package io.github.edadma.toml

import java.time as jt

import scala.collection.immutable.VectorMap

/** Thrown by [[TomlValue]] `to*` methods when the runtime type does not match. */
final class TomlTypeMismatch(expected: String, val value: TomlValue)
    extends RuntimeException(s"expected TOML $expected, got ${TomlValue.typeLabel(value)}")

sealed trait TomlValue:

  final def toStr: String = this match
    case TomlValue.Str(s) => s
    case _                => throw TomlTypeMismatch("string", this)

  /** TOML integer as `Long` (Java/Scala `Int` is too small for the full TOML 64-bit range). */
  final def toLong: Long = this match
    case TomlValue.Num(n) => n
    case _                => throw TomlTypeMismatch("integer", this)

  /** Same as [[toLong]]; name matches common “config int” wording. */
  final def toInt: Long = toLong

  final def toDouble: Double = this match
    case TomlValue.FloatVal(d) => d
    case _                       => throw TomlTypeMismatch("float", this)

  final def toBool: Boolean = this match
    case TomlValue.Bool(b) => b
    case _                 => throw TomlTypeMismatch("bool", this)

  final def toTable: VectorMap[String, TomlValue] = this match
    case TomlValue.Obj(fields) => fields
    case _                       => throw TomlTypeMismatch("table", this)

  final def toArr: List[TomlValue] = this match
    case TomlValue.Arr(elems) => elems
    case _                      => throw TomlTypeMismatch("array", this)

  final def toOffsetDateTime: jt.OffsetDateTime = this match
    case TomlValue.OffsetDateTime(v) => v
    case _                           => throw TomlTypeMismatch("offset date-time", this)

  final def toLocalDateTime: jt.LocalDateTime = this match
    case TomlValue.LocalDateTime(v) => v
    case _                          => throw TomlTypeMismatch("local date-time", this)

  final def toLocalDate: jt.LocalDate = this match
    case TomlValue.LocalDate(v) => v
    case _                       => throw TomlTypeMismatch("local date", this)

  final def toLocalTime: jt.LocalTime = this match
    case TomlValue.LocalTime(v) => v
    case _                       => throw TomlTypeMismatch("local time", this)

object TomlValue:

  def typeLabel(v: TomlValue): String = v match
    case _: Str           => "string"
    case _: Num           => "integer"
    case _: FloatVal      => "float"
    case _: Bool          => "bool"
    case _: Arr           => "array"
    case _: Obj           => "table"
    case _: OffsetDateTime => "offset date-time"
    case _: LocalDateTime => "local date-time"
    case _: LocalDate     => "local date"
    case _: LocalTime     => "local time"

  case class Str(s: String) extends TomlValue
  case class Num(n: Long) extends TomlValue
  case class FloatVal(d: Double) extends TomlValue
  case class Bool(b: Boolean) extends TomlValue
  case class Arr(elems: List[TomlValue]) extends TomlValue
  case class Obj(fields: VectorMap[String, TomlValue]) extends TomlValue

  case class OffsetDateTime(v: jt.OffsetDateTime) extends TomlValue
  case class LocalDateTime(v: jt.LocalDateTime) extends TomlValue
  case class LocalDate(v: jt.LocalDate) extends TomlValue
  case class LocalTime(v: jt.LocalTime) extends TomlValue

/** Resolved document: root key-values and nested tables. Keys iterate in file / first-seen order. */
case class TomlDocument(root: VectorMap[String, TomlValue]):

  /** Dot-separated path (`server.port` → root → `server` table → `port`). */
  def get(path: String): Option[TomlValue] =
    val segs = path.split('.').filter(_.nonEmpty).toList
    if segs.isEmpty then None
    else navigate(root, segs)

  def getString(path: String): Option[String] =
    get(path).collect { case TomlValue.Str(s) => s }

  /** TOML integer as `Long`. */
  def getLong(path: String): Option[Long] =
    get(path).collect { case TomlValue.Num(n) => n }

  /** Same as [[getLong]] (TOML integers are 64-bit). */
  def getInt(path: String): Option[Long] = getLong(path)

  def getDouble(path: String): Option[Double] =
    get(path).collect { case TomlValue.FloatVal(d) => d }

  def getBool(path: String): Option[Boolean] =
    get(path).collect { case TomlValue.Bool(b) => b }

  def getTable(path: String): Option[VectorMap[String, TomlValue]] =
    get(path).collect { case TomlValue.Obj(fields) => fields }

  def getArr(path: String): Option[List[TomlValue]] =
    get(path).collect { case TomlValue.Arr(elems) => elems }

  def getOffsetDateTime(path: String): Option[jt.OffsetDateTime] =
    get(path).collect { case TomlValue.OffsetDateTime(v) => v }

  def getLocalDateTime(path: String): Option[jt.LocalDateTime] =
    get(path).collect { case TomlValue.LocalDateTime(v) => v }

  def getLocalDate(path: String): Option[jt.LocalDate] =
    get(path).collect { case TomlValue.LocalDate(v) => v }

  def getLocalTime(path: String): Option[jt.LocalTime] =
    get(path).collect { case TomlValue.LocalTime(v) => v }

  private def navigate(cur: VectorMap[String, TomlValue], segs: List[String]): Option[TomlValue] =
    segs match
      case Nil => None
      case k :: Nil =>
        cur.get(k)
      case k :: rest =>
        cur.get(k) match
          case Some(TomlValue.Obj(fields)) => navigate(fields, rest)
          case _                           => None

sealed trait TomlStmt

object TomlStmt:
  /** Dotted key relative to current table (TOML dotted keys). */
  case class KeyValue(segments: List[String], value: TomlValue) extends TomlStmt
  case class TableHeader(path: List[String], arrayOfTables: Boolean) extends TomlStmt
