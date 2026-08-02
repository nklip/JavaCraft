# ess-verification

<sub>[Back to ESS](../README.md)</sub>

This reactor-only module creates one JaCoCo report from ESS unit and Cucumber E2E tests.

The Cucumber suite runs in `ess-testing` under Maven Failsafe and writes `jacoco-it.exec`.
That module is test-scoped here: it contributes execution data, while its downloader and
generator scaffolding is excluded from the system coverage denominator. `report-aggregate`
maps the unit and E2E data only to `ess-api` and `ess-app` production classes.

Run from the repository root:

```bash
mvn -pl microservices/ess/ess-verification -am clean verify
```

The report is written to
`microservices/ess/ess-verification/target/site/jacoco-aggregate/`.
