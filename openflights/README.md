# OpenFlights

An OpenFlights-based ingestion system that reads `.dat` datasets, publishes typed Kafka
messages, consumes them, and stores the normalized result in PostgreSQL.

**Stack:** Java 21, Spring Boot, Spring Kafka, Spring Data JPA, PostgreSQL, Swagger/OpenAPI, Cucumber, Testcontainers

## Contents
1. [Quick Start](#1-quick-start)
2. [Applications](#2-applications)
3. [Import Flow](#3-import-flow)
4. [Modules](#4-modules)
5. [Testing Notes](#5-testing-notes)

## 1. Quick Start
<sub>[Back to top](#openflights)</sub>

### 1.1 Local infrastructure

Start PostgreSQL and Kafka for local development with:

```bash
docker compose -f openflights/compose.yaml up -d
```

Stop them with:

```bash
docker compose -f openflights/compose.yaml down
```

If you want a clean local reset of persisted Docker data:

```bash
docker compose -f openflights/compose.yaml down -v
```

This stack exposes:

| Service | URL / Port | Notes |
|---|---|---|
| PostgreSQL | `localhost:5432` | database/user/password are all `openflights` |
| Kafka | `localhost:9092` | single-node KRaft broker |

### 1.2 Start the applications

The OpenFlights runtime is split into three Spring Boot applications:

```bash
mvn -f openflights/openflights-kafka-producer/pom.xml spring-boot:run
mvn -f openflights/openflights-kafka-consumer/pom.xml spring-boot:run
mvn -f openflights/openflights-app/pom.xml spring-boot:run
```

## 2. Applications
<sub>[Back to top](#openflights)</sub>

| Module | Port | Main purpose | Swagger / API docs |
|---|---|---|---|
| `openflights-kafka-producer` | `8051` | reads OpenFlights `.dat` files and publishes typed Kafka records | `http://localhost:8051/swagger-ui.html` / `http://localhost:8051/api-docs` |
| `openflights-kafka-consumer` | `8052` | consumes typed Kafka records and persists them into PostgreSQL | no Swagger surface |
| `openflights-app` | `8050` | operational/admin HTTP endpoints for persisted data | `http://localhost:8050/swagger-ui.html` / `http://localhost:8050/api-docs` |

Important runtime responsibilities:

- `OpenFlightsDataConfiguration`
  wires the framework-light `openflights-data` readers/parsers into Spring beans and provides the dedicated import executor.
- `OpenFlightsFileImportService`
  reads one dataset at a time and publishes records in bounded parallel chunks.
- `KafkaMessageProducer`
  routes each record type to the correct Kafka topic and derives stable Kafka keys.
- `OpenFlightsPersistenceService`
  owns consumer-side normalization, idempotent persistence, placeholder reference handling, and out-of-order route ingestion recovery.
- `OpenFlightsPlaceholderWriteService`
  writes temporary airline/airport/country placeholders in separate transactions so a concurrent duplicate placeholder insert does not abort the outer route transaction.
- `AdminDataController`
  exposes the PostgreSQL cleanup endpoint from `openflights-app`.

`openflights-kafka-consumer` and `openflights-app` both reuse the same datasource/JPA defaults from
`openflights-jpa` via `classpath:openflights-jpa-defaults.yaml`, so the Postgres connection settings
stay centralized.

## 3. Import Flow
<sub>[Back to top](#openflights)</sub>

Import data through the producer endpoints in this order:

1. countries
2. airlines
3. airports
4. planes
5. routes

Endpoints:

| Dataset | Endpoint |
|---|---|
| countries | `POST /api/v1/openflights/import/countries` |
| airlines | `POST /api/v1/openflights/import/airlines` |
| airports | `POST /api/v1/openflights/import/airports` |
| planes | `POST /api/v1/openflights/import/planes` |
| routes | `POST /api/v1/openflights/import/routes` |

The preferred runtime order is still the list above, even though route ingestion can now recover from
missing airline and airport references by creating temporary placeholders and later overwriting them
with real reference data.

Kafka topic layout:

| Topic | Default partitions | Notes |
|---|---|---|
| `openflights.country` | `1` | reference data |
| `openflights.airline` | `1` | reference data |
| `openflights.airport` | `1` | reference data |
| `openflights.plane` | `1` | reference data |
| `openflights.route` | `8` | higher-throughput route ingestion |

The route consumer also uses `8` listener threads by default. Those defaults can be adjusted in:

- [application.yaml](/Users/nikita.lipatov/projects/GitHub/JavaCraft/openflights/openflights-kafka-producer/src/main/resources/application.yaml)
- [application.yaml](/Users/nikita.lipatov/projects/GitHub/JavaCraft/openflights/openflights-kafka-consumer/src/main/resources/application.yaml)

### Admin cleanup

`openflights-app` exposes:

- `DELETE /api/v1/openflights/admin/data`

That endpoint deletes persisted PostgreSQL rows only. It does not reset Kafka topics, consumer offsets,
or any in-flight retry state.

## 4. Modules
<sub>[Back to top](#openflights)</sub>

| Module | Responsibility |
|---|---|
| `openflights-api` | shared records, Kafka topic constants, low-level OpenFlights value parsing |
| `openflights-data` | framework-light `.dat` readers and parsers |
| `openflights-jpa` | JPA entities, repositories, mappers, normalizers, shared datasource defaults |
| `openflights-kafka-producer` | import endpoints, file-import orchestration, Kafka publishing |
| `openflights-kafka-consumer` | typed Kafka listeners, persistence orchestration, placeholder repair |
| `openflights-app` | admin/operational HTTP endpoints for persisted data |
| `openflights-testing` | Cucumber end-to-end tests with Testcontainers |

For the full runtime and data-model breakdown, see [ARCHITECTURE.md](/Users/nikita.lipatov/projects/GitHub/JavaCraft/openflights/ARCHITECTURE.md).

## 5. Testing Notes
<sub>[Back to top](#openflights)</sub>

`openflights-testing` runs end-to-end Cucumber scenarios with Testcontainers and uses the same route
topic partition count and route consumer concurrency as the normal runtime configuration.

The full-ingestion Cucumber scenario currently expects the final PostgreSQL dataset to reach:

- countries: `260`
- airlines: `6162`
- airports: `7810`
- planes: `246`
- routes: `67663`
- routeEquipmentCodes: `93231`

Those values are asserted directly in
[OpenFlightsIngestion.feature](/Users/nikita.lipatov/projects/GitHub/JavaCraft/openflights/openflights-testing/src/test/resources/features/OpenFlightsIngestion.feature)
and cross-checked by the calculator test support.

Per-type and total ingestion timings reported by the Cucumber suite are observational only. They vary
from machine to machine depending on CPU, Docker load, and local PostgreSQL/Kafka performance, and they
measure when PostgreSQL reaches the expected dataset state rather than raw producer HTTP response time.
