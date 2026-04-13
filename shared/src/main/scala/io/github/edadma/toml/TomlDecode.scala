package io.github.edadma.toml

import java.time as jt
import java.time.format as jtf

/** Map lexer text to [[TomlValue]] (numbers, floats, datetimes) and TOML 1.0.0 value rules. */
private[edadma] object TomlDecode:

  /** Each `_` sits between decimal digits; no leading/trailing/double `_`. */
  private def underscoresOkDecimalRun(run: String): Boolean =
    if run.contains("__") || run.startsWith("_") || run.endsWith("_") then false
    else run.split('_').forall(s => s.nonEmpty && s.forall(_.isDigit))

  private def underscoresOkRadixRun(run: String, digit: Char => Boolean): Boolean =
    if run.contains("__") || run.startsWith("_") || run.endsWith("_") then false
    else run.split('_').forall(s => s.nonEmpty && s.forall(digit))

  private def underscoresOkSignedDecimalRun(run: String): Boolean =
    if run.startsWith("+") || run.startsWith("-") then underscoresOkDecimalRun(run.drop(1))
    else underscoresOkDecimalRun(run)

  private def isHexDigit(c: Char): Boolean =
    c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  private def isOctDigit(c: Char): Boolean = c >= '0' && c <= '7'

  private def isBinDigit(c: Char): Boolean = c == '0' || c == '1'

  def numericOrFloat(text: String): Either[String, TomlValue] =
    val s = text.trim
    s match
      case "inf" | "+inf"  => Right(TomlValue.FloatVal(Double.PositiveInfinity))
      case "-inf"          => Right(TomlValue.FloatVal(Double.NegativeInfinity))
      case "nan" | "+nan"  => Right(TomlValue.FloatVal(Double.NaN))
      case "-nan"          => Right(TomlValue.FloatVal(java.lang.Double.longBitsToDouble(0xfff8000000000000L)))
      case _ if s.startsWith("0x") =>
        if !underscoresOkRadixRun(s.drop(2), isHexDigit) then Left(s"invalid underscore placement in integer: $s")
        else radixInt(s.drop(2), 16)
      case _ if s.startsWith("0o") =>
        if !underscoresOkRadixRun(s.drop(2), isOctDigit) then Left(s"invalid underscore placement in integer: $s")
        else radixInt(s.drop(2), 8)
      case _ if s.startsWith("0b") =>
        if !underscoresOkRadixRun(s.drop(2), isBinDigit) then Left(s"invalid underscore placement in integer: $s")
        else radixInt(s.drop(2), 2)
      case _ if s.contains('.') || s.contains('e') || s.contains('E') =>
        decodeFloat(s)
      case _ =>
        if !underscoresOkSignedDecimalRun(s) then Left(s"invalid underscore placement in integer: $s")
        else decimalInt(s)

  private def decodeFloat(s: String): Either[String, TomlValue] =
    val eIdx = s.indexWhere(c => c == 'e' || c == 'E')
    val (mantissa, expWithSign) =
      if eIdx < 0 then (s, None)
      else (s.take(eIdx), Some(s.drop(eIdx + 1)))
    expWithSign match
      case Some(exp) if !underscoresOkSignedDecimalRun(exp) =>
        Left(s"invalid underscore placement in float: $s")
      case _ =>
        val dotIdx = mantissa.indexOf('.')
        val (intRaw, fracRaw) =
          if dotIdx < 0 then (mantissa, "")
          else (mantissa.take(dotIdx), mantissa.drop(dotIdx + 1))
        if intRaw.isEmpty then Left(s"invalid float: $s")
        else if dotIdx >= 0 && fracRaw.replace("_", "").isEmpty then Left(s"invalid float: $s")
        else if !underscoresOkSignedDecimalRun(intRaw) then Left(s"invalid underscore placement in float: $s")
        else if fracRaw.nonEmpty && !underscoresOkDecimalRun(fracRaw) then
          Left(s"invalid underscore placement in float: $s")
        else
          val intNoUs = intRaw.replace("_", "")
          val intBody =
            if intNoUs.startsWith("+") then intNoUs.drop(1)
            else intNoUs
          val intClean = if intBody.startsWith("-") then intBody.drop(1) else intBody
          if intClean.length > 1 && intClean.charAt(0) == '0' then
            Left(s"leading zero in float integer part: $s")
          else
            try Right(TomlValue.FloatVal(s.replace("_", "").toDouble))
            catch case _: NumberFormatException => Left(s"invalid float: $s")

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
        if i > 0 && s.indexOf('T') < 0 && s.indexOf('t') < 0 && s.contains('-') then s.patch(i, "T", 1)
        else s

      val n = normSpace(t).replace('t', 'T').replace('z', 'Z')

      scala.util.Try(jt.OffsetDateTime.parse(n, isoOffset)).toOption
        .map(TomlValue.OffsetDateTime.apply)
        .orElse(scala.util.Try(jt.LocalDateTime.parse(n, isoLocalDateTime)).toOption.map(TomlValue.LocalDateTime.apply))
        .orElse(scala.util.Try(jt.LocalDate.parse(n, isoDate)).toOption.map(TomlValue.LocalDate.apply))
        .orElse(scala.util.Try(jt.LocalTime.parse(n, isoTime)).toOption.map(TomlValue.LocalTime.apply))
        .toRight(s"cannot parse datetime or time: $t")

end TomlDecode
