# Event-Sourced Work Request Service
<sub>[Back to Microservices](../README.md#microservices)</sub>

Initially created in 2016 and rebuilt in-place as an event-sourced work request service.

`ewrs` is now a PostgreSQL-backed Spring Boot example that demonstrates:
- CQRS with separate command and query paths
- append-only event storage
- replayable SQL projections
- live projection updates over SSE
- in-process event fan-out through the preserved `ewrs-events` kernel

See [ARCHITECTURE.md](ARCHITECTURE.md) for the detailed runtime view, module map, and projection flow.<br/>
See [SCHEMA.md](SCHEMA.md) for the SQL tables, sequence, and how each part of the schema is used.<br/>
See [ewrs-dashboard/README.md](ewrs-dashboard/README.md) for the Thymeleaf + ECharts visualization module.

## Contents
1. [Quick Start](#1-quick-start)
2. [Module layout](#2-module-layout)
3. [What stayed from the original module](#3-what-stayed-from-the-original-module)
4. [Runtime flow](#4-runtime-flow)
5. [Local runtime](#5-local-runtime)
6. [Testing split](#6-testing-split)
7. [Related docs](#7-related-docs)

## 1. Quick Start
<sub>[Back to top](#event-sourced-work-request-service)</sub>

### 1.1 Start PostgreSQL

Start the local EWRS database:

```bash
docker compose -f microservices/ewrs/compose.yaml up -d
```

Stop it with:

```bash
docker compose -f microservices/ewrs/compose.yaml down
```

If you want a clean reset of the persisted Docker volume:

```bash
docker compose -f microservices/ewrs/compose.yaml down -v
```

Local database settings:

| Service | URL / Port | Notes |
|---|---|---|
| PostgreSQL | `localhost:5434` | database/user/password are all `ewrs` |

### 1.2 Start the application

Run the Spring Boot app from the repo root:

```bash
mvn -f microservices/ewrs/ewrs-app/pom.xml spring-boot:run
```

On startup ewrs connects to PostgreSQL, validates the schema, and applies the Liquibase changelog.

### 1.3 Start the scenario driver

Run the standalone scenario/load driver from the repo root:

```bash
mvn -f microservices/ewrs/ewrs-scenarios/pom.xml spring-boot:run
```

By default it targets the core EWRS app at `http://localhost:8053`.

### 1.4 Start the dashboard

Run the standalone read-only dashboard after `ewrs-app` has initialized the shared EWRS schema:

```bash
mvn -f microservices/ewrs/ewrs-dashboard/pom.xml spring-boot:run
```

The dashboard reads the same PostgreSQL tables as `ewrs-app`, so the easiest local path is:

1. start PostgreSQL
2. start `ewrs-app` once so Liquibase creates the schema
3. start `ewrs-dashboard`

### 1.5 Open the local apps

Once the apps are running, these endpoints are available:

- EWRS app Swagger UI
  `http://localhost:8053/swagger-ui.html`
- EWRS app OpenAPI
  `http://localhost:8053/api-docs`
- Scenario driver Swagger UI
  `http://localhost:8054/swagger-ui.html`
- Scenario driver OpenAPI
  `http://localhost:8054/api-docs`
- Dashboard UI
  `http://localhost:8056/`
- Dashboard Swagger UI
  `http://localhost:8056/swagger-ui.html`
- Dashboard OpenAPI
  `http://localhost:8056/api-docs`

### 1.6 Generate a scenario or load

Run one deterministic happy-path workflow through the standalone driver:

```bash
curl -X POST http://localhost:8054/api/v1/scenarios/HAPPY_PATH/run
```

Generate deterministic mixed load with four work requests:

```bash
curl -X POST http://localhost:8054/api/v1/scenarios/load \
  -H 'Content-Type: application/json' \
  -d '{
    "count": 4
  }'
```

### 1.7 Create and inspect a work request directly through the core API

Create a request:

```bash
curl -X POST http://localhost:8053/api/v1/work-requests \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "Ship projection docs",
    "priority": "CRITICAL",
    "budgetCode": "PLATFORM-2026",
    "estimate": 40,
    "requestedBy": "Nikita",
    "correlationId": "quick-start-create"
  }'
```

List the projected requests:

```bash
curl http://localhost:8053/api/v1/work-requests
```

Inspect current budget projections:

```bash
curl http://localhost:8053/api/v1/projections/budgets
```

Watch live projection updates over SSE:

```bash
curl -N http://localhost:8053/api/v1/projections/stream
```

## 2. Module layout
<sub>[Back to top](#event-sourced-work-request-service)</sub>

`ewrs` now expands into seven modules:

- `ewrs-events`
  preserved event kernel with workflow events, notifier, subscriptions, and `EventsMonitor`
- `ewrs-api`
  REST/SSE request and response contracts
- `ewrs-app`
  Spring Boot service with the event store, command handling, projections, replay, and OpenAPI
- `ewrs-scenarios`
  standalone scenario/load driver that calls `ewrs-app` over HTTP for demos and deterministic data generation
- [ewrs-dashboard](ewrs-dashboard/README.md)
  standalone Thymeleaf + ECharts visualizer that reads EWRS projections and event history directly from PostgreSQL
- `ewrs-testing`
  Testcontainers + Cucumber/JUnit end-to-end coverage that exercises both the core API and the scenario driver
- [ewrs-verification](ewrs-verification/README.md)
  aggregate JaCoCo report for production-module unit, integration, and Cucumber E2E coverage

`ewrs-simulator` is no longer the runtime module. Its old random pipeline is retired from the active build.

## 3. What stayed from the original module
<sub>[Back to top](#event-sourced-work-request-service)</sub>

The strongest original asset was the event kernel, so v1 keeps the same event family:

- `CreatedEvent`
- `AcceptedEvent`
- `RejectedEvent`
- `RunningEvent`
- `CompletedEvent`

It also preserves:

- `EventNotifier`
- `EventsSubscriptionsManager`
- `EventsMonitor`

The main extension is that events now carry event-sourcing metadata such as `eventId`, `occurredAt`,
`actor`, `correlationId`, and `streamVersion`.

## 4. Runtime flow
<sub>[Back to top](#event-sourced-work-request-service)</sub>

At runtime the service behaves like this:

1. a REST command appends a new workflow event to PostgreSQL `event_store`
2. PostgreSQL `LISTEN/NOTIFY` wakes the projector
3. the projector catches up from its last applied event id
4. read-side SQL projections are updated in order
5. `EventsMonitor` is refreshed from the applied event
6. SSE subscribers receive the projection update

The domain is framed as work requests with:

- title
- priority
- budget code
- estimate
- requested by / acted by

Budget is reserved only when an approval produces `AcceptedEvent`.
Budget-denied approval produces a persisted `RejectedEvent` instead of an empty failure.

## 5. Local runtime
<sub>[Back to top](#event-sourced-work-request-service)</sub>

Local infrastructure lives in [compose.yaml](compose.yaml):

- PostgreSQL only

Runtime applications:

- `ewrs-app`
  core API on `localhost:8053`
- `ewrs-scenarios`
  optional scenario/load driver on `localhost:8054`, targeting `ewrs-app` over HTTP
- `ewrs-dashboard`
  optional read-only dashboard on `localhost:8056`, rendering SQL projections and event history with Thymeleaf + ECharts

The core application itself is API-driven:

- no random task generation
- no in-memory finance state
- no simulator worker threads in production runtime

## 6. Testing split
<sub>[Back to top](#event-sourced-work-request-service)</sub>

Testing is split by responsibility:

- `ewrs-events`
  unit coverage for event metadata, notifier fan-out, identity, and monitor behavior
- `ewrs-app`
  unit tests for command rules plus integration tests for event storage, projections, replay, and admin rebuild
- `ewrs-scenarios`
  unit coverage for deterministic scenario orchestration and target-client sequencing
- `ewrs-dashboard`
  unit and integration coverage for dashboard SQL aggregation, timeline drill-down, and standalone page rendering
- `ewrs-testing`
  end-to-end HTTP/SSE verification with Testcontainers PostgreSQL, calling the scenario driver the same way a manual user would

The Cucumber suite is named `CucumberIT` and runs with Maven Failsafe during `verify`.
Surefire continues to run the standalone `*Test` classes during `test`; the two lifecycles
write separate `jacoco.exec` and `jacoco-it.exec` files for aggregation.

Run the Cucumber suite and build the combined coverage report from the repository root:

```bash
mvn -pl microservices/ewrs/ewrs-verification -am clean verify
```

## 7. Related docs
<sub>[Back to top](#event-sourced-work-request-service)</sub>

- [ARCHITECTURE.md](ARCHITECTURE.md)
  runtime topology, module map, write side, read side, and projection flow
- [SCHEMA.md](SCHEMA.md)
  SQL table-level documentation for `event_store`, projections, reference data, and projector checkpointing
- [ewrs-dashboard/README.md](ewrs-dashboard/README.md)
  module-specific quick start for the Thymeleaf + ECharts dashboard, including its page and JSON endpoints
