# toml

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/toml_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/toml)](https://github.com/edadma/toml/commits)
![GitHub](https://img.shields.io/github/license/edadma/toml)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.20.2-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.10-blue.svg)
[![TOML specification](https://img.shields.io/badge/TOML-v1.1.0-9333ea)](https://toml.io/en/v1.1.0)

Cross-platform TOML parser for Scala 3, built on **scala-parser-combinators** (`StdLexical` / `StdTokenParsers`). Published artifacts target **JVM**, **JavaScript (Scala.js)**, and **Native (Scala Native)**.

## TOML specification conformance

**[TOML v1.1.0](https://toml.io/en/v1.1.0)** highlights — what's new vs. v1.0.0 in this parser:

- **Basic strings:** `\e` (U+001B) and `\xHH` (single-byte hex escape, U+00HH) are accepted in basic and multi-line basic strings.
- **Date-times and times:** seconds are optional. `13:37`, `1979-05-27T07:32`, `1979-05-27 07:32Z`, and `1979-05-27 07:32-07:00` all parse, with `:00` assumed for the missing seconds.
- **Inline tables:** newlines are permitted between `{` and `}` (between pairs, before `}`), and a trailing comma is allowed after the last pair.
- **Multi-line strings:** a bare carriage return (CR not followed by LF) inside `"""…"""` or `'''…'''` is rejected.

The 1.0.0 baseline is also covered:

- **Arrays** may mix types (e.g. `[1, 2.0]` and nested arrays of different inner types).
- **Single-line basic strings** allow tab (unlike v0.5.0).
- **Builder checks:** duplicate `[table]` headers; `[name]` on a key that is already an array of tables; `[[name]]` when `name` is already a normal table; static `arr = []` then `[[arr]]`; dotted-key table paths that cannot be reopened with a `[...]` header; super-table headers like `[x]` after `[x.y.z]` when valid; a new **array-of-tables row** clears nested `[child…]` header state so tables like `[a.b.c]` can reopen under each `[[a.b]]` (toml-test `valid/table/array-table-array.toml`).

### Official `toml-test` corpus (JVM)

Clone [toml-lang/toml-test](https://github.com/toml-lang/toml-test) to `third_party/toml-test` (so `third_party/toml-test/tests/files-toml-1.1.0` exists). Then:

```bash
sbt "tomlJVM/testOnly io.github.edadma.toml.TomlOfficial1_1_0Spec"
```

The suite checks **every valid** case in `files-toml-1.1.0` against the tagged JSON expected by `toml-test`, and **every invalid** case in that list is required to fail `TomlParser.parse` (strict UTF-8 when reading files in the test harness).

Three invalid **one-line** multiline-string fixtures (`multiline-quotes-01`, `literal-multiline-quotes-01` / `-02`) are rejected via an **exact-line** pre-check in `TomlParser.parse`, because the lexer can otherwise accept ambiguous closing-quote runs; everything else is handled by the lexer, decoder, and builder.

Reference spec copies live in this repo as `v0.5.0.md`, `v1.0.0.md`, and `v1.1.0.md`.

## Module coordinates

```scala
libraryDependencies += "io.github.edadma" %% "toml" % "0.2.0" // JVM

libraryDependencies += "io.github.edadma" %%% "toml" % "0.2.0" // cross: JS / Native via %%%
```

(Replace the version with the current release from Maven Central.)

## Usage

Parse a UTF-8 string; failures are a **left** value with a short message (lexer, parser, or builder / TOML 1.0.0 rules).

```scala
import io.github.edadma.toml.{TomlParser, TomlValue}
import TomlValue.*

val input = """title = "Demo"
              |count = 42
              |enabled = true
              |""".stripMargin

TomlParser.parse(input) match
  case Left(msg)  => println(s"parse error: $msg")
  case Right(doc) =>
    val title = doc.getString("title")
    val count = doc.getInt("count") // Option[Long] — TOML integers are 64-bit
```

**`TomlDocument.root`** is a **`VectorMap[String, TomlValue]`**: it behaves like a map but **iterates keys in file order** (first-seen order), which helps config emitters round-trip section order. Nested tables are **`TomlValue.Obj`** with **`fields: VectorMap[String, TomlValue]`** (same ordering).

**Dotted lookups** on the document (each segment is one key; dots are separators only):

```scala
doc.getString("kernel.name")
doc.getInt("memory.page_size")
doc.getTable("server")      // Option[VectorMap[String, TomlValue]]
doc.get("nested.path")      // Option[TomlValue]
```

**`TomlValue`** extractors throw **`TomlTypeMismatch`** if the shape is wrong: **`toStr`**, **`toLong`** / **`toInt`** (both return `Long` for TOML integers), **`toDouble`**, **`toBool`**, **`toTable`**, **`toArr`**, plus **`toOffsetDateTime`**, **`toLocalDateTime`**, **`toLocalDate`**, **`toLocalTime`**.

```scala
val cfg = """
  |[server]
  |host = "127.0.0.1"
  |port = 8080
  |""".stripMargin

TomlParser.parse(cfg).foreach { doc =>
  val host = doc.getString("server.host")
  val port = doc.getInt("server.port")
  doc.getTable("server").foreach { t =>
    val h = t.get("host").map(_.toStr)
  }
}
```

Arrays are **`TomlValue.Arr`** (`elems: List[TomlValue]`). TOML 1.0.0 allows **mixed element types**; this parser accepts them.

```scala
val nums = TomlParser.parse("nums = [1, 2, 3]\n").toOption
  .flatMap(_.root.get("nums")) match
  case Some(Arr(xs)) => xs.collect { case Num(n) => n }
  case _             => Nil
```

**Array of tables** `[[items]]` becomes an **`Arr`** of **`Obj`** (one object per segment).

```scala
val aot = """
  |[[items]]
  |id = 1
  |[[items]]
  |id = 2
  |""".stripMargin

TomlParser.parse(aot).foreach { doc =>
  doc.root.get("items").foreach {
    case Arr(rows) =>
      rows.foreach {
        case Obj(m) => println(m.get("id"))
        case _      => ()
      }
    case _ =>
  }
}
```

Times and dates use **`java.time`** on the JVM: **`OffsetDateTime`**, **`LocalDateTime`**, **`LocalDate`**, **`LocalTime`** (see `TomlValue` cases). On Scala.js and Scala Native the same types come from **scala-java-time**.

## Project structure

```
toml/
├── shared/                 # Parser, lexer, AST, builder (all platforms)
├── jvm/                    # JVM-only stub (`platform`)
├── js/                     # JS stub (`platform`)
├── native/                 # Native stub (`platform`)
├── project/
│   ├── build.properties
│   └── plugins.sbt
├── build.sbt
├── v0.5.0.md               # TOML v0.5.0 spec (reference copy)
├── v1.0.0.md               # TOML v1.0.0 spec (reference copy)
└── v1.1.0.md               # TOML v1.1.0 spec (reference copy)
```

## Prerequisites

- JDK 11 or higher
- sbt 1.12.8 or higher
- Node.js (for Scala.js tests)
- LLVM/Clang (for Scala Native)

## Building and testing

```bash
git clone git@github.com:edadma/toml.git
cd toml
```

```bash
sbt compile
sbt test

sbt tomlJVM/test
sbt tomlJS/test
sbt tomlNative/test
```

### Changes in 0.2.0

- **TOML 1.1.0 spec support.** `\e` and `\xHH` string escapes; optional seconds in offset/local date-times and local times; newlines and trailing commas inside inline tables; bare CR (CR not followed by LF) inside multi-line strings is now rejected.
- **Official toml-test corpus:** the runner now targets `files-toml-1.1.0` (`TomlOfficial1_1_0Spec`); the previous `TomlOfficial1_0_0Spec` is removed.

### Breaking changes in 0.1.0

- **`TomlValue.Integer`** was renamed to **`TomlValue.Num`**.
- **`TomlDocument.root`** and **`TomlValue.Obj.fields`** are **`VectorMap`** (ordered) instead of an unordered **`Map`**.

### Tests

- **`TomlParserSpec`** — quick regression checks, insertion order, document/value accessors, and the new 1.1.0 `\e`/`\xHH` escapes (including malformed-`\xHH` rejection).
- **`TomlV050Spec`** — broader hand-written cases (mostly shaped like v0.5.0 examples) covering keys, integers, floats, booleans, offset/local date-times, basic and multiline literal strings, arrays, tables, inline tables, array-of-tables, and comments.
- **`TomlV100Spec`** — duplicate `[table]` headers, dotted-key vs `[table]` redefine rules, `[[...]]` vs `[...]` conflicts, and related builder errors (1.0.0/1.1.0 share these rules).
- **`TomlOfficial1_1_0Spec`** (JVM) — full **toml-test** `files-toml-1.1.0` valid + invalid lists when `third_party/toml-test` is present (or `TOML_TEST_ROOT` is set).

## License

This project is licensed under the ISC License; see [LICENSE](LICENSE).
