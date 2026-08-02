# blocking-verification
<sub>[Back to Blocking](../README.md)</sub>

This report-only module combines JaCoCo execution data from the Blocking Echo reactor.
It reports production classes from `blocking-client` and `blocking-server`, including
the paths exercised by unit tests and by the Cucumber E2E tests in `blocking-testing`.

The separate module is necessary because `blocking-testing` contains only test code.
A per-module JaCoCo report there has no production classes against which to map its
execution data, while `report-aggregate` can map that data to the client/server modules.

Generate the aggregate from the project root:

```bash
mvn -pl echo/blocking/blocking-verification -am clean verify
```

For a shorter functional E2E run:

```bash
mvn -pl echo/blocking/blocking-verification -am clean verify \
    -Dcucumber.filter.tags='@Virtual and not @Performance and not @PerformanceSummary'
```

The report is written to `echo/blocking/blocking-verification/target/site/jacoco-aggregate/`.
Use `-DskipITs` to generate a unit-test-only baseline for comparison.
