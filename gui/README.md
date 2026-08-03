# GUI: Desktop Applications
<sub>[Back to JavaCraft](../README.md#micro-java-samples)</sub>

This folder contains the Swing desktop applications. Both are self-contained: they have no
server, no database, and no network dependencies — build the jar and run it.

## Modules

- [mathparser](mathparser/README.md) - Recursive-descent expression parser with a Swing calculator UI.
  Split into `parser` (the reusable evaluation library) and `parser-gui` (the desktop front end).
- [tic-tac-toe](tic-tac-toe/README.md) - Two-player Tic-Tac-Toe game with a configurable AI
  opponent and customisable player symbols.

## Build and run

Build both applications:

```bash
mvn -f gui/pom.xml clean package
```

`-pl gui` on its own only builds this aggregator pom — `-am` adds parents, not children.

Each application ships as an executable jar:

```bash
java -jar gui/mathparser/parser-gui/target/mathparser.jar
java -jar gui/tic-tac-toe/target/tic-tac-toe.jar
```

## Test coverage

`gui/mathparser/parser` and `gui/tic-tac-toe` produce ordinary JaCoCo reports under
`target/site/jacoco/`.

`gui/mathparser/parser-gui` sets `jacoco.skip` and opts out: it drives real Swing windows,
and instrumenting those classes makes the tests timing-sensitive. Its tests still run — only
the coverage agent is disabled. To measure it anyway:

```bash
mvn -pl gui/mathparser/parser-gui test -Djacoco.skip=false
```

Both applications need a display, so their view tests do not run in a headless environment.
