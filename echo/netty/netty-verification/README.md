# netty-verification
<sub>[Back to Netty](../README.md)</sub>

This report-only module combines JaCoCo execution data from the Netty Echo reactor.
It reports production classes from `netty-client` and `netty-server`, including paths
exercised by unit tests and by the Cucumber E2E tests in `netty-testing`.

The separate module is necessary because `netty-testing` contains only test code.
`report-aggregate` maps that module's execution data to the client/server classes.

Generate the aggregate from the project root:

```bash
mvn -pl echo/netty/netty-verification -am clean verify
```

For a shorter functional E2E run:

```bash
mvn -pl echo/netty/netty-verification -am clean verify \
    -Dcucumber.filter.tags='not @Performance and not @PerformanceSummary'
```

The report is written to `echo/netty/netty-verification/target/site/jacoco-aggregate/`.
Use `-DskipITs` to generate a unit-test-only baseline for comparison.
