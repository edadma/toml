package io.github.edadma.toml

import scala.collection.mutable

/** Mutable tree while building; converted to immutable [[TomlDocument]]. */
private sealed trait TNode

private object TNode:
  case class Leaf(v: TomlValue) extends TNode
  case class Table(m: mutable.Map[String, TNode]) extends TNode
  case class TableArray(elems: mutable.ArrayBuffer[mutable.Map[String, TNode]]) extends TNode

object TomlBuilder:

  /** Merge inline-table dotted pairs into a nested [[TomlValue.Obj]]. */
  def mergeInlinePairs(pairs: List[(List[String], TomlValue)]): Either[String, Map[String, TomlValue]] =
    val m = mutable.Map.empty[String, TNode]
    val err = mutable.ArrayBuffer.empty[String]
    pairs.foreach { case (segs, v) => putDottedWithErr(m, segs, TNode.Leaf(v), err) }
    if err.nonEmpty then Left(err.mkString("; "))
    else Right(freezeMap(m))

  def build(stmts: List[TomlStmt]): Either[String, TomlDocument] =
    val root = mutable.Map.empty[String, TNode]
    var focus = root
    val err = mutable.ArrayBuffer.empty[String]

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

    def ensureTable(path: List[String]): mutable.Map[String, TNode] =
      if path.isEmpty then root
      else navigateTablePath(root, path)

    def ensureTableArray(path: List[String]): mutable.Map[String, TNode] =
      if path.isEmpty then
        err += "array of tables header must not be empty"
        focus
      else
        val parentPath = path.init
        val name = path.last
        val parent = navigateTablePath(root, parentPath)
        if err.nonEmpty then focus
        else
          parent.get(name) match
            case None =>
              val row = mutable.Map.empty[String, TNode]
              val buf = mutable.ArrayBuffer(row)
              parent(name) = TNode.TableArray(buf)
              row
            case Some(TNode.TableArray(buf)) =>
              val row = mutable.Map.empty[String, TNode]
              buf += row
              row
            case Some(_) =>
              err += s"[[${path.mkString(".")}]] conflicts with existing key"
              focus

    for s <- stmts do
      s match
        case TomlStmt.TableHeader(path, false) =>
          focus = ensureTable(path)
        case TomlStmt.TableHeader(path, true) =>
          focus = ensureTableArray(path)
        case TomlStmt.KeyValue(segments, value) =>
          putDottedWithErr(focus, segments, TNode.Leaf(value), err)

    if err.nonEmpty then Left(err.mkString("; "))
    else Right(TomlDocument(freezeMap(root)))

  private def putDottedWithErr(
      target: mutable.Map[String, TNode],
      segments: List[String],
      leaf: TNode,
      err: mutable.ArrayBuffer[String],
  ): Unit =
    segments match
      case Nil => ()
      case k :: Nil =>
        target.get(k) match
          case Some(_) =>
            err += s"duplicate or conflicting key: $k"
          case None =>
            target(k) = leaf
      case k :: rest =>
        target.get(k) match
          case Some(TNode.Table(child)) =>
            putDottedWithErr(child, rest, leaf, err)
          case None =>
            val child = mutable.Map.empty[String, TNode]
            target(k) = TNode.Table(child)
            putDottedWithErr(child, rest, leaf, err)
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
