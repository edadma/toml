# toml

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/toml_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/toml)](https://github.com/edadma/toml/commits)
![GitHub](https://img.shields.io/github/license/edadma/toml)
![Scala Version](https://img.shields.io/badge/Scala-3.8.3-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.20.2-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.10-blue.svg)
![TOML specification](https://img.shields.io/badge/TOML-v0.5.0-9333ea)

Cross-platform TOML parser for Scala 3, built on **scala-parser-combinators** (`StdLexical` / `StdTokenParsers`) with **Packrat** parsing. Published artifacts target **JVM**, **JavaScript (Scala.js)**, and **Native (Scala Native)**.

**Repository:** [github.com/edadma/toml](https://github.com/edadma/toml)

## TOML specification conformance

The purple badge marks **[TOML v0.5.0](https://toml.io/en/v0.5.0)** as the **baseline**: ordinary v0.5.0-shaped documents (tables, key paths, strings, numbers, arrays, inline tables, datetimes, etc.) are what this parser is built around. That is **“basic” or practical conformance** in the sense that real v0.5-era files should behave as authors expect—not in the sense of a strict validator that rejects every input a later spec would allow.

This parser **intentionally accepts extras** from **[v1.0.0](https://toml.io/en/v1.0.0)** and **[v1.1.0](https://toml.io/en/v1.1.0)** where they are useful, including for example:

- **`\e` and `\xHH`** in basic strings (reserved / invalid in v0.5.0 and v1.0.0; defined in v1.1.0)
- **Raw tab** in single-line basic `"..."` strings (disallowed in v0.5.0; allowed from v1.0.0 onward)
- Other **1.1** basic escapes and the same multiline basic behavior shared by v0.5.0 and later (line-ending backslash trimming, unescaped `"` where the spec allows, and so on)

So the input language is a **v0.5.0 core plus forward-compatible extensions**. It is still **not** a complete, spec-test-suite–certified implementation of any single release. Reference copies live in this repo as `v0.5.0.md`, `v1.0.0.md`, and `v1.1.0.md`.

**Gaps (all versions):** incomplete ABNF coverage, incomplete duplicate-key and table–array conflict checks, and other edge cases.

## Module coordinates

```scala
libraryDependencies += "io.github.edadma" %% "toml" % "0.0.1" // JVM

libraryDependencies += "io.github.edadma" %%% "toml" % "0.0.1" // cross: JS / Native via %%%
```

(Replace the version with the current release from Maven Central.)

## API

```scala
import io.github.edadma.toml.{TomlDocument, TomlParser, TomlValue}

val doc: Either[String, TomlDocument] = TomlParser.parse("key = 1\n")
```

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
- **`TomlV050Spec`** — broader hand-written cases aligned with v0.5.0 sections (keys, integers, floats, booleans, offset/local date-times, basic and multiline literal strings, arrays, tables, inline tables, array-of-tables, comments). It encodes **permissive** behavior where we differ from a strict validator (e.g. mixed-type arrays parse successfully). One case remains **ignored**: bare keys that are only ASCII digits (`1234 = "x"`), because the lexer currently prefers a numeric token over a bare key there.

This is **not** exhaustive for every v0.5.0 edge case (duplicate table headers, array/table conflicts, etc. are still only partially enforced—see **Gaps** above).

## Publishing

The build follows the same Sonatype / Maven Central layout as `cross_template` (sbt-sonatype, sbt-pgp). After credentials and signing are configured:

```bash
sbt publishSigned
sbt sonatypeBundleRelease
```

## License

This project is licensed under the ISC License; see [LICENSE](LICENSE).
