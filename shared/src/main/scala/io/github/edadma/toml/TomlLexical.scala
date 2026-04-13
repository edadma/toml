package io.github.edadma.toml

import scala.util.parsing.combinator.lexical.StdLexical
import scala.util.parsing.input.CharArrayReader.EofCh

/** Lexical analyzer: [[StdLexical]] / standard tokens plus TOML-specific literals (TOML 1.0.0 string escapes). */
class TomlLexical extends StdLexical:

  delimiters ++= Seq("[[", "]]", "[", "]", "{", "}", "=", ",", ".")
  reserved ++= Seq("true", "false")

  case class DateTimeLit(lexeme: String) extends Token:
    def chars: String = lexeme

  override def whitespaceChar =
    elem("", ch => ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n')

  override def whitespace: Parser[Any] =
    rep(whitespaceChar | ('#' ~> rep(chrExcept(EofCh, '\n'))))

  override def identChar: Parser[Char] = letter | digit | elem('_') | elem('-')

  private def hexDigit: Parser[Char] =
    elem("hex digit", c => c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))

  private def binDigit: Parser[Char] =
    elem("binary digit", c => c == '0' || c == '1')

  private def octDigit: Parser[Char] =
    elem("octal digit", c => c >= '0' && c <= '7')

  private def decRun: Parser[List[Char]] =
    rep1(digit) ~ rep(elem("_", _ == '_') ~> rep1(digit)) ^^ { case h ~ t => h ::: t.flatten }

  private def radixRun(d: Parser[Char]): Parser[List[Char]] =
    rep1(d) ~ rep(elem("_", _ == '_') ~> rep1(d)) ^^ { case h ~ t => h ::: t.flatten }

  private def escFail(msg: String): Parser[Nothing] = err(msg)

  private def unicodeBmp(n: Int): Parser[List[Char]] =
    if n >= 0 && n <= 0xffff && Character.isBmpCodePoint(n) then success(List(n.toChar))
    else escFail("invalid \\u escape")

  private def unicodeAny(n: Int): Parser[List[Char]] =
    if Character.isValidCodePoint(n) then success(Character.toChars(n).toList)
    else escFail("invalid \\U escape")

  private def hexValue(n: Int): Parser[Int] =
    repN(n, hexDigit) ^^ (ds => Integer.parseInt(ds.mkString, 16))

  private def repN[T](k: Int, p: Parser[T]): Parser[List[T]] =
    if k <= 0 then success(Nil)
    else repN(k - 1, p) ~ p ^^ { case xs ~ x => xs :+ x }

  private def basicEscape: Parser[List[Char]] =
    '\\' ~> (
      elem("escape", _ != EofCh) >> {
        case 'b'  => success(List('\u0008'))
        case 't'  => success(List('\t'))
        case 'n'  => success(List('\n'))
        case 'f'  => success(List('\u000c'))
        case 'r'  => success(List('\r'))
        case '"'  => success(List('"'))
        case '\\' => success(List('\\'))
        case 'u'  => hexValue(4).flatMap(unicodeBmp)
        case 'U'  => hexValue(8).flatMap(unicodeAny)
        case 'e' | 'x' =>
          escFail("reserved escape \\e / \\x (invalid in TOML 1.0.0)")
        case c    => escFail(s"invalid escape \\$c")
      }
    )

  /** v0.5.0+ "line-ending backslash": trim `\` and following whitespace until next non-whitespace or `"""`. */
  private def lineEndingBackslash: Parser[List[Char]] =
    guard(
      elem('\\') ~ rep(elem("", c => c == ' ' || c == '\t')) ~ (
        (elem('\r') ~ elem('\n')) | elem('\n')
      ),
    ) ~> elem('\\') ~> rep(elem("", c => c == ' ' || c == '\t')) ~> (
      (elem('\r') ~ elem('\n')) | elem('\n')
    ) ~> rep(elem("", _.isWhitespace)) ^^^ Nil

  /** One `"` not starting the closing `"""`. */
  private def mlBasicOneQuote: Parser[List[Char]] =
    elem('"') ~ not(elem('"') ~ elem('"')) ^^^ List('"')

  /** Two `"` not starting the closing `"""`. */
  private def mlBasicTwoQuotes: Parser[List[Char]] =
    (elem('"') ~ elem('"')) ~ not(elem('"')) ^^^ List('"', '"')

  private def slBasicStringBody: Parser[List[Char]] =
    def plain: Parser[List[Char]] = chrExcept('\"', '\\', '\n', EofCh) ^^ (List(_))
    rep(basicEscape | plain) ^^ (_.flatten)

  private def mlBasicStringBody: Parser[List[Char]] =
    def plainRun: Parser[List[Char]] = rep1(chrExcept('\"', '\\', EofCh)) ^^ (_.toList)
    rep(
      lineEndingBackslash
        | basicEscape
        | mlBasicTwoQuotes
        | mlBasicOneQuote
        | plainRun,
    ) ^^ (_.flatten)

  private def triple(ch: Char): Parser[Unit] =
    elem("", _ == ch) ~ elem("", _ == ch) ~ elem("", _ == ch) ^^^ ()

  private def mlBasicString: Parser[Token] =
    (triple('"') ~> opt(elem("", _ == '\n'))) ~ mlBasicStringBody <~ triple('"') ^^ { case _ ~ chars =>
      StringLit(chars.mkString)
    }

  private def slBasicString: Parser[Token] =
    elem("", _ == '"') ~> slBasicStringBody <~ elem("", _ == '"') ^^ (cs => StringLit(cs.mkString))

  private def slLiteralString: Parser[Token] =
    elem("", _ == '\'') ~> rep(chrExcept('\'', '\n', EofCh)) <~ elem("", _ == '\'') ^^ (cs =>
      StringLit(cs.mkString),
    )

  private def mlLiteralString: Parser[Token] =
    triple('\'') ~> opt(elem("", _ == '\n')) >> { _ =>
      /** `''` plus a non-quote character (two quotes in content). */
      def mlLitTwoQuotes: Parser[List[Char]] =
        (elem("", _ == '\'') ~ elem("", _ == '\'') ~ chrExcept('\'')) ^^ { case a ~ b ~ c =>
          List(a, b, c)
        }

      /** One `'` not starting the closing `'''`. */
      def mlLitOneQuote: Parser[List[Char]] =
        elem("", _ == '\'') ~ not(elem("", _ == '\'') ~ elem("", _ == '\'')) ^^^ List('\'')

      def chunk: Parser[List[Char]] =
        mlLitTwoQuotes
          | mlLitOneQuote
          | rep1(chrExcept('\'', EofCh)) ^^ (_.toList)

      rep(chunk) <~ triple('\'') ^^ (parts => StringLit(parts.flatten.mkString))
    }

  private def specialFloatToken: Parser[Token] =
    (elem("sign", c => c == '+' || c == '-') ~ (
      (elem("", _ == 'i') ~ elem("", _ == 'n') ~ elem("", _ == 'f')) ^^^ "inf"
        | (elem("", _ == 'n') ~ elem("", _ == 'a') ~ elem("", _ == 'n')) ^^^ "nan"
    )) ^^ { case s ~ w => NumericLit(s.toString + w) }
      | (elem("", _ == 'i') ~ elem("", _ == 'n') ~ elem("", _ == 'f')) ^^^ NumericLit("inf")
      | (elem("", _ == 'n') ~ elem("", _ == 'a') ~ elem("", _ == 'n')) ^^^ NumericLit("nan")

  private def fractional: Parser[List[Char]] =
    elem("", _ == '.') ~ decRun ^^ { case d ~ ds => d :: ds }

  private def expPart: Parser[List[Char]] =
    elem("eE", c => c == 'e' || c == 'E') ~ opt(elem("sign", c => c == '+' || c == '-')) ~ decRun ^^ {
      case e ~ s ~ ds => e :: s.toList ::: ds
    }

  private def floatToken: Parser[Token] =
    opt(elem("sign", c => c == '+' || c == '-')) ~ decRun ~ opt(fractional) ~ opt(expPart) ^? {
      case s ~ intPart ~ frac ~ exp if frac.isDefined || exp.isDefined =>
        val sb = new StringBuilder
        s.foreach(sb.append)
        intPart.foreach(sb.append)
        frac.foreach(_.foreach(sb.append))
        exp.foreach(_.foreach(sb.append))
        NumericLit(sb.toString)
    }

  private def radixIntegerToken: Parser[Token] =
    elem("", _ == '0') ~> (
      (elem("", c => c == 'x' || c == 'X') ~> radixRun(hexDigit)) ^^ { ds =>
        NumericLit("0x" + ds.mkString)
      }
        | (elem("", c => c == 'o' || c == 'O') ~> radixRun(octDigit)) ^^ { ds =>
          NumericLit("0o" + ds.mkString)
        }
        | (elem("", c => c == 'b' || c == 'B') ~> radixRun(binDigit)) ^^ { ds =>
          NumericLit("0b" + ds.mkString)
        }
    )

  private def digits2: Parser[List[Char]] = repN(2, digit)
  private def digits4: Parser[List[Char]] = repN(4, digit)

  private def fracSeconds: Parser[List[Char]] =
    elem("", _ == '.') ~ rep1(digit) ^^ { case d ~ ds => d :: ds }

  private def partialTime: Parser[List[Char]] =
    digits2 ~ elem("", _ == ':') ~ digits2 ~ opt(
      elem("", _ == ':') ~> digits2 ~ opt(fracSeconds),
    ) ^^ { case hh ~ c1 ~ mm ~ o =>
      val base = hh ::: c1 :: mm
      o match
        case None => base
        case Some(ss ~ fo) =>
          base ::: List(':') ::: ss ::: fo.getOrElse(Nil)
    }

  private def timeOffset: Parser[List[Char]] =
    elem("", _ == 'Z') ^^^ List('Z')
      | (elem("sign", c => c == '+' || c == '-') ~ digits2 ~ elem("", _ == ':') ~ digits2 ^^ {
          case s ~ hh ~ c ~ mm => s :: hh ::: c :: mm
        })

  /** `full-date` optionally followed by `T`/space + partial-time + optional offset. */
  private def dateTimeLit: Parser[Token] =
    digits4 ~ elem("", _ == '-') ~ digits2 ~ elem("", _ == '-') ~ digits2 ~ opt(
      elem("", c => c == 'T' || c == ' ') ~ partialTime ~ opt(timeOffset),
    ) ^^ { case y ~ _ ~ mo ~ _ ~ d ~ rest =>
      val sb = new StringBuilder
      sb.append(y.mkString).append('-').append(mo.mkString).append('-').append(d.mkString)
      rest.foreach { case sep ~ t ~ off =>
        sb.append(sep)
        t.foreach(sb.append)
        off.foreach(_.foreach(sb.append))
      }
      DateTimeLit(sb.toString)
    }

  /** Local time of day only (`HH:MM`, optional seconds and fraction). */
  private def localTimeOnlyLit: Parser[Token] =
    partialTime ^^ (t => DateTimeLit(t.mkString))

  private def intTokenDecimal: Parser[Token] =
    (opt(elem("sign", c => c == '+' || c == '-')) ~ decRun) <~
      guard(not(letter | elem('_') | elem('-'))) ^^ { case s ~ ds =>
        val sb = new StringBuilder
        s.foreach(sb.append)
        ds.foreach(sb.append)
        NumericLit(sb.toString)
      }

  private def bareKeyToken: Parser[Token] =
    rep1(identChar) ^^ { cs => processIdent(cs.mkString) }

  override def token: Parser[Token] =
    mlBasicString
      | mlLiteralString
      | slBasicString
      | slLiteralString
      | specialFloatToken
      | floatToken
      | radixIntegerToken
      | dateTimeLit
      | localTimeOnlyLit
      | intTokenDecimal
      | bareKeyToken
      | EofCh ^^^ EOF
      | delim
      | failure("illegal character")

  /** For token parsers that need to recognize [[DateTimeLit]] without path-dependent types. */
  def dateTimeLexeme(t: Token): Option[String] =
    t match
      case d: DateTimeLit => Some(d.chars)
      case _              => None

end TomlLexical
