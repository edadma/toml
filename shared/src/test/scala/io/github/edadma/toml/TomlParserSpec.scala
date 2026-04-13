package io.github.edadma.toml

import java.time as jt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TomlParserSpec extends AnyFlatSpec with Matchers:

  def ok(s: String): TomlDocument =
    TomlParser.parse(s) match
      case Right(doc) => doc
      case Left(msg)  => fail(msg)

  "TomlParser" should "parse root key-values" in:
    val doc = ok("a = 1\nb = \"hi\"\nflag = true\n")
    doc.root("a") shouldBe TomlValue.Integer(1)
    doc.root("b") shouldBe TomlValue.Str("hi")
    doc.root("flag") shouldBe TomlValue.Bool(true)

  it should "parse floats and negative integers" in:
    val doc = ok("x = -3\ny = 2.5\nz = 1e3\n")
    doc.root("x") shouldBe TomlValue.Integer(-3)
    doc.root("y") shouldBe TomlValue.FloatVal(2.5)
    doc.root("z") shouldBe TomlValue.FloatVal(1000.0)

  it should "parse basic strings with escapes and unicode" in:
    val doc = ok(raw"""s = "a\nb\t\"c\\d"""" + "\n" + "u = \"\\u0041\"\n")
    doc.root("s") shouldBe TomlValue.Str("a\nb\t\"c\\d")
    doc.root("u") shouldBe TomlValue.Str("A")

  it should "parse dotted keys at root" in:
    val doc = ok("a.b = 1\n")
    doc.root("a") shouldBe TomlValue.Obj(Map("b" -> TomlValue.Integer(1)))

  it should "parse standard table headers" in:
    val doc = ok("[t]\nk = 2\n")
    doc.root("t") shouldBe TomlValue.Obj(Map("k" -> TomlValue.Integer(2)))

  it should "parse nested table headers" in:
    val doc = ok("[a.b]\nx = 1\n")
    doc.root("a") shouldBe TomlValue.Obj(
      Map("b" -> TomlValue.Obj(Map("x" -> TomlValue.Integer(1)))),
    )

  it should "parse arrays" in:
    val doc = ok("n = [1, 2, 3,]\n")
    doc.root("n") shouldBe TomlValue.Arr(
      List(TomlValue.Integer(1), TomlValue.Integer(2), TomlValue.Integer(3)),
    )

  it should "parse inline tables" in:
    val doc = ok("t = { a = 1, b.c = 2 }\n")
    doc.root("t") shouldBe TomlValue.Obj(
      Map(
        "a" -> TomlValue.Integer(1),
        "b" -> TomlValue.Obj(Map("c" -> TomlValue.Integer(2))),
      ),
    )

  it should "parse array of tables" in:
    val doc = ok("[[items]]\nid = 1\n[[items]]\nid = 2\n")
    doc.root("items") shouldBe TomlValue.Arr(
      List(
        TomlValue.Obj(Map("id" -> TomlValue.Integer(1))),
        TomlValue.Obj(Map("id" -> TomlValue.Integer(2))),
      ),
    )

  it should "ignore comments and blank lines" in:
    val doc = ok("# top\na = 1 # end\n\nb=2\n")
    doc.root("a") shouldBe TomlValue.Integer(1)
    doc.root("b") shouldBe TomlValue.Integer(2)

  it should "parse hex, octal, and binary integers" in:
    val doc = ok("h = 0xDEAD_BEEF\no = 0o755\nb = 0b1010\n")
    doc.root("h") shouldBe TomlValue.Integer(0xdeadbeefL)
    doc.root("o") shouldBe TomlValue.Integer(493L)
    doc.root("b") shouldBe TomlValue.Integer(10L)

  it should "reject leading zeros in decimal integers" in:
    TomlParser.parse("a = 0123\n").isLeft shouldBe true

  it should "parse quoted keys" in:
    val doc = ok("\"127.0.0.1\" = 1\n'x-y' = 2\n")
    doc.root("127.0.0.1") shouldBe TomlValue.Integer(1)
    doc.root("x-y") shouldBe TomlValue.Integer(2)

  it should "parse inf and nan" in:
    val doc = ok("a = inf\nb = -inf\nc = nan\n")
    doc.root("a") shouldBe TomlValue.FloatVal(Double.PositiveInfinity)
    doc.root("b") shouldBe TomlValue.FloatVal(Double.NegativeInfinity)
    (doc.root("c") match
      case TomlValue.FloatVal(d) => d.isNaN shouldBe true
      case _                       => fail(),
    )

  it should "parse +nan" in:
    val doc = ok("x = +nan\n")
    doc.root("x").asInstanceOf[TomlValue.FloatVal].d.isNaN shouldBe true

  it should "parse nan key followed by nan_plus = +nan" in:
    ok("nan = nan\nnan_plus = +nan\n")

  it should "parse offset date-time and local date" in:
    val doc = ok("odt = 1979-05-27T07:32:00Z\nld = 1979-05-27\n")
    doc.root("odt") shouldBe TomlValue.OffsetDateTime(
      jt.OffsetDateTime.parse("1979-05-27T07:32:00Z"),
    )
    doc.root("ld") shouldBe TomlValue.LocalDate(jt.LocalDate.parse("1979-05-27"))

  it should "attach subtables to the current array-of-tables element" in:
    val doc = ok("[[fruits]]\nname = \"apple\"\n[fruits.physical]\ncolor = \"red\"\n")
    val arr = doc.root("fruits").asInstanceOf[TomlValue.Arr]
    arr.elems should have length 1
    arr.elems.head shouldBe TomlValue.Obj(
      Map(
        "name" -> TomlValue.Str("apple"),
        "physical" -> TomlValue.Obj(Map("color" -> TomlValue.Str("red"))),
      ),
    )

  it should "parse multiline basic strings" in:
    val doc = ok("s = \"\"\"hello\nworld\"\"\"\n")
    doc.root("s") shouldBe TomlValue.Str("hello\nworld")

  it should "trim line-ending backslashes in multiline basic strings (v0.5+)" in:
    val expected = "The quick brown fox jumps over the lazy dog."
    val doc = ok(
      "s = \"\"\"\n" +
        "The quick brown \\\n" +
        "\n\n\n" +
        "  fox jumps over \\\n" +
        "    the lazy dog.\"\"\"\n",
    )
    doc.root("s") shouldBe TomlValue.Str(expected)

  it should "trim opening line-ending backslashes in multiline basic strings" in:
    val expected = "The quick brown fox jumps over the lazy dog."
    val doc = ok(
      "s = \"\"\"\\\n" +
        "       The quick brown \\\n" +
        "       fox jumps over \\\n" +
        "       the lazy dog.\\\n" +
        "       \"\"\"\n",
    )
    doc.root("s") shouldBe TomlValue.Str(expected)

  it should "allow unescaped double quotes inside multiline basic strings" in:
    val doc = ok("s = \"\"\"say \"hello\" now\"\"\"\n")
    doc.root("s") shouldBe TomlValue.Str("say \"hello\" now")

  it should "reject reserved basic-string escapes \\e and \\x (TOML 1.0.0)" in:
    TomlParser.parse("a = \"\\e\"\n").isLeft shouldBe true
    TomlParser.parse("a = \"\\x00\"\n").isLeft shouldBe true

  it should "accept mixed-type arrays (TOML 1.0.0)" in:
    val doc = ok("a = [ 1, 2.0 ]\n")
    doc.root("a").asInstanceOf[TomlValue.Arr].elems should have length 2

  it should "accept homogeneous arrays mixing string forms (TOML 1.0.0)" in:
    val doc = ok("a = [ \"x\", 'y' ]\n")
    doc.root("a").asInstanceOf[TomlValue.Arr].elems should have length 2

end TomlParserSpec
