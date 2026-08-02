# openflights-verification

<sub>[Back to OpenFlights](../README.md)</sub>

This reactor-only module creates one JaCoCo report from OpenFlights unit and Cucumber
end-to-end tests.

`openflights-testing` supplies execution data without adding an empty test-harness bundle.
`report-aggregate` maps that data to the API, data, JPA, app, Kafka consumer, and Kafka
producer production modules.

Run from the repository root:

```bash
mvn -pl microservices/openflights/openflights-verification -am clean verify
```

The report is written to
`microservices/openflights/openflights-verification/target/site/jacoco-aggregate/`.
