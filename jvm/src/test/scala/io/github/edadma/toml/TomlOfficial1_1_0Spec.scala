package io.github.edadma.toml

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ujson.read as readJson

/** Runs the [toml-test](https://github.com/toml-lang/toml-test) corpus for TOML 1.1.0. */
class TomlOfficial1_1_0Spec extends AnyFlatSpec with Matchers:

  private def testsRoot: Path =
    Option(System.getenv("TOML_TEST_ROOT"))
      .map(Paths.get(_))
      .getOrElse(Paths.get("third_party/toml-test/tests").toAbsolutePath)

  private lazy val fileListPath: Path = testsRoot.resolve("files-toml-1.1.0")

  private def corpusAvailable: Boolean =
    Files.isDirectory(testsRoot) && Files.isRegularFile(fileListPath)

  private def readUtf8Strict(path: Path): String =
    val dec = StandardCharsets.UTF_8.newDecoder()
    dec.onMalformedInput(CodingErrorAction.REPORT)
    dec.onUnmappableCharacter(CodingErrorAction.REPORT)
    dec.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString

  "TOML 1.1.0 official corpus" should "be present (clone toml-lang/toml-test under third_party)" in {
    assume(corpusAvailable, s"missing $testsRoot or $fileListPath")
  }

  it should "reject every invalid decoder case" in {
    assume(corpusAvailable)
    val rels = Files.readAllLines(fileListPath, StandardCharsets.UTF_8).asScala
      .map(_.trim)
      .filter(_.nonEmpty)
      .filter(_.startsWith("invalid/"))
      .filter(_.endsWith(".toml"))
      .toSeq

    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    for rel <- rels do
      val p = testsRoot.resolve(rel)
      if !Files.isRegularFile(p) then failures += s"$rel: missing file"
      else
        scala.util.Try(readUtf8Strict(p)) match
          case scala.util.Failure(_) => ()
          case scala.util.Success(toml) =>
            TomlParser.parse(toml) match
              case Right(_) => failures += s"$rel: expected parse failure"
              case Left(_)  => ()

    failures shouldBe empty
  }

  it should "match tagged JSON for every valid case" in {
    assume(corpusAvailable)
    val rels = Files.readAllLines(fileListPath, StandardCharsets.UTF_8).asScala
      .map(_.trim)
      .filter(_.nonEmpty)
      .filter(_.startsWith("valid/"))
      .filter(_.endsWith(".toml"))
      .toSeq

    val failures = scala.collection.mutable.ArrayBuffer.empty[String]
    for rel <- rels do
      val tomlPath = testsRoot.resolve(rel)
      val jsonPath = testsRoot.resolve(rel.stripSuffix(".toml") + ".json")
      if !Files.isRegularFile(tomlPath) then failures += s"$rel: missing .toml"
      else if !Files.isRegularFile(jsonPath) then failures += s"$rel: missing .json"
      else
        val toml = readUtf8Strict(tomlPath)
        val wantJson = readJson(readUtf8Strict(jsonPath))
        TomlParser.parse(toml) match
          case Left(msg) => failures += s"$rel: parse error: $msg"
          case Right(doc) =>
            val haveJson = TomlTaggedJson.encodeDocument(doc)
            TomlJsonSemanticEq.cmp(wantJson, haveJson) match
              case None         => ()
              case Some(detail) => failures += s"$rel: $detail"

    failures shouldBe empty
  }

end TomlOfficial1_1_0Spec
