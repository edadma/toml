package io.github.edadma.toml

import scala.collection.mutable

/** Mutable tree while building; converted to immutable [[TomlDocument]]. */
private sealed trait TNode

private object TNode:
  case class Leaf(v: TomlValue) extends TNode
  case class Table(m: mutable.Map[String, TNode]) extends TNode
  case class TableArray(elems: mutable.ArrayBuffer[mutable.Map[String, TNode]]) extends TNode

/** Builds [[TomlDocument]] from statements; enforces TOML 1.0.0 table / AoT conflicts where implemented. */
object TomlBuilder:

  /** Merge inline-table dotted pairs into a nested [[TomlValue.Obj]]. */
  def mergeInlinePairs(pairs: List[(List[String], TomlValue)]): Either[String, Map[String, TomlValue]] =
    val m = mutable.Map.empty[String, TNode]
    val err = mutable.ArrayBuffer.empty[String]
    val inlineBan = mutable.Set.empty[List[String]]
    pairs.foreach { case (segs, v) =>
      putDottedWithErr(m, segs, TNode.Leaf(v), err, Nil, inlineBan)
    }
    if err.nonEmpty then Left(err.mkString("; "))
    else Right(freezeMap(m))

  def build(stmts: List[TomlStmt]): Either[String, TomlDocument] =
    val root = mutable.Map.empty[String, TNode]
    var focus = root
    val err = mutable.ArrayBuffer.empty[String]
    /** Paths already opened with a standard `[table]` header (TOML 1.0.0 duplicate / redefine rules). */
    val explicitStd = mutable.Set.empty[List[String]]
    /** Tables that hold a value from a multi-segment dotted key; cannot be reopened with `[path]` (TOML 1.0.0). */
    val dottedBan = mutable.Set.empty[List[String]]
    var focusAbsPath: List[String] = Nil

    /** Walk `path` from `m`; [[TNode.TableArray]] uses the last element’s map (TOML array-of-tables rules). */
    def navigateTablePath(m: mutable.Map[String, TNode], path: List[String]): mutable.Map[String, TNode] =
      path match
        case Nil => m
        case h :: t =>
          m.get(h) match
            case Some(TNode.TableArray(buf)) =>
              if buf.isEmpty then
                err += s"array '$h' is empty; cannot resolve path ${(h :: t).mkString(".")}"
                m
              else navigateTablePath(buf.last, t)
            case Some(TNode.Table(inner)) =>
              navigateTablePath(inner, t)
            case None =>
              val inner = mutable.Map.empty[String, TNode]
              m(h) = TNode.Table(inner)
              navigateTablePath(inner, t)
            case Some(TNode.Leaf(_)) =>
              err += s"key '$h' is not a table (path ${(h :: t).mkString(".")})"
              m

    /** True if some strictly longer path was already opened with `[...]` (allows `[x]` after `[x.y.z]`). */
    def hasStrictExplicitExtension(p: List[String]): Boolean =
      explicitStd.exists(q => q.length > p.length && q.take(p.length) == p)

    /** Open `[path]` per TOML 1.0.0: no duplicate header, no `[t]` on AoT, no redefine of dotted-key tables. */
    def openStandardTable(path: List[String]): Unit =
      if path.isEmpty then focus = root
      else if explicitStd.contains(path) then
        err += s"duplicate table header [${path.mkString(".")}]"
      else
        walkOpenStandard(root, path, path) match
          case None        => ()
          case Some(inner) =>
            explicitStd += path
            focus = inner
            focusAbsPath = path

    def walkOpenStandard(
        cur: mutable.Map[String, TNode],
        segs: List[String],
        fullPath: List[String],
    ): Option[mutable.Map[String, TNode]] =
      segs match
        case Nil => Some(cur)
        case seg :: rest =>
          val isLast = rest.isEmpty
          cur.get(seg) match
            case None =>
              val inner = mutable.Map.empty[String, TNode]
              cur(seg) = TNode.Table(inner)
              if isLast then Some(inner) else walkOpenStandard(inner, rest, fullPath)
            case Some(TNode.Leaf(_)) =>
              err += s"table header [${fullPath.mkString(".")}] conflicts with existing key at '$seg'"
              None
            case Some(TNode.TableArray(buf)) =>
              if buf.isEmpty then
                err += s"array '$seg' is empty in path ${fullPath.mkString(".")}"
                None
              else if isLast then
                err +=
                  s"table header [${fullPath.mkString(".")}] conflicts with array of tables '$seg'"
                None
              else
                walkOpenStandard(buf.last, rest, fullPath)
            case Some(TNode.Table(inner)) =>
              if isLast then
                if hasStrictExplicitExtension(fullPath) then Some(inner)
                else if dottedBan.contains(fullPath) then
                  err += s"table header [${fullPath.mkString(".")}] redefines a table created from dotted keys"
                  None
                else Some(inner)
              else
                walkOpenStandard(inner, rest, fullPath)

    def ensureTableArray(path: List[String]): Option[mutable.Map[String, TNode]] =
      if path.isEmpty then
        err += "array of tables header must not be empty"
        None
      else
        val parentPath = path.init
        val name = path.last
        val errBefore = err.length
        val parent = navigateTablePath(root, parentPath)
        if err.length != errBefore then None
        else
          parent.get(name) match
            case None =>
              val row = mutable.Map.empty[String, TNode]
              val buf = mutable.ArrayBuffer(row)
              parent(name) = TNode.TableArray(buf)
              Some(row)
            case Some(TNode.TableArray(buf)) =>
              val row = mutable.Map.empty[String, TNode]
              buf += row
              Some(row)
            case Some(_) =>
              err += s"[[${path.mkString(".")}]] conflicts with existing key"
              None

    for s <- stmts do
      s match
        case TomlStmt.TableHeader(path, false) =>
          openStandardTable(path)
        case TomlStmt.TableHeader(path, true) =>
          ensureTableArray(path) match
            case Some(row) =>
              focus = row
              focusAbsPath = path
            case None => ()
        case TomlStmt.KeyValue(segments, value) =>
          putDottedWithErr(focus, segments, TNode.Leaf(value), err, focusAbsPath, dottedBan)

    if err.nonEmpty then Left(err.mkString("; "))
    else Right(TomlDocument(freezeMap(root)))

  /** @param pathToTarget path from document root to `target` (the map receiving the next segment). */
  private def putDottedWithErr(
      target: mutable.Map[String, TNode],
      segments: List[String],
      leaf: TNode,
      err: mutable.ArrayBuffer[String],
      pathToTarget: List[String],
      dottedBan: mutable.Set[List[String]],
  ): Unit =
    segments match
      case Nil => ()
      case k :: Nil =>
        if pathToTarget.length >= 2 then dottedBan += pathToTarget
        target.get(k) match
          case Some(_) =>
            err += s"duplicate or conflicting key: $k"
          case None =>
            target(k) = leaf
      case k :: rest =>
        target.get(k) match
          case Some(TNode.Table(child)) =>
            putDottedWithErr(child, rest, leaf, err, pathToTarget :+ k, dottedBan)
          case None =>
            val child = mutable.Map.empty[String, TNode]
            target(k) = TNode.Table(child)
            putDottedWithErr(child, rest, leaf, err, pathToTarget :+ k, dottedBan)
          case Some(TNode.TableArray(_)) =>
            err += s"cannot extend dotted key under '$k': array, not a table"
          case Some(TNode.Leaf(_)) =>
            err += s"cannot extend dotted key under '$k': not a table"

  private def freezeMap(m: mutable.Map[String, TNode]): Map[String, TomlValue] =
    m.view.mapValues(freezeNode).toMap

  private def freezeNode(n: TNode): TomlValue =
    n match
      case TNode.Leaf(v) => v
      case TNode.Table(m) => TomlValue.Obj(freezeMap(m))
      case TNode.TableArray(buf) =>
        TomlValue.Arr(buf.map(tm => TomlValue.Obj(freezeMap(tm))).toList)

end TomlBuilder
