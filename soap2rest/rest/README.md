# soap2rest-rest

<sub>[Back to soap2rest](../README.md)</sub>

`soap2rest/rest` contains the REST contract and the runnable REST backend used by the SOAP facade.

## Contents
1. [What This Part Covers](#1-what-this-part-covers)
2. [Modules](#2-modules)
3. [Architecture Summary](#3-architecture-summary)
4. [Async Smart Flow](#4-async-smart-flow)
5. [Data and Schema](#5-data-and-schema)
6. [Build and Test](#6-build-and-test)
7. [Run](#7-run)
8. [Related Docs](#8-related-docs)

## 1. What This Part Covers
<sub>[Back to top](#soap2rest-rest)</sub>

- shared DTOs used across modules
- REST controllers for gas, electric, smart, and meter operations
- persistence with JPA, H2, and Liquibase
- API-key security for browser and client access
- asynchronous smart metric processing over JMS/Artemis
- REST-focused unit and cucumber tests

## 2. Modules
<sub>[Back to top](#soap2rest-rest)</sub>

| Module | Purpose |
| --- | --- |
| `rest-api` | Shared DTOs such as `Metric`, `Metrics`, and async result payloads |
| `rest-app` | Runnable Spring Boot service with controllers, services, DAO layer, security, Swagger, and tests |

Detailed runtime guide:
- [rest-app/README.md](rest-app/README.md)

## 3. Architecture Summary
<sub>[Back to top](#soap2rest-rest)</sub>

`rest-app` keeps a straightforward layered design:

1. Controllers map HTTP to application calls.
2. Services hold business rules and orchestration.
3. DAO and entity packages own persistence behavior.
4. Async smart writes are accepted over HTTP, queued to Artemis, and executed by a JMS listener.

More detail:
- [../ARCHITECTURE.md](../ARCHITECTURE.md)

## 4. Async Smart Flow
<sub>[Back to top](#soap2rest-rest)</sub>

The smart controller supports both execution modes on the same endpoint:

- `PUT /api/v1/smart/{id}?async=false`
  - executes immediately
  - returns `200 OK` with a boolean result
- `PUT /api/v1/smart/{id}?async=true`
  - queues the work
  - returns `202 Accepted` with a plain-text request id
- `GET /api/v1/smart/async/{requestId}`
  - returns `202`, `200`, `500`, or `404`

The async state machine is intentionally simple:
- `ACCEPTED`
- `COMPLETED`
- `FAILED`

## 5. Data and Schema
<sub>[Back to top](#soap2rest-rest)</sub>

- Liquibase changelogs live under `rest-app/src/main/resources/liquibase/`
- H2 is the default local database
- meter and metric integrity checks are enforced in the service and persistence layers

## 6. Build and Test
<sub>[Back to top](#soap2rest-rest)</sub>

From repository root:

Run REST backend tests:
```bash
mvn -pl soap2rest/rest/rest-app -am test
```

Run the shared API module build:
```bash
mvn -pl soap2rest/rest/rest-api -am test
```

## 7. Run
<sub>[Back to top](#soap2rest-rest)</sub>

```bash
mvn -pl soap2rest/rest/rest-app -am spring-boot:run
```

Default local URLs:
- Swagger UI: `http://localhost:8081/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`
- H2 console: `http://localhost:8081/console`

## 8. Related Docs
<sub>[Back to top](#soap2rest-rest)</sub>

- parent overview: [../README.md](../README.md)
- module architecture: [../ARCHITECTURE.md](../ARCHITECTURE.md)
- runnable service details: [rest-app/README.md](rest-app/README.md)
