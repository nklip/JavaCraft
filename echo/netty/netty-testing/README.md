# netty-testing

## Module purpose

`netty-testing` contains Cucumber end-to-end tests for the Netty echo implementation.
It validates behavior across real classes from:

- `netty-client`
- `netty-server`

## What is covered

Feature files:

- `src/test/resources/features/netty.feature`
  - functional scenarios
  - edge-case/message-format scenarios
  - high-load scenario
- `src/test/resources/features/netty-benchmark.feature`
  - benchmark scenario with warmups + measured runs
  - persisted summary scenario

Step definition classes:

- `CommonStepDefinitions` - shared server startup and cleanup.
- `NettyStepDefinitions` - functional and high-load client/server steps.
- `NettyBenchmarkStepDefinitions` - benchmark execution and summary comparison.

## Tags

- `@Performance` - executes benchmark workload.
- `@PerformanceSummary` - reads persisted benchmark summary and prints final output.

## Benchmark flow

Benchmark step:

- runs warmup iterations (not included in measured average)
- runs measured iterations
- prints per-run metrics and aggregate metrics
- persists results to:
  - `target/performance-results/netty-netty.properties`

Summary step:

- reads persisted benchmark summary
- prints final average/throughput/latency
- prints total measured and total scenario execution time

If `@PerformanceSummary` is run before `@Performance` creates the summary file, it fails by design.

## How tests are run

Runner:

- `dev.nklip.javacraft.echo.netty.CucumberRunner`

Reports:

- `target/cucumber-reports/cucumber.html`
- `target/cucumber-reports/cucumber.json`

## Execute from project root

Run full suite for this module:

```bash
mvn -pl echo/netty/netty-testing -am test
```

Run only benchmark scenario:

```bash
mvn -pl echo/netty/netty-testing -am test -Dcucumber.filter.tags='@Performance'
```

Run only benchmark summary:

```bash
mvn -pl echo/netty/netty-testing -am test -Dcucumber.filter.tags='@PerformanceSummary'
```

