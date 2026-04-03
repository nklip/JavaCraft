# OpenFlights

An OpenFlights-based data application.

**Stack:** Java 21, Spring Boot, Spring Kafka, Spring Data JPA, PostgreSQL, Swagger/OpenAPI, Cucumber, Testcontainers

## Contents
1. [Quick Start](#1-quick-start)

## 1. Quick Start
<sub>[Back to top](#openflights)</sub>

### 1.1. Docker - Local infrastructure

Start PostgreSQL and Kafka for local development with:

```bash
docker compose -f openflights/compose.yaml up -d
```

Stop them with:

```bash
docker compose -f openflights/compose.yaml down
```

This stack exposes:

- PostgreSQL on `localhost:5432` with database/user/password `openflights`
- Kafka on `localhost:9092`

### 1.2. Applications

The OpenFlights runtime is split into three Spring Boot applications:

- `openflights-kafka-producer`
  - reads OpenFlights `.dat` files from `openflights-data`
  - exposes one import endpoint per dataset
  - publishes typed records to Kafka
- `openflights-kafka-consumer`
  - consumes typed Kafka topics
  - normalizes and persists records into PostgreSQL
- `openflights-app`
  - exposes admin HTTP operations such as database cleanup

Important runtime responsibilities are intentionally separated:

- `OpenFlightsDataConfiguration`
  - wires the framework-light `openflights-data` parsers/readers into Spring beans
  - provides the dedicated executor used for import parallelism
- `OpenFlightsFileImportService`
  - reads one dataset at a time
  - publishes records in bounded parallel chunks
- `KafkaMessageProducer`
  - routes each record type to the correct Kafka topic
  - derives stable Kafka keys centrally
- `OpenFlightsPersistenceService`
  - handles consumer-side normalization, idempotent persistence, and out-of-order route ingestion
- `OpenFlightsPlaceholderWriteService`
  - writes temporary airline/airport/country placeholders in separate transactions so concurrent route ingestion does not
    poison the outer route transaction

### 1.3. Data

Ingest data in next order from Openflights-Kafka-Producer module.

Order:
1) countries
2) airlines
3) airports
4) planes
5) routes

Route ingestion is tuned differently from the other datasets:
- the `openflights.route` topic uses `8` partitions by default
- the consumer uses `8` route listener threads by default

Those defaults can be adjusted in:
- `openflights-kafka-producer/src/main/resources/application.yaml`
- `openflights-kafka-consumer/src/main/resources/application.yaml`

### 1.4. Testing note

The end-to-end Cucumber ingestion test uses the same route topic partition count and route consumer concurrency
as the normal runtime configuration.

The reported per-type and total ingestion timings are observational only and can vary from machine to machine
depending on CPU, Docker load, and local PostgreSQL/Kafka performance.

Those timings are measured from PostgreSQL reaching the expected dataset state for each import stage and for the full
import, not from producer HTTP response latency alone.
