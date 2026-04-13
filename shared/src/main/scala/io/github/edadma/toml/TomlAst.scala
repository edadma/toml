package io.github.edadma.toml

import java.time as jt

sealed trait TomlValue

object TomlValue:
  case class Str(s: String) extends TomlValue
  case class Integer(n: Long) extends TomlValue
  case class FloatVal(d: Double) extends TomlValue
  case class Bool(b: Boolean) extends TomlValue
  case class Arr(elems: List[TomlValue]) extends TomlValue
  case class Obj(fields: Map[String, TomlValue]) extends TomlValue

  case class OffsetDateTime(v: jt.OffsetDateTime) extends TomlValue
  case class LocalDateTime(v: jt.LocalDateTime) extends TomlValue
  case class LocalDate(v: jt.LocalDate) extends TomlValue
  case class LocalTime(v: jt.LocalTime) extends TomlValue

/** Resolved document: root key-values and nested tables. */
case class TomlDocument(root: Map[String, TomlValue])

sealed trait TomlStmt

object TomlStmt:
  /** Dotted key relative to current table (TOML dotted keys). */
  case class KeyValue(segments: List[String], value: TomlValue) extends TomlStmt
  case class TableHeader(path: List[String], arrayOfTables: Boolean) extends TomlStmt
