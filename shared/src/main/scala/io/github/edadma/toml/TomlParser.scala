package io.github.edadma.toml

import scala.util.parsing.combinator.PackratParsers
import scala.util.parsing.combinator.syntactical.StdTokenParsers

object TomlParser extends StdTokenParsers with PackratParsers:

  object Lex extends TomlLexical

  type Tokens = Lex.type
  val lexical: Lex.type = Lex

  private lazy val segment: PackratParser[String] =
    ident
      | numericLit
      | stringLit
      | elem("datetime segment", t => Lex.dateTimeLexeme(t).isDefined) ^^ (t => Lex.dateTimeLexeme(t).get)

  private lazy val dottedKey: PackratParser[List[String]] =
    rep1sep(segment, keyword("."))

  private lazy val dateTimeValue: PackratParser[TomlValue] =
    elem("datetime", t => Lex.dateTimeLexeme(t).isDefined) >> { tok =>
      val s = Lex.dateTimeLexeme(tok).get
      TomlDecode.temporal(s) match
        case Right(v)  => success(v)
        case Left(msg) => failure(msg)
    }

  private lazy val numericValue: PackratParser[TomlValue] =
    numericLit >> { txt =>
      TomlDecode.numericOrFloat(txt) match
        case Right(v)  => success(v)
        case Left(msg) => failure(msg)
    }

  private lazy val value: PackratParser[TomlValue] =
    ( stringLit ^^ TomlValue.Str.apply
      | keyword("true") ^^^ TomlValue.Bool(true)
      | keyword("false") ^^^ TomlValue.Bool(false)
      | dateTimeValue
      | numericValue
      | arrayValue
      | inlineTable
    )

  private lazy val arrayValue: PackratParser[TomlValue] =
    keyword("[") ~> repsep(value, keyword(",")) <~ opt(keyword(",")) <~ keyword("]") ^^ TomlValue.Arr.apply

  private lazy val inlinePair: PackratParser[(List[String], TomlValue)] =
    dottedKey ~ keyword("=") ~ value ^^ { case k ~ _ ~ v => (k, v) }

  private lazy val inlineTable: PackratParser[TomlValue] =
    (keyword("{") ~> repsep(inlinePair, keyword(",")) <~ opt(keyword(",")) <~ keyword("}")) >> { pairs =>
      TomlBuilder.mergeInlinePairs(pairs) match
        case Right(m)  => success(TomlValue.Obj(m))
        case Left(msg) => failure(msg)
    }

  private lazy val keyValue: PackratParser[TomlStmt] =
    dottedKey ~ keyword("=") ~ value ^^ { case k ~ _ ~ v => TomlStmt.KeyValue(k, v) }

  private lazy val tableHeader: PackratParser[TomlStmt] =
    (keyword("[[") ~> dottedKey <~ keyword("]]") ^^ { p => TomlStmt.TableHeader(p, arrayOfTables = true) }
      | keyword("[") ~> dottedKey <~ keyword("]") ^^ { p => TomlStmt.TableHeader(p, arrayOfTables = false) })

  private lazy val statement: PackratParser[TomlStmt] =
    tableHeader | keyValue

  private lazy val document: Parser[List[TomlStmt]] =
    rep(statement)

  def parse(input: String): Either[String, TomlDocument] =
    val reader = new PackratReader(new Lex.Scanner(input))
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
