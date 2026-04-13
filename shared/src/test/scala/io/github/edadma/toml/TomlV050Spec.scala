package io.github.edadma.toml

import java.time as jt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Hand-written coverage for documents shaped like the v0.5.0 spec examples (see
  * https://toml.io/en/v0.5.0 ). The implementation targets **TOML 1.0.0** (e.g. homogeneous arrays,
  * no `\\e` / `\\x` escapes); these tests assert what **this** parser accepts and the resulting values.
  */
class TomlV050Spec extends AnyFlatSpec with Matchers:

  def ok(s: String): TomlDocument =
    TomlParser.parse(s) match
      case Right(doc) => doc
      case Left(msg)  => fail(msg)

  def parseError(s: String): Unit =
    TomlParser.parse(s) should matchPattern { case Left(_) => }

  // --- Keys (v0.5.0 § Keys) ---

  it should "accept bare keys with letters, digits, underscore, hyphen" in:
    val doc = ok("key = 1\nbare_key = 2\nbare-key = 3\n")
    doc.root("key") shouldBe TomlValue.Integer(1)
    doc.root("bare_key") shouldBe TomlValue.Integer(2)
    doc.root("bare-key") shouldBe TomlValue.Integer(3)

  it should "accept quoted keys (basic and literal)" in:
    val doc = ok("\"127.0.0.1\" = 1\n'x-y' = 2\n\"ʎǝʞ\" = 3\n")
    doc.root("127.0.0.1") shouldBe TomlValue.Integer(1)
    doc.root("x-y") shouldBe TomlValue.Integer(2)
    doc.root("ʎǝʞ") shouldBe TomlValue.Integer(3)

  it should "accept a single empty basic-string key" in:
    val doc = ok("\"\" = 1\n")
    doc.root("") shouldBe TomlValue.Integer(1)

  it should "reject duplicate empty keys when basic and literal both name the empty string" in:
    parseError("\"\" = 1\n'' = 2\n")

  it should "reject duplicate root keys (v0.5.0 duplicate key rule)" in:
    parseError("a = 1\na = 2\n")

  it should "accept dotted keys at root" in:
    val doc = ok("physical.color = \"orange\"\nphysical.shape = \"round\"\n")
    val o = doc.root("physical").asInstanceOf[TomlValue.Obj]
    o.fields("color") shouldBe TomlValue.Str("orange")
    o.fields("shape") shouldBe TomlValue.Str("round")

  it should "accept dotted keys with quoted segments (v0.5.0)" in:
    val doc = ok("site.\"google.com\" = true\n")
    val site = doc.root("site").asInstanceOf[TomlValue.Obj]
    site.fields("google.com") shouldBe TomlValue.Bool(true)

  // --- Integers (v0.5.0 § Integer) ---

  it should "accept decimal integers with sign and underscores" in:
    val doc = ok("int1 = +99\nint2 = 42\nint3 = 0\nint4 = -17\nint5 = 1_000\n")
    doc.root("int1") shouldBe TomlValue.Integer(99)
    doc.root("int2") shouldBe TomlValue.Integer(42)
    doc.root("int3") shouldBe TomlValue.Integer(0)
    doc.root("int4") shouldBe TomlValue.Integer(-17)
    doc.root("int5") shouldBe TomlValue.Integer(1000)

  it should "reject leading zeros in decimal integers" in:
    parseError("a = 0123\n")

  it should "accept hexadecimal, octal, and binary integers (v0.5.0)" in:
    val doc = ok(
      "hex1 = 0xDEADBEEF\nhex2 = 0xdeadbeef\nhex3 = 0xdead_beef\n" +
        "oct1 = 0o01234567\noct2 = 0o755\n" +
        "bin1 = 0b11010110\n",
    )
    doc.root("hex1") shouldBe TomlValue.Integer(0xdeadbeefL)
    doc.root("hex3") shouldBe TomlValue.Integer(0xdeadbeefL)
    doc.root("oct2") shouldBe TomlValue.Integer(493L)
    doc.root("bin1") shouldBe TomlValue.Integer(214L)

  // --- Floats (v0.5.0 § Float) ---

  it should "accept fractional floats, exponents, and underscores" in:
    val doc = ok(
      "flt1 = +1.0\nflt2 = 3.1415\nflt3 = -0.01\n" +
        "flt4 = 5e+22\nflt5 = 1e6\nflt6 = -2E-2\n" +
        "flt7 = 6.626e-34\n" +
        "flt8 = 9_224_617.445_991_228_313\n",
    )
    doc.root("flt1") shouldBe TomlValue.FloatVal(1.0)
    doc.root("flt2") shouldBe TomlValue.FloatVal(3.1415)
    doc.root("flt5") shouldBe TomlValue.FloatVal(1e6)
    doc.root("flt8") shouldBe TomlValue.FloatVal(9224617.445991228)

  it should "accept inf and nan (v0.5.0)" in:
    val doc = ok("a = inf\nb = +inf\nc = -inf\nd = nan\n")
    doc.root("a") shouldBe TomlValue.FloatVal(Double.PositiveInfinity)
    doc.root("c") shouldBe TomlValue.FloatVal(Double.NegativeInfinity)
    (doc.root("d").asInstanceOf[TomlValue.FloatVal].d.isNaN) shouldBe true

  // --- Booleans ---

  it should "accept booleans" in:
    val doc = ok("t = true\nf = false\n")
    doc.root("t") shouldBe TomlValue.Bool(true)
    doc.root("f") shouldBe TomlValue.Bool(false)

  // --- Date-times (v0.5.0 § Offset / Local) ---

  it should "accept offset date-times with Z, numeric offset, space separator, and fraction" in:
    val doc = ok(
      "odt1 = 1979-05-27T07:32:00Z\n" +
        "odt2 = 1979-05-27T00:32:00-07:00\n" +
        "odt3 = 1979-05-27T00:32:00.999999-07:00\n" +
        "odt4 = 1979-05-27 07:32:00Z\n",
    )
    doc.root("odt1") shouldBe TomlValue.OffsetDateTime(jt.OffsetDateTime.parse("1979-05-27T07:32:00Z"))
    doc.root("odt4") shouldBe TomlValue.OffsetDateTime(jt.OffsetDateTime.parse("1979-05-27T07:32:00Z"))

  it should "accept local date-time" in:
    val doc = ok("ldt1 = 1979-05-27T07:32:00\nldt2 = 1979-05-27T00:32:00.999999\n")
    doc.root("ldt1") shouldBe TomlValue.LocalDateTime(jt.LocalDateTime.parse("1979-05-27T07:32:00"))
    doc.root("ldt2") shouldBe TomlValue.LocalDateTime(jt.LocalDateTime.parse("1979-05-27T00:32:00.999999"))

  it should "accept local date and local time" in:
    val doc = ok("ld1 = 1979-05-27\nlt1 = 07:32:00\nlt2 = 00:32:00.999999\n")
    doc.root("ld1") shouldBe TomlValue.LocalDate(jt.LocalDate.parse("1979-05-27"))
    doc.root("lt1") shouldBe TomlValue.LocalTime(jt.LocalTime.parse("07:32:00"))
    doc.root("lt2") shouldBe TomlValue.LocalTime(jt.LocalTime.parse("00:32:00.999999"))

  // --- Strings (v0.5.0 § String) ---

  it should "accept basic string escapes from v0.5.0 (plus \\u and \\U)" in:
    val doc = ok(raw"""esc = "\b\f\r\"\\"""" + "\n" + raw"""u = "\u00E9"""" + "\n" + raw"""U = "\U000000E9"""" + "\n")
    doc.root("esc") shouldBe TomlValue.Str("\b\f\r\"\\")
    doc.root("u") shouldBe TomlValue.Str("é")
    doc.root("U") shouldBe TomlValue.Str("é")

  it should "accept single-line literal strings" in:
    val doc = ok("winpath = 'C:\\Users\\nodejs\\templates'\nquoted = 'Tom \"Dubs\" Preston-Werner'\n")
    doc.root("winpath") shouldBe TomlValue.Str("""C:\Users\nodejs\templates""")
    doc.root("quoted") shouldBe TomlValue.Str("""Tom "Dubs" Preston-Werner""")

  it should "accept multiline literal strings with opening newline trim" in:
    val doc = ok("lines = '''\nThe first newline is\ntrimmed in raw strings.\n'''\n")
    // Only the newline directly after the opening ''' is trimmed; the newline before closing ''' is content.
    doc.root("lines") shouldBe TomlValue.Str("The first newline is\ntrimmed in raw strings.\n")

  it should "accept multiline literal with embedded single quotes (two-quote rule)" in:
    val doc = ok("regex2 = '''I [dw]on't need \\d{2} apples'''\n")
    doc.root("regex2") shouldBe TomlValue.Str("""I [dw]on't need \d{2} apples""")

  // --- Arrays (v0.5.0 § Array) ---

  it should "accept nested arrays and multiline layout with trailing comma" in:
    val doc = ok(
      """arr1 = [ 1, 2, 3 ]
        |arr2 = [ "red", "yellow", "green" ]
        |arr3 = [ [ 1, 2 ], [3, 4, 5] ]
        |arr8 = [
        |  1,
        |  2,
        |]
        |""".stripMargin,
    )
    doc.root("arr1") shouldBe TomlValue.Arr(
      List(TomlValue.Integer(1), TomlValue.Integer(2), TomlValue.Integer(3)),
    )
    doc.root("arr3").asInstanceOf[TomlValue.Arr].elems should have length 2

  it should "accept homogeneous string arrays mixing basic, literal, multiline forms (v0.5.0 same-type rule)" in:
    val doc = ok("arr4 = [ \"all\", 'strings', \"\"\"are the same\"\"\", '''type''' ]\n")
    doc.root("arr4").asInstanceOf[TomlValue.Arr].elems should have length 4
    doc.root("arr4").asInstanceOf[TomlValue.Arr].elems.foreach(_.isInstanceOf[TomlValue.Str] shouldBe true)

  it should "reject mixed int/float arrays (TOML 1.0.0)" in:
    parseError("arr = [ 1, 2.0 ]\n")

  // --- Tables (v0.5.0 § Table) ---

  it should "accept standard and nested table headers" in:
    val doc = ok("[table-1]\nkey1 = 1\n[table-2]\nkey2 = 2\n[a.b]\nx = 3\n")
    doc.root("table-1").asInstanceOf[TomlValue.Obj].fields("key1") shouldBe TomlValue.Integer(1)
    doc.root("a").asInstanceOf[TomlValue.Obj].fields("b").asInstanceOf[TomlValue.Obj].fields("x") shouldBe TomlValue.Integer(3)

  it should "accept quoted segments in table headers" in:
    val doc = ok("[dog.\"tater.man\"]\ntype.name = \"pug\"\n")
    val dog = doc.root("dog").asInstanceOf[TomlValue.Obj]
    val tater = dog.fields("tater.man").asInstanceOf[TomlValue.Obj]
    tater.fields("type").asInstanceOf[TomlValue.Obj].fields("name") shouldBe TomlValue.Str("pug")

  it should "create implicit parent tables (v0.5.0)" in:
    val doc = ok("[x.y.z.w]\nk = 1\n")
    val x = doc.root("x").asInstanceOf[TomlValue.Obj]
    val y = x.fields("y").asInstanceOf[TomlValue.Obj]
    val z = y.fields("z").asInstanceOf[TomlValue.Obj]
    val w = z.fields("w").asInstanceOf[TomlValue.Obj]
    w.fields("k") shouldBe TomlValue.Integer(1)

  it should "ignore whitespace padding in table header segments" in:
    val doc = ok("[ g . h . i ]\nk = 1\n")
    val g = doc.root("g").asInstanceOf[TomlValue.Obj]
    g.fields("h").asInstanceOf[TomlValue.Obj].fields("i").asInstanceOf[TomlValue.Obj].fields("k") shouldBe TomlValue.Integer(1)

  // --- Inline table (v0.5.0 § Inline Table) ---

  it should "accept inline tables with dotted keys inside" in:
    val doc = ok("name = { first = \"Tom\", last = \"Preston-Werner\" }\nanimal = { type.name = \"pug\" }\n")
    doc.root("name").asInstanceOf[TomlValue.Obj].fields("first") shouldBe TomlValue.Str("Tom")
    val animal = doc.root("animal").asInstanceOf[TomlValue.Obj]
    animal.fields("type").asInstanceOf[TomlValue.Obj].fields("name") shouldBe TomlValue.Str("pug")

  it should "accept array of inline tables" in:
    val doc = ok("points = [ { x = 1, y = 2, z = 3 }, { x = 7, y = 8, z = 9 } ]\n")
    val arr = doc.root("points").asInstanceOf[TomlValue.Arr]
    arr.elems should have length 2
    arr.elems.head.asInstanceOf[TomlValue.Obj].fields("x") shouldBe TomlValue.Integer(1)

  // --- Array of tables (v0.5.0 § Array of Tables) ---

  it should "accept multiple array-of-table elements and empty rows" in:
    val doc = ok("[[products]]\nname = \"Hammer\"\nsku = 1\n[[products]]\n[[products]]\nname = \"Nail\"\n")
    val arr = doc.root("products").asInstanceOf[TomlValue.Arr]
    arr.elems should have length 3
    arr.elems(0).asInstanceOf[TomlValue.Obj].fields("name") shouldBe TomlValue.Str("Hammer")
    arr.elems(1).asInstanceOf[TomlValue.Obj].fields shouldBe empty
    arr.elems(2).asInstanceOf[TomlValue.Obj].fields("name") shouldBe TomlValue.Str("Nail")

  it should "attach subtables to the current array-of-tables element (v0.5.0 fruit example, simplified)" in:
    val doc = ok(
      """[[fruit]]
        |name = "apple"
        |[fruit.physical]
        |color = "red"
        |[[fruit.variety]]
        |name = "red delicious"
        |[[fruit]]
        |name = "banana"
        |""".stripMargin,
    )
    val fruits = doc.root("fruit").asInstanceOf[TomlValue.Arr]
    fruits.elems should have length 2
    val apple = fruits.elems.head.asInstanceOf[TomlValue.Obj]
    apple.fields("name") shouldBe TomlValue.Str("apple")
    apple.fields("physical").asInstanceOf[TomlValue.Obj].fields("color") shouldBe TomlValue.Str("red")
    val varieties = apple.fields("variety").asInstanceOf[TomlValue.Arr]
    varieties.elems should have length 1
    varieties.elems.head.asInstanceOf[TomlValue.Obj].fields("name") shouldBe TomlValue.Str("red delicious")

  // --- Lexer: bare key that is all digits (v0.5.0 allows) — currently shadowed by numeric literal token ---

  it should "accept bare key that is only ASCII digits (v0.5.0)" ignore {
    // TODO: un-ignore when lexical token order allows Ident before NumericLit for keys like `1234 = "x"`.
    val doc = ok("1234 = \"only-digits\"\n")
    doc.root("1234") shouldBe TomlValue.Str("only-digits")
  }

  // --- Comments ---

  it should "treat # as full-line and end-of-line comments" in:
    val doc = ok("# c1\na = 1 # c2\n")
    doc.root("a") shouldBe TomlValue.Integer(1)

end TomlV050Spec
