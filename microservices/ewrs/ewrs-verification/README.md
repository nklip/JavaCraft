# ewrs-verification

<sub>[Back to EWRS](../README.md)</sub>

This reactor-only module creates one JaCoCo report from EWRS unit, integration, and
Cucumber E2E tests.

`ewrs-testing` supplies execution data without adding an empty test-harness bundle.
`report-aggregate` maps that data to the events, API, application, scenarios, and dashboard
production modules.

Run from the repository root:

```bash
mvn -pl microservices/ewrs/ewrs-verification -am clean verify
```

The report is written to
`microservices/ewrs/ewrs-verification/target/site/jacoco-aggregate/`.
