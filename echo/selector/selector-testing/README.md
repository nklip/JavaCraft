# selector-testing

## Module purpose

`selector-testing` contains Cucumber end-to-end tests for the selector-based non-blocking echo implementation.
It validates behavior across real classes from:

- `selector-client`
- `selector-server`

## What is covered

Feature files:

- `src/test/resources/features/selector.feature`
  - functional scenarios
  - edge-case/format scenarios
  - high-load scenario
- `src/test/resources/features/selector-benchmark.feature`
  - selector benchmark scenario (SelectorServer + SelectorClient)
  - persisted summary scenario

Step definition classes:

- `SelectorStepDefinitions` - functional and load steps with shared implementation.
- `SelectorBenchmarkStepDefinitions` - benchmark-only steps.
- `CommonStepDefinitions` - shared `@Given` server startup and `@After` cleanup.

## Tags

- `@Performance` - benchmark scenario
- `@PerformanceSummary` - reads persisted benchmark summary and prints final result

## Benchmark flow

Benchmark step:

- runs warmups (not measured)
- runs measured iterations
- prints per-run and aggregate metrics
- persists summary under `target/performance-results/selector-selector.properties`

Summary step:

- reads persisted benchmark summary
- prints `[PERF][FINAL]` output for this selector case
- prints total measured time and total benchmark scenario time

## How tests are run

Runner:

- `dev.nklip.javacraft.echo.selector.CucumberRunner`

Reports:

- `target/cucumber-reports/cucumber.html`
- `target/cucumber-reports/cucumber.json`

## Execute from project root

Run full suite for this module:

```bash
mvn -pl echo/selector/selector-testing -am test
```

Run only benchmark scenario:

```bash
mvn -pl echo/selector/selector-testing -am test -Dcucumber.filter.tags='@Performance'
```

Run only benchmark summary:

```bash
mvn -pl echo/selector/selector-testing -am test -Dcucumber.filter.tags='@PerformanceSummary'
```

If `@PerformanceSummary` runs before benchmark results exist, it fails by design.
