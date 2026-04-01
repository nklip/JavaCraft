# blocking-testing

## Module purpose

`blocking-testing` contains Cucumber end-to-end tests for the Blocking Echo system.  
It validates behavior across real client/server classes from:

- `blocking-client`
- `blocking-server`

This module is test-focused and does not produce runtime application code.

## What is covered

Feature files:

- `src/test/resources/features/blocking.feature` (functional + edge cases + high load)
- `src/test/resources/features/blocking-benchmark.feature` (performance-only scenarios)

Main coverage areas:

- Simple connect/send/disconnect flows.
- Multi-client counter consistency (`stats`).
- Message format and edge cases (empty input, spaces, escaped content).
- High-load behavior (100 clients x 100 messages each).
- Performance benchmark scenarios for server/client thread combinations.

Step definition classes:

- `BlockingStepDefinitions` - functional and load steps.
- `BlockingBenchmarkStepDefinitions` - benchmark/performance steps.
- `CommonStepDefinitions` - shared steps and cleanup hook.

## Tag model

Functional scenarios:

- `@Virtual` - functional flows using virtual clients.
- `@Platform` - functional flows using platform clients.

Performance scenarios:

- `@Performance` - benchmark scenarios.
- `@ServerPlatform` / `@ServerVirtual` - server type.
- `@ClientPlatform` / `@ClientVirtual` - client type.
- `@PerformanceSummary` - compares persisted benchmark outputs from all 4 combinations.

## Benchmark design

Implemented in `BlockingBenchmarkStepDefinitions` (with shared client operations from `BlockingStepDefinitions`):

- warmup iterations (not included in measured average/median)
- measured runs
- per-run metrics:
  - elapsed time
  - throughput (msg/s)
  - average latency (ms/msg)
- persisted result files under `target/performance-results/*.properties`
- final summary step reads all persisted files, sorts by average time, and prints:
  - fastest to slowest
  - absolute delta and percent delta
  - total measured time and total scenario time

During benchmark message loops, step definitions temporarily suppress `System.out` noise for more stable timings.

## How tests are run

Runner: `dev.nklip.javacraft.echo.blocking.CucumberRunner`

Reports generated to:

- `target/cucumber-reports/cucumber.html`
- `target/cucumber-reports/cucumber.json`

## Execute from project root

Run full cucumber suite for this module:

```bash
mvn -pl echo/blocking/blocking-testing -am test
```

Run only functional virtual scenarios:

```bash
mvn -pl echo/blocking/blocking-testing -am test -Dcucumber.filter.tags='@Virtual and not @Performance and not @PerformanceSummary'
```

Run only functional platform scenarios:

```bash
mvn -pl echo/blocking/blocking-testing -am test -Dcucumber.filter.tags='@Platform and not @Performance and not @PerformanceSummary'
```

Run one benchmark combination example (PlatformServer + PlatformThreadClient):

```bash
mvn -pl echo/blocking/blocking-testing -am test -Dcucumber.filter.tags='@Performance and @ServerPlatform and @ClientPlatform'
```

After running all 4 `@Performance` combinations, run final comparison:

```bash
mvn -pl echo/blocking/blocking-testing -am test -Dcucumber.filter.tags='@PerformanceSummary'
```

If `@PerformanceSummary` is run before benchmark result files exist, the test fails by design.
