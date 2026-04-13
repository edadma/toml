# toml

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/toml_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/toml)](https://github.com/edadma/toml/commits)
![GitHub](https://img.shields.io/github/license/edadma/toml)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.20.2-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.10-blue.svg)
![TOML specification](https://img.shields.io/badge/TOML-v1.0.0-9333ea)

Cross-platform TOML parser for Scala 3, built on **scala-parser-combinators** (`StdLexical` / `StdTokenParsers`). Published artifacts target **JVM**, **JavaScript (Scala.js)**, and **Native (Scala Native)**.

**Repository:** [github.com/edadma/toml](https://github.com/edadma/toml)

## TOML specification conformance

The purple badge marks **[TOML v1.0.0](https://toml.io/en/v1.0.0)** as the **target**. Highlights:

- **Basic strings:** only the escapes listed in v1.0.0; **`\e` and `\xHH` are rejected** (v1.1.0).
- **Arrays:** v1.0.0 allows **mixed types** (e.g. `[1, 2.0]` and nested arrays of different inner types).
- **Single-line basic strings:** tab is allowed (v1.0.0), unlike v0.5.0.

**Implemented builder checks:** duplicate `[table]` headers; `[name]` on a key that is already an array of tables; `[[name]]` when `name` is already a normal table; static `arr = []` then `[[arr]]`; dotted-key table paths that cannot be reopened with a `[...]` header; super-table headers like `[x]` after `[x.y.z]` when valid; new **array-of-tables row** clears nested `[child...]` header state so tables like `[a.b.c]` can reopen under each `[[a.b]]` (toml-test `valid/table/array-table-array.toml`).

### Official `toml-test` corpus (JVM)

Clone [toml-lang/toml-test](https://github.com/toml-lang/toml-test) to `third_party/toml-test` (so `third_party/toml-test/tests/files-toml-1.0.0` exists). Then:

```bash
sbt tomlJVM/testOnly io.github.edadma.toml.TomlOfficial1_0_0Spec
```

The suite checks **every valid** case in `files-toml-1.0.0` against the tagged JSON expected by `toml-test`, and **every invalid** case in that list is required to fail `TomlParser.parse` (strict UTF-8 when reading files in the test harness).

Three invalid **one-line** multiline-string fixtures (`multiline-quotes-01`, `literal-multiline-quotes-01` / `-02`) are rejected via an **exact-line** pre-check in `TomlParser.parse`, because the lexer can otherwise accept ambiguous closing-quote runs; everything else is handled by the lexer, decoder, and builder.

Reference spec copies live in this repo as `v0.5.0.md`, `v1.0.0.md`, and `v1.1.0.md`.

**Not TOML 1.1.0:** documents that rely on v1.1-only features (notably **`\e`**, **`\xHH`**, and other 1.1 deltas) may fail to parse.

## Module coordinates

```scala
libraryDependencies += "io.github.edadma" %% "toml" % "0.0.3" // JVM

libraryDependencies += "io.github.edadma" %%% "toml" % "0.0.3" // cross: JS / Native via %%%
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
    val title = doc.root.get("title").collect { case Str(s) => s }
    val count = doc.root.get("count").collect { case Integer(n) => n }
```

The document root is a `Map[String, TomlValue]` on **`TomlDocument.root`**. Top-level keys are flat; nested TOML tables become **`TomlValue.Obj`** with a **`fields`** map.

```scala
val cfg = """
  |[server]
  |host = "127.0.0.1"
  |port = 8080
  |""".stripMargin

TomlParser.parse(cfg).foreach { doc =>
  doc.root.get("server").foreach {
    case Obj(m) =>
      val host = m.get("host").collect { case Str(s) => s }
      val port = m.get("port").collect { case Integer(n) => n }
    case _ =>
  }
}
```

Arrays are **`TomlValue.Arr`** (`elems: List[TomlValue]`). TOML 1.0.0 allows **mixed element types**; this parser accepts them.

```scala
val nums = TomlParser.parse("nums = [1, 2, 3]\n").toOption
  .flatMap(_.root.get("nums")) match
  case Some(Arr(xs)) => xs.collect { case Integer(n) => n }
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

### Tests

- **`TomlParserSpec`** — quick regression checks.
- **`TomlV050Spec`** — broader hand-written cases (mostly shaped like v0.5.0 examples) covering keys, integers, floats, booleans, offset/local date-times, basic and multiline literal strings, arrays, tables, inline tables, array-of-tables, and comments, checked against **v1.0.0** rules where they apply (including bare keys that are only ASCII digits).
- **`TomlV100Spec`** — duplicate `[table]` headers, dotted-key vs `[table]` redefine rules, `[[...]]` vs `[...]` conflicts, and related **TOML 1.0.0** builder errors.
- **`TomlOfficial1_0_0Spec`** (JVM) — full **toml-test** `files-toml-1.0.0` valid + invalid lists when `third_party/toml-test` is present (or `TOML_TEST_ROOT` is set).

## Publishing

The build follows the same Sonatype / Maven Central layout as `cross_template` (sbt-sonatype, sbt-pgp). After credentials and signing are configured:

```bash
sbt publishSigned
sbt sonatypeBundleRelease
```

## License

This project is licensed under the ISC License; see [LICENSE](LICENSE).
