package io.github.edadma.toml

import java.time.*
import java.time.format.DateTimeFormatter
import ujson.{Arr, Obj, Str, Value}

/** Semantic equality matching [toml-test/json.go](https://github.com/toml-lang/toml-test/blob/main/json.go). */
private[toml] object TomlJsonSemanticEq:

  private def datetimeRepl(s: String): String =
    s.replace(" ", "T").replace("t", "T").replace("z", "Z")

  private val fmtOdt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  private val fmtLdt = DateTimeFormatter.ISO_LOCAL_DATE_TIME
  private val fmtLd = DateTimeFormatter.ISO_LOCAL_DATE
  private val fmtLt = DateTimeFormatter.ISO_LOCAL_TIME

  /** @return None if equal, else human-readable mismatch */
  def cmp(want: Value, have: Value, keyPath: String = ""): Option[String] =
    (want, have) match
      case (wo: Obj, ho: Obj) =>
        if isTagged(wo) && isTagged(ho) then cmpTagged(wo, ho, keyPath)
        else if isTagged(wo) != isTagged(ho) then
          Some(s"key $keyPath: value/table mismatch (tagged vs nested)")
        else cmpObjects(wo, ho, keyPath)
      case (wa: Arr, ha: Arr) =>
        if wa.arr.length != ha.arr.length then
          Some(s"key $keyPath: array length ${wa.arr.length} vs ${ha.arr.length}")
        else
          wa.arr.zip(ha.arr).zipWithIndex.collectFirst {
            case ((w, h), i) if cmp(w, h, s"$keyPath[$i]").isDefined =>
              cmp(w, h, s"$keyPath[$i]").get
          }
      case _ =>
        Some(s"key $keyPath: expected ${want.getClass.getSimpleName}, got ${have.getClass.getSimpleName}")

  private def isTagged(o: Obj): Boolean =
    val m = o.obj
    m.size == 2 && m.contains("type") && m.contains("value")

  private def cmpObjects(want: Obj, have: Obj, keyPath: String): Option[String] =
    val wk = want.obj.keys.toSet
    val hk = have.obj.keys.toSet
    wk.diff(hk).headOption.map(k => s"key ${join(keyPath, k)}: missing in parser output")
      .orElse:
        hk.diff(wk).headOption.map(k => s"key ${join(keyPath, k)}: unexpected in parser output")
      .orElse:
        wk.toSeq.sorted.iterator
          .map(k => cmp(want.obj(k), have.obj(k), join(keyPath, k)))
          .collectFirst { case Some(m) => m }

  private def join(prefix: String, k: String): String =
    if prefix.isEmpty then k else s"$prefix.$k"

  private def cmpTagged(want: Obj, have: Obj, keyPath: String): Option[String] =
    val wt = want.obj("type").str
    val ht = have.obj("type").str
    if wt != ht then Some(s"key $keyPath: type $wt vs $ht")
    else
      val wv = want.obj("value")
      val hv = have.obj("value")
      (wv, hv) match
        case (Str(ws), Str(hs)) =>
          wt match
            case "float" =>
              if !cmpFloats(ws, hs) then Some(s"key $keyPath: float $ws vs $hs") else None
            case "datetime" | "datetime-local" | "date-local" | "time-local" =>
              cmpDateTimes(wt, ws, hs, keyPath)
            case "bool" =>
              if ws.toLowerCase != hs.toLowerCase then Some(s"key $keyPath: bool $ws vs $hs") else None
            case _ =>
              if ws != hs then Some(s"key $keyPath: value\n  expected: $ws\n  got:      $hs") else None
        case _ =>
          Some(s"key $keyPath: tagged value fields must be strings")

  private def cmpFloats(want: String, have: String): Boolean =
    val wl = want.toLowerCase.trim
    val hl = have.toLowerCase.trim
    if wl.endsWith("nan") || hl.endsWith("nan") then
      val w = wl.dropWhile(c => c == '+' || c == '-')
      val h = hl.dropWhile(c => c == '+' || c == '-')
      w == h
    else if wl == "inf" || wl == "+inf" then hl == "inf" || hl == "+inf"
    else if wl == "-inf" then hl == "-inf"
    else if hl == "inf" || hl == "+inf" then wl == "inf" || wl == "+inf"
    else if hl == "-inf" then wl == "-inf"
    else
      (scala.util.Try(java.lang.Double.parseDouble(want)).toOption,
        scala.util.Try(java.lang.Double.parseDouble(have)).toOption) match
        case (Some(a), Some(b)) => a == b
        case _                  => false

  private def cmpDateTimes(kind: String, want: String, have: String, keyPath: String): Option[String] =
    val wn = datetimeRepl(want)
    val hn = datetimeRepl(have)
    val (pw, ph) =
      try
        kind match
          case "datetime" =>
            (OffsetDateTime.parse(wn, fmtOdt), OffsetDateTime.parse(hn, fmtOdt))
          case "datetime-local" =>
            (LocalDateTime.parse(wn, fmtLdt), LocalDateTime.parse(hn, fmtLdt))
          case "date-local" =>
            (LocalDate.parse(wn, fmtLd), LocalDate.parse(hn, fmtLd))
          case "time-local" =>
            (LocalTime.parse(wn, fmtLt), LocalTime.parse(hn, fmtLt))
          case _ => throw IllegalArgumentException(kind)
      catch case _: Exception => return Some(s"key $keyPath: cannot parse datetimes $want / $have")

    val eq = (pw, ph) match
      case (a: OffsetDateTime, b: OffsetDateTime) => a.isEqual(b)
      case (a: LocalDateTime, b: LocalDateTime)   => a == b
      case (a: LocalDate, b: LocalDate)          => a == b
      case (a: LocalTime, b: LocalTime)          => a == b
      case _                                     => false

    if eq then None
    else Some(s"key $keyPath: datetime mismatch $want vs $have")

end TomlJsonSemanticEq
