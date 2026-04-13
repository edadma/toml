package io.github.edadma.toml

import java.time as jt
import java.time.format as jtf

/** Map lexer text to [[TomlValue]] (numbers, floats, datetimes). */
private[edadma] object TomlDecode:

  def numericOrFloat(text: String): Either[String, TomlValue] =
    val s = text.trim
    s match
      case "inf" | "+inf"  => Right(TomlValue.FloatVal(Double.PositiveInfinity))
      case "-inf"          => Right(TomlValue.FloatVal(Double.NegativeInfinity))
      case "nan" | "+nan"  => Right(TomlValue.FloatVal(Double.NaN))
      case "-nan"          => Right(TomlValue.FloatVal(java.lang.Double.longBitsToDouble(0xfff8000000000000L)))
      case _ if s.startsWith("0x") || s.startsWith("0X") =>
        radixInt(s.drop(2), 16)
      case _ if s.startsWith("0o") || s.startsWith("0O") =>
        radixInt(s.drop(2), 8)
      case _ if s.startsWith("0b") || s.startsWith("0B") =>
        radixInt(s.drop(2), 2)
      case _ if s.contains('.') || s.contains('e') || s.contains('E') =>
        try Right(TomlValue.FloatVal(s.replace("_", "").toDouble))
        catch case _: NumberFormatException => Left(s"invalid float: $s")
      case _ =>
        decimalInt(s)

  private def radixInt(body: String, radix: Int): Either[String, TomlValue] =
    val digits = body.replace("_", "")
    if digits.isEmpty then Left("empty radix integer")
    else
      try Right(TomlValue.Integer(java.lang.Long.parseUnsignedLong(digits, radix)))
      catch case _: NumberFormatException => Left(s"invalid integer: $body (radix $radix)")

  private def decimalInt(s: String): Either[String, TomlValue] =
    val clean = s.replace("_", "")
    val signed =
      if clean.startsWith("+") then clean.drop(1)
      else clean
    val (negative, unsigned) =
      if signed.startsWith("-") then (true, signed.drop(1))
      else (false, signed)

    if unsigned.isEmpty then Left(s"invalid integer: $s")
    else if unsigned.length > 1 && unsigned.charAt(0) == '0' then Left(s"leading zero in decimal integer: $s")
    else
      try
        val lit = (if negative then "-" else "") + unsigned
        Right(TomlValue.Integer(java.lang.Long.parseLong(lit)))
      catch case _: NumberFormatException => Left(s"invalid or out-of-range integer: $s")

  private val isoDate = jtf.DateTimeFormatter.ISO_LOCAL_DATE
  private val isoTime = jtf.DateTimeFormatter.ISO_LOCAL_TIME
  private val isoLocalDateTime = jtf.DateTimeFormatter.ISO_LOCAL_DATE_TIME
  private val isoOffset = jtf.DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def temporal(raw: String): Either[String, TomlValue] =
    val t = raw.trim
    if t.isEmpty then Left("empty datetime")
    else
      def normSpace(s: String): String =
        val i = s.indexOf(' ')
        if i > 0 && s.indexOf('T') < 0 && s.contains('-') then s.patch(i, "T", 1) else s

      scala.util.Try(jt.OffsetDateTime.parse(normSpace(t), isoOffset)).toOption
        .map(TomlValue.OffsetDateTime.apply)
        .orElse(scala.util.Try(jt.LocalDateTime.parse(t, isoLocalDateTime)).toOption.map(TomlValue.LocalDateTime.apply))
        .orElse(scala.util.Try(jt.LocalDate.parse(t, isoDate)).toOption.map(TomlValue.LocalDate.apply))
        .orElse(scala.util.Try(jt.LocalTime.parse(t, isoTime)).toOption.map(TomlValue.LocalTime.apply))
        .toRight(s"cannot parse datetime or time: $t")

end TomlDecode
