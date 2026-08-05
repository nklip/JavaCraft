# Micro java samples.

* [algs](algs/README.md) - Algorithms
* [blueprints](blueprints/README.md) - Architectural blueprints
* [echo](echo/README.md) - Different Server types
* [gui](gui/README.md) - Swing desktop applications (Math parser, Tic-tac-toe)
* [microservices](microservices/README.md) - Microservices
* [vfs](vfs/README.md) - Virtual File Server
* [xlspaceship](xlspaceship/README.md) - Battleship game

## Contents
1. [Dependency management](#dependency-management)
2. [Secrets and local environment](#secrets-and-local-environment)
3. [Test coverage](#test-coverage)

## Dependency management
<sub>[Back to top](#micro-java-samples)</sub>

### Overview of dependencies

```bash
mvn dependency:tree
```

### To find unused dependencies
```bash
mvn dependency:analyze
```

### To see new dependencies without snapshots, prereleases, or major upgrades
```bash
mvn versions:display-dependency-updates -DprocessDependencyManagement=false -DprocessDependencyManagementTransitive=false
```

### To see new dependencies including major upgrades
```bash
mvn versions:display-dependency-updates -DallowMajorUpdates=true -DprocessDependencyManagement=false -DprocessDependencyManagementTransitive=false
```


## Secrets and local environment
<sub>[Back to top](#micro-java-samples)</sub>

Most modules need no credentials. The ones that do read them from **environment variables** —
never from a file in the repo. Committed configuration only ever holds a reference, e.g.
`anthropic.api-key=${ANTHROPIC_API_KEY:}`.

The recommended way to set them locally is [direnv](https://direnv.net/):

```bash
brew install direnv          # then hook it into your shell: direnv hook --help
cp .envrc.example .envrc     # fill in the values
direnv allow
```

`.envrc.example` lists every variable the repo understands and is committed; `.envrc` is
gitignored.

> [!NOTE]
> A per-module `.env` also works, but only if you launch from that module's directory —
> frameworks read `.env` from the **process working directory**, and this repo is normally
> driven from the root. `mvn -pl <module> quarkus:dev` runs with the module as its working
> directory, while `java -jar <module>/target/…` from the root does not, so no single `.env`
> location covers both. An exported variable takes precedence over `.env` and works either way,
> which is why direnv is the recommendation.

Never commit a key. If one is ever pushed, **rotate it first** — rewriting history does not
un-leak it.

## Test coverage
<sub>[Back to top](#micro-java-samples)</sub>

JaCoCo is wired into the root pom, so any `mvn test` run also produces a coverage report
for the modules it built, except modules that explicitly opt out.

```bash
mvn -pl <module> test
```

The report lands in `<module>/target/site/jacoco/` — open `index.html` for the browsable
view, or read `jacoco.csv` / `jacoco.xml` for tooling.

`gui/mathparser/parser-gui` opts out because instrumenting its Swing classes makes its
real-window tests timing-sensitive. Its dependency, `gui/mathparser/parser`, still
produces coverage.

To run tests without the coverage agent:

```bash
mvn -pl <module> test -Djacoco.skip=true
```

There are no coverage thresholds yet: `jacoco:check` is not configured, so a low number
reports but does not fail the build.

The Echo E2E tests run with Failsafe during `verify`. Each implementation has a dedicated
verification module that combines its client/server unit and E2E coverage:

```bash
mvn -pl echo/blocking/blocking-verification -am clean verify
mvn -pl echo/netty/netty-verification -am clean verify
mvn -pl echo/selector/selector-verification -am clean verify
```

Each aggregate report is written under its verification module's
`target/site/jacoco-aggregate/` directory.

The Microservices Cucumber suites use the same Failsafe and aggregate-report pattern:

```bash
mvn -pl microservices/ess/ess-verification -am clean verify
mvn -pl microservices/ewrs/ewrs-verification -am clean verify
mvn -pl microservices/openflights/openflights-verification -am clean verify
```

