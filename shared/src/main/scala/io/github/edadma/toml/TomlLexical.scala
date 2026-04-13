package io.github.edadma.toml

import scala.util.parsing.combinator.lexical.StdLexical
import scala.util.parsing.input.CharArrayReader.EofCh

/** Lexical analyzer based on `StdLexical`, with standard tokens plus TOML-specific literals (TOML 1.0.0 string escapes). */
class TomlLexical extends StdLexical:

  delimiters ++= Seq("[", "]", "{", "}", "=", ",", ".")

  case class DateTimeLit(lexeme: String) extends Token:
    def chars: String = lexeme

  /** Emitted for `\n` or `\r\n` so the parser can require line breaks between statements. */
  case class NewlineToken() extends Token:
    def chars: String = "\n"

  case class MultilineBasicStr(lexeme: String) extends Token:
    def chars: String = lexeme

  case class MultilineLiteralStr(lexeme: String) extends Token:
    def chars: String = lexeme

  /** TOML 1.0.0: control chars in comments except tab (U+0009). */
  private def isIllegalCommentChar(c: Char): Boolean =
    val cp = c.toInt
    (cp >= 0x0000 && cp <= 0x0008) || (cp >= 0x000a && cp <= 0x001f) || cp == 0x007f

  /** Basic / literal single-line: disallow controls except tab (U+0009). */
  private def isIllegalSlStringChar(c: Char): Boolean =
    val cp = c.toInt
    (cp >= 0x0000 && cp <= 0x0008) || (cp >= 0x000a && cp <= 0x001f) || cp == 0x007f

  /** Multiline basic: allow tab, LF, CR; forbid other controls (spec 1.0.0). */
  private def isIllegalMlBasicChar(c: Char): Boolean =
    val cp = c.toInt
    (cp >= 0x0000 && cp <= 0x0008) || cp == 0x000b || cp == 0x000c || (cp >= 0x000e && cp <= 0x001f) || cp == 0x007f

  /** Multiline literal: same allowed line breaks as multiline basic. */
  private def isIllegalMlLiteralChar(c: Char): Boolean = isIllegalMlBasicChar(c)

  override def whitespaceChar =
    elem("", ch => ch == ' ' || ch == '\t')

  override def whitespace: Parser[Any] =
    rep(whitespaceChar | ('#' ~> rep(elem("", c => c != EofCh && c != '\n' && c != '\r' && !isIllegalCommentChar(c)))))

  /** TOML 1.0.0 bare keys: ASCII letters, digits, `_`, `-` only. */
  override def identChar: Parser[Char] =
    elem("", c => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c.isDigit || c == '_' || c == '-')

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
    if n >= 0xd800 && n <= 0xdfff then escFail("invalid \\u escape (surrogate code point)")
    else if n >= 0 && n <= 0xffff && Character.isBmpCodePoint(n) then success(List(n.toChar))
    else escFail("invalid \\u escape")

  private def unicodeAny(n: Int): Parser[List[Char]] =
    if Character.isValidCodePoint(n) then success(Character.toChars(n).toList)
    else escFail("invalid \\U escape")

  /** Parse exactly `n` hex digits as unsigned (supports \\U up to 0x10FFFF). */
  private def hexCodeUnits(n: Int): Parser[Long] =
    repN(n, hexDigit) ^^ (ds => java.lang.Long.parseUnsignedLong(ds.mkString, 16))

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
        case 'u'  => hexCodeUnits(4).flatMap(v => unicodeBmp(v.toInt))
        case 'U'  =>
          hexCodeUnits(8).flatMap { v =>
            if v > 0x10ffffL then escFail("invalid \\U escape (code point out of range)")
            else if v >= 0xd800 && v <= 0xdfff then escFail("invalid \\U escape (surrogate code point)")
            else unicodeAny(v.toInt)
          }
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

  /** Two `"` not starting the closing `"""` — each pair is two literal `"` in the string (TOML 1.0.0). */
  private def mlBasicTwoQuotes: Parser[List[Char]] =
    (elem('"') ~ elem('"')) ~ not(elem('"')) ^^^ List('"', '"')

  /** One `"` immediately before the closing `"""` (e.g. `statement."` + `"""`). */
  private def mlBasicQuoteBeforeClose: Parser[List[Char]] =
    elem('"') ~ guard(elem('"') ~ elem('"') ~ elem('"')) ^^^ List('"')

  private def slBasicStringBody: Parser[List[Char]] =
    def plain: Parser[List[Char]] =
      elem("", c => c != '\"' && c != '\\' && c != '\n' && c != EofCh && !isIllegalSlStringChar(c)) ^^ (List(_))
    rep(basicEscape | plain) ^^ (_.flatten)

  private def mlBasicStringBody: Parser[List[Char]] =
    def plainRun: Parser[List[Char]] =
      rep1(elem("", c => c != '\"' && c != '\\' && c != EofCh && !isIllegalMlBasicChar(c))) ^^ (_.toList)
    rep(
      lineEndingBackslash
        | basicEscape
        | mlBasicQuoteBeforeClose
        | mlBasicTwoQuotes
        | mlBasicOneQuote
        | plainRun,
    ) ^^ (_.flatten)

  private def triple(ch: Char): Parser[Unit] =
    elem("", _ == ch) ~ elem("", _ == ch) ~ elem("", _ == ch) ^^^ ()

  private def mlBasicString: Parser[Token] =
    (triple('"') ~> opt(elem("", _ == '\n'))) ~ mlBasicStringBody <~ triple('"') ^^ { case _ ~ chars =>
      MultilineBasicStr(chars.mkString)
    }

  private def slBasicString: Parser[Token] =
    elem("", _ == '"') ~> slBasicStringBody <~ elem("", _ == '"') ^^ (cs => StringLit(cs.mkString))

  private def slLiteralString: Parser[Token] =
    elem("", _ == '\'') ~> rep(
      elem("", c => c != '\'' && c != '\n' && c != EofCh && !isIllegalSlStringChar(c)),
    ) <~ elem("", _ == '\'') ^^ (cs => StringLit(cs.mkString))

  private def mlLiteralString: Parser[Token] =
    triple('\'') ~> opt(elem("", _ == '\n')) >> { _ =>
      /** `''` followed by `'''` → two apostrophes, then closing delimiter. */
      def mlLitTwoBeforeClose: Parser[List[Char]] =
        (elem("", _ == '\'') ~ elem("", _ == '\'')) ~ guard(
          elem("", _ == '\'') ~ elem("", _ == '\'') ~ elem("", _ == '\''),
        ) ^^^ List('\'', '\'')

      /** One `'` immediately before closing `'''`. */
      def mlLitOneBeforeClose: Parser[List[Char]] =
        elem("", _ == '\'') ~ guard(
          elem("", _ == '\'') ~ elem("", _ == '\'') ~ elem("", _ == '\''),
        ) ^^^ List('\'')

      /** Two `'` not followed by `'''` — two apostrophe characters in the value. */
      def mlLitTwoQuotes: Parser[List[Char]] =
        (elem("", _ == '\'') ~ elem("", _ == '\'')) ~ guard(not(elem("", _ == '\''))) ^^^ List('\'', '\'')

      /** One `'` not starting `''` or `'''`. */
      def mlLitOneQuote: Parser[List[Char]] =
        elem("", _ == '\'') ~ guard(not(elem("", _ == '\'') ~ elem("", _ == '\''))) ^^^ List('\'')

      def chunk: Parser[List[Char]] =
        mlLitTwoBeforeClose
          | mlLitOneBeforeClose
          | mlLitTwoQuotes
          | mlLitOneQuote
          | rep1(elem("", c => c != '\'' && c != EofCh && !isIllegalMlLiteralChar(c))) ^^ (_.toList)

      rep(chunk) <~ triple('\'') ^^ (parts => MultilineLiteralStr(parts.flatten.mkString))
    }

  /** `inf` / `nan` must not prefix a longer bare word (`infinity`, `nan_plus`, …). */
  private def notBareWordCont: Parser[Unit] =
    guard(not(identChar))

  private def specialFloatToken: Parser[Token] =
    (elem("sign", c => c == '+' || c == '-') ~ (
      (elem("", _ == 'i') ~ elem("", _ == 'n') ~ elem("", _ == 'f')) ~ notBareWordCont ^^^ "inf"
        | (elem("", _ == 'n') ~ elem("", _ == 'a') ~ elem("", _ == 'n')) ~ notBareWordCont ^^^ "nan"
    )) ^^ { case s ~ w => NumericLit(s.toString + w) }
      | (elem("", _ == 'i') ~ elem("", _ == 'n') ~ elem("", _ == 'f')) ~ notBareWordCont ^^^ NumericLit("inf")
      | (elem("", _ == 'n') ~ elem("", _ == 'a') ~ elem("", _ == 'n')) ~ notBareWordCont ^^^ NumericLit("nan")

  private def fractional: Parser[List[Char]] =
    elem("", _ == '.') ~ decRun ^^ { case d ~ ds => d :: ds }

  private def expPart: Parser[List[Char]] =
    elem("eE", c => c == 'e' || c == 'E') ~ opt(elem("sign", c => c == '+' || c == '-')) ~ decRun ^^ {
      case e ~ s ~ ds => e :: s.toList ::: ds
    }

  /** True when fractional is `.` followed only by digits (exclude so `3.14159` can be key `3` + `14159`). */
  private def fractionalIsOnlyDigits(fr: List[Char]): Boolean =
    fr.length >= 2 && fr.head == '.' && fr.tail.nonEmpty && fr.tail.forall(_.isDigit)

  private def floatToken: Parser[Token] =
    opt(elem("sign", c => c == '+' || c == '-')) ~ decRun ~ opt(fractional) ~ opt(expPart) ^? {
      case s ~ intPart ~ frac ~ exp
          if exp.isDefined || frac.exists(fr => !fractionalIsOnlyDigits(fr)) =>
        val sb = new StringBuilder
        s.foreach(sb.append)
        intPart.foreach(sb.append)
        frac.foreach(_.foreach(sb.append))
        exp.foreach(_.foreach(sb.append))
        NumericLit(sb.toString)
    }

  private def radixIntegerToken: Parser[Token] =
    elem("", _ == '0') ~> (
      (elem("", _ == 'x') ~> radixRun(hexDigit)) ^^ { ds => NumericLit("0x" + ds.mkString) }
        | (elem("", _ == 'o') ~> radixRun(octDigit)) ^^ { ds => NumericLit("0o" + ds.mkString) }
        | (elem("", _ == 'b') ~> radixRun(binDigit)) ^^ { ds => NumericLit("0b" + ds.mkString) }
    )

  private def digits2: Parser[List[Char]] = repN(2, digit)
  private def digits4: Parser[List[Char]] = repN(4, digit)

  private def fracSeconds: Parser[List[Char]] =
    elem("", _ == '.') ~ rep1(digit) ^^ { case d ~ ds => d :: ds }

  /** `HH:MM:SS` with optional fractional seconds (required for datetimes and local times, TOML 1.0.0). */
  private def partialTimeFull: Parser[List[Char]] =
    digits2 ~ elem("", _ == ':') ~ digits2 ~ elem("", _ == ':') ~ digits2 ~ opt(fracSeconds) ^^ {
      case hh ~ c1 ~ mm ~ c2 ~ ss ~ fo =>
        hh ::: c1 :: mm ::: c2 :: ss ::: fo.getOrElse(Nil)
    }

  private def timeOffset: Parser[List[Char]] =
    elem("", c => c == 'Z' || c == 'z') ^^^ List('Z')
      | (elem("sign", c => c == '+' || c == '-') ~ digits2 ~ elem("", _ == ':') ~ digits2 ^^ {
          case s ~ hh ~ c ~ mm => s :: hh ::: c :: mm
        })

  /** `full-date` optionally followed by `T`/space + partial-time + optional offset. */
  private def dateTimeLit: Parser[Token] =
    digits4 ~ elem("", _ == '-') ~ digits2 ~ elem("", _ == '-') ~ digits2 ~ opt(
      elem("", c => c == 'T' || c == 't' || c == ' ') ~ partialTimeFull ~ opt(timeOffset),
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

  /** Local time of day only (`HH:MM:SS` with optional fraction). */
  private def localTimeOnlyLit: Parser[Token] =
    partialTimeFull ^^ (t => DateTimeLit(t.mkString))

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

  private def newlineToken: Parser[Token] =
    (elem('\r') ~ elem('\n')) ^^^ NewlineToken()
      | elem('\n') ^^^ NewlineToken()
      | elem('\r') ~> failure("bare carriage return")

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
      | bareKeyToken
      | intTokenDecimal
      | newlineToken
      | EofCh ^^^ EOF
      | delim
      | failure("illegal character")

  /** For token parsers that need to recognize [[DateTimeLit]] without path-dependent types. */
  def dateTimeLexeme(t: Token): Option[String] =
    t match
      case d: DateTimeLit => Some(d.chars)
      case _              => None

end TomlLexical
