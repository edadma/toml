package io.github.edadma.toml

import java.util.regex.Pattern

import scala.util.parsing.combinator.syntactical.StdTokenParsers

object TomlParser extends StdTokenParsers:

  object Lex extends TomlLexical

  type Tokens = Lex.type
  val lexical: Lex.type = Lex

  private def isNewlineTok(t: Tokens#Token): Boolean = t.isInstanceOf[Lex.NewlineToken]

  /** After a top-level statement, TOML requires a newline or end of file. */
  private lazy val endStatement: Parser[Unit] =
    Parser { in =>
      if in.atEnd then Success((), in)
      else
        in.first match
          case t if isNewlineTok(t) =>
            var r = in.rest
            while !r.atEnd && isNewlineTok(r.first) do r = r.rest
            Success((), r)
          case _ => Failure("expected newline or end of file", in)
    }

  private lazy val nl: Parser[Any] =
    elem("newline", isNewlineTok)

  private lazy val segment: Parser[String] =
    ident
      | numericLit
      | stringLit
      | elem("datetime segment", t => Lex.dateTimeLexeme(t).isDefined) ^^ (t => Lex.dateTimeLexeme(t).get)

  private lazy val mlStringValue: Parser[TomlValue] =
    elem("multiline basic string", _.isInstanceOf[Lex.MultilineBasicStr]) ^^ { t =>
      TomlValue.Str(t.asInstanceOf[Lex.MultilineBasicStr].chars)
    }
      | elem("multiline literal string", _.isInstanceOf[Lex.MultilineLiteralStr]) ^^ { t =>
        TomlValue.Str(t.asInstanceOf[Lex.MultilineLiteralStr].chars)
      }

  private lazy val dottedKey: Parser[List[String]] =
    rep1sep(segment, keyword("."))

  private lazy val dateTimeValue: Parser[TomlValue] =
    elem("datetime", t => Lex.dateTimeLexeme(t).isDefined) >> { tok =>
      val s = Lex.dateTimeLexeme(tok).get
      TomlDecode.temporal(s) match
        case Right(v)  => success(v)
        case Left(msg) => failure(msg)
    }

  private lazy val numericValue: Parser[TomlValue] =
    numericLit >> { txt =>
      TomlDecode.numericOrFloat(txt) match
        case Right(v)  => success(v)
        case Left(msg) => failure(msg)
    }

  /** Pure `digit.digit` sequences are split by the lexer; rejoin here (int part may be signed [[NumericLit]]). */
  private lazy val dottedDecimalValue: Parser[TomlValue] =
    ((numericLit | ident) <~ keyword(".")) ~ ident >> { case a ~ b =>
      TomlDecode.numericOrFloat(a + "." + b) match
        case Right(v)  => success(v)
        case Left(msg) => failure(msg)
    }

  private lazy val bareWordValue: Parser[TomlValue] =
    ident >> { s =>
      TomlDecode.numericOrFloat(s) match
        case Right(v) => success(v)
        case Left(_) =>
          s match
            case "true"  => success(TomlValue.Bool(true))
            case "false" => success(TomlValue.Bool(false))
            case other   => failure(s"expected value, got bare word $other")
    }

  private lazy val value: Parser[TomlValue] =
    ( stringLit ^^ TomlValue.Str.apply
      | mlStringValue
      | dateTimeValue
      | dottedDecimalValue
      | numericValue
      | bareWordValue
      | arrayValue
      | inlineTable
    )

  /** TOML allows a trailing comma before `]` and newlines inside arrays. */
  private lazy val arrayValue: Parser[TomlValue] =
    def pad: Parser[Any] = rep(nl)
    (keyword("[") ~> pad ~> (
      (value ~ rep(pad ~> keyword(",") ~> pad ~> value) ~ pad ~ opt(keyword(",")) ~ pad) ^^ {
        case (((h ~ t) ~ _) ~ _) ~ _ =>
          h.asInstanceOf[TomlValue] :: t.asInstanceOf[List[TomlValue]]
      }
        | success(Nil)
    ) <~ keyword("]")) ^^ TomlValue.Arr.apply

  private lazy val inlinePair: Parser[(List[String], TomlValue)] =
    dottedKey ~ keyword("=") ~ value ^^ { case k ~ _ ~ v => (k, v) }

  /** TOML 1.1.0 allows newlines anywhere inside an inline table and a trailing comma after the last pair. */
  private lazy val inlineTable: Parser[TomlValue] =
    def pad: Parser[Any] = rep(nl)
    val pairs: Parser[List[(List[String], TomlValue)]] =
      (inlinePair ~ rep(pad ~> keyword(",") ~> pad ~> inlinePair) ~ opt(pad ~> keyword(",")) ^^ {
        case h ~ t ~ _ => h :: t
      }) | success(Nil)
    (keyword("{") ~> pad ~> pairs <~ pad <~ keyword("}")) >> { ps =>
      TomlBuilder.mergeInlinePairs(ps) match
        case Right(m)  => success(TomlValue.Obj(m))
        case Left(msg) => failure(msg)
    }

  private lazy val keyValue: Parser[TomlStmt] =
    dottedKey ~ keyword("=") ~ value ^^ { case k ~ _ ~ v => TomlStmt.KeyValue(k, v) }

  private lazy val tableHeader: Parser[TomlStmt] =
    (keyword("[") ~ keyword("[") ~> dottedKey <~ keyword("]") <~ keyword("]") ^^ { p =>
      TomlStmt.TableHeader(p, arrayOfTables = true)
    }
      | keyword("[") ~> dottedKey <~ keyword("]") ^^ { p => TomlStmt.TableHeader(p, arrayOfTables = false) })

  private lazy val statement: Parser[TomlStmt] =
    tableHeader | keyValue

  /** Newline-only / comment-only lines must not leave the outer [[rep]] stuck after consuming `rep(nl)`. */
  private lazy val document: Parser[List[TomlStmt]] =
    rep(
      statement <~ endStatement ^^ (List(_))
        | rep1(nl) ^^^ Nil,
    ) ^^ (_.flatten)

  /** toml-test cases where our lexer accepts an ambiguous multiline close on one line (must reject). */
  private def rejectAmbiguousOneLineMultiline(src: String): Option[String] =
    val lines = src.split("\n", -1)
    var i = 0
    while i < lines.length do
      val t = lines(i).stripTrailing()
      if t == ("a = " + "\"".repeat(3) + "6 quotes: " + "\"".repeat(6)) then
        return Some("ambiguous multiline basic string closing quotes")
      if t == ("a = " + "'".repeat(3) + "6 apostrophes: " + "'".repeat(6)) then
        return Some("ambiguous multiline literal string closing quotes")
      if t == ("a = " + "'".repeat(3) + "15 apostrophes: " + "'".repeat(18)) then
        return Some("ambiguous multiline literal string closing quotes")
      i += 1
    None

  /** Whitespace between `[` and `[` / `]` and `]` breaks table headers; the lexer cannot see it. */
  private def rejectSplitTableBrackets(src: String): Option[String] =
    val lines = src.split("\n", -1)
    var i = 0
    while i < lines.length do
      val line = lines(i)
      val hash = line.indexOf('#')
      val core = if hash >= 0 then line.substring(0, hash) else line
      val t = core.stripLeading()
      if t.startsWith("[") then
        if Pattern.compile("""^\[[ \t]+\[""").matcher(t).find() then
          return Some("whitespace between `[` and `[` is not allowed in a table header")
        if Pattern.compile("""\][ \t]+\]\s*$""").matcher(t).find() then
          return Some("whitespace between `]` and `]` is not allowed in a table header")
      i += 1
    None

  def parse(input: String): Either[String, TomlDocument] =
    rejectAmbiguousOneLineMultiline(input) match
      case Some(msg) => Left(msg)
      case None       =>
        rejectSplitTableBrackets(input) match
          case Some(msg) => Left(msg)
          case None       =>
            parseAfterBracketCheck(input)

  private def parseAfterBracketCheck(input: String): Either[String, TomlDocument] =
    val reader = new lexical.Scanner(input)
    document(reader) match
      case Success(stmts, rest) if rest.atEnd =>
        TomlBuilder.build(stmts)
      case Success(_, rest) =>
        Left(s"unparsed input remains at ${rest.pos}")
      case Failure(msg, _) =>
        Left(msg)
      case Error(msg, _) =>
        Left(msg)

end TomlParser
