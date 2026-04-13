package io.github.edadma.toml

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** TOML 1.0.0 table / array-of-tables conflict and duplicate-header rules. */
class TomlV100Spec extends AnyFlatSpec with Matchers:

  def ok(s: String): TomlDocument =
    TomlParser.parse(s) match
      case Right(doc) => doc
      case Left(msg)  => fail(msg)

  def parseError(s: String): Unit =
    TomlParser.parse(s) should matchPattern { case Left(_) => }

  it should "reject duplicate standard table headers" in:
    parseError(
      """[fruit]
        |apple = "red"
        |[fruit]
        |orange = "orange"
        |""".stripMargin,
    )

  it should "reject [fruit.apple] when apple is a key under [fruit]" in:
    parseError(
      """[fruit]
        |apple = "red"
        |[fruit.apple]
        |texture = "smooth"
        |""".stripMargin,
    )

  it should "reject [fruit.apple] after dotted keys under [fruit]" in:
    parseError(
      """[fruit]
        |apple.color = "red"
        |[fruit.apple]
        |texture = "smooth"
        |""".stripMargin,
    )

  it should "allow [fruit.apple.texture] after dotted keys under [fruit] (new sub-table)" in:
    val doc = ok(
      """[fruit]
        |apple.color = "red"
        |[fruit.apple.texture]
        |smooth = true
        |""".stripMargin,
    )
    val fruit = doc.root("fruit").asInstanceOf[TomlValue.Obj]
    val apple = fruit.fields("apple").asInstanceOf[TomlValue.Obj]
    apple.fields("color") shouldBe TomlValue.Str("red")
    val texture = apple.fields("texture").asInstanceOf[TomlValue.Obj]
    texture.fields("smooth") shouldBe TomlValue.Bool(true)

  it should "allow [x] after [x.y.z.w] (super-table, TOML 1.0.0)" in:
    val doc = ok(
      """[x.y.z.w]
        |k = 1
        |[x]
        |a = 2
        |""".stripMargin,
    )
    doc.root("x").asInstanceOf[TomlValue.Obj].fields("a") shouldBe TomlValue.Integer(2)

  it should "reject [[fruit]] when fruit was a normal table from dotted keys" in:
    parseError(
      """[fruit.physical]
        |color = "red"
        |[[fruit]]
        |name = "apple"
        |""".stripMargin,
    )

  it should "reject [fruits] when fruits is an array of tables" in:
    parseError(
      """[[fruits]]
        |name = "a"
        |[fruits]
        |name = "b"
        |""".stripMargin,
    )

  it should "still allow [fruits.physical] under [[fruits]]" in:
    val doc = ok(
      """[[fruits]]
        |name = "apple"
        |[fruits.physical]
        |color = "red"
        |""".stripMargin,
    )
    val arr = doc.root("fruits").asInstanceOf[TomlValue.Arr]
    arr.elems.head.asInstanceOf[TomlValue.Obj].fields("physical")
      .asInstanceOf[TomlValue.Obj].fields("color") shouldBe TomlValue.Str("red")

  it should "reject static empty array then [[array]]" in:
    parseError(
      """fruits = []
        |[[fruits]]
        |name = "x"
        |""".stripMargin,
    )

  it should "reject [fruit.apple] when only defined earlier by root dotted keys" in:
    parseError(
      """fruit.apple.color = "red"
        |[fruit.apple]
        |x = 1
        |""".stripMargin,
    )

  it should "allow [a] after a.b = 1 at root (dotted key then super-table header)" in:
    val doc = ok(
      """a.b = 1
        |[a]
        |c = 2
        |""".stripMargin,
    )
    val a = doc.root("a").asInstanceOf[TomlValue.Obj]
    a.fields("b") shouldBe TomlValue.Integer(1)
    a.fields("c") shouldBe TomlValue.Integer(2)

end TomlV100Spec
