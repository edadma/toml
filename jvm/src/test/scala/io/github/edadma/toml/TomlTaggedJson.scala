package io.github.edadma.toml

import java.time.format.DateTimeFormatter

import ujson.{Arr, Obj, Str, Value}

/** Tagged JSON tree expected by [toml-test](https://github.com/toml-lang/toml-test) decoders. */
private[toml] object TomlTaggedJson:

  private val fmtOdt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  private val fmtLdt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
  private val fmtLd = DateTimeFormatter.ISO_LOCAL_DATE
  private val fmtLt = DateTimeFormatter.ISO_LOCAL_TIME

  def encodeDocument(doc: TomlDocument): Value =
    encodeTable(doc.root)

  private def encodeTable(m: Map[String, TomlValue]): Value =
    Obj.from(m.toSeq.sortBy(_._1).map { case (k, v) => k -> encodeValue(v) })

  private def encodeValue(v: TomlValue): Value = v match
    case TomlValue.Obj(fields)     => encodeTable(fields)
    case TomlValue.Arr(elems)      => Arr.from(elems.map(encodeValue))
    case TomlValue.Str(s)          => tagged("string", s)
    case TomlValue.Num(n)          => tagged("integer", n.toString)
    case TomlValue.FloatVal(d)     => tagged("float", formatFloat(d))
    case TomlValue.Bool(b)         => tagged("bool", if b then "true" else "false")
    case TomlValue.OffsetDateTime(t) =>
      tagged("datetime", t.format(fmtOdt))
    case TomlValue.LocalDateTime(t) =>
      tagged("datetime-local", t.format(fmtLdt))
    case TomlValue.LocalDate(t) =>
      tagged("date-local", t.format(fmtLd))
    case TomlValue.LocalTime(t) =>
      tagged("time-local", t.format(fmtLt))

  private def tagged(typ: String, value: String): Value =
    Obj("type" -> Str(typ), "value" -> Str(value))

  private def formatFloat(d: Double): String =
    if java.lang.Double.isNaN(d) then "nan"
    else if d == Double.PositiveInfinity then "inf"
    else if d == Double.NegativeInfinity then "-inf"
    else d.toString

end TomlTaggedJson
