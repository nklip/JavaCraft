# selector-verification
<sub>[Back to Selector](../README.md)</sub>

This report-only module combines JaCoCo execution data from the Selector Echo reactor.
It reports production classes from `selector-client` and `selector-server`, including
paths exercised by unit tests and by the Cucumber E2E tests in `selector-testing`.

The separate module is necessary because `selector-testing` contains only test code.
`report-aggregate` maps that module's execution data to the client/server classes.

Generate the aggregate from the project root:

```bash
mvn -pl echo/selector/selector-verification -am clean verify
```

For a shorter functional E2E run:

```bash
mvn -pl echo/selector/selector-verification -am clean verify \
    -Dcucumber.filter.tags='not @Performance and not @PerformanceSummary'
```

The report is written to `echo/selector/selector-verification/target/site/jacoco-aggregate/`.
Use `-DskipITs` to generate a unit-test-only baseline for comparison.
