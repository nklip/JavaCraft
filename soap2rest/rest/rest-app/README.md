# soap2rest-rest-app

<sub>[Back to soap2rest](../../README.md)</sub>

## Contents
1. [Purpose](#1-purpose)
2. [What It Demonstrates](#2-what-it-demonstrates)
3. [Package Responsibilities](#3-package-responsibilities)
4. [Main API Areas](#4-main-api-areas)
5. [Smart Sync vs Async](#5-smart-sync-vs-async)
6. [Data and Schema](#6-data-and-schema)
7. [Security](#7-security)
8. [Build and Test](#8-build-and-test)
9. [Run](#9-run)
10. [Troubleshooting](#10-troubleshooting)
11. [Related Docs](#11-related-docs)

## 1. Purpose
<sub>[Back to top](#soap2rest-rest-app)</sub>

`rest-app` is the runnable REST backend behind the `soap2rest` demo. It owns:

- metric and meter APIs
- account-scoped validation and business rules
- persistence and schema migration
- API-key security
- asynchronous smart metric execution over JMS/Artemis

This module is the system of record. The SOAP module delegates real work here rather than duplicating business logic.

## 2. What It Demonstrates
<sub>[Back to top](#soap2rest-rest-app)</sub>

- layered Spring Boot design
- JPA + Liquibase + H2
- Swagger / OpenAPI exposure
- synchronous and asynchronous API behavior on the same business area
- unit tests plus REST cucumber scenarios
- Testcontainers-backed Artemis coverage for async flows

## 3. Package Responsibilities
<sub>[Back to top](#soap2rest-rest-app)</sub>

- `rest`
  - controllers and HTTP mapping
- `service`
  - business logic and orchestration
- `service.async`
  - async job submission, state tracking, and JMS consumption
- `dao`
  - repositories and persistence operations
- `dao.entity`
  - database entities
- `conf` and `security`
  - application infrastructure and API-key security

## 4. Main API Areas
<sub>[Back to top](#soap2rest-rest-app)</sub>

| Area | Endpoints | Notes |
| --- | --- | --- |
| Smart | `GET /api/v1/smart`, `GET /api/v1/smart/{id}`, `GET /api/v1/smart/{id}/latest`, `PUT /api/v1/smart/{id}`, `DELETE /api/v1/smart/{id}` | combined gas + electric operations |
| Smart async result | `GET /api/v1/smart/async/{requestId}` | poll result of async smart `PUT` |
| Electric | `GET/PUT/DELETE /api/v1/smart/{id}/electric`, `GET /latest` | electric metric operations |
| Gas | `GET/PUT/DELETE /api/v1/smart/{id}/gas`, `GET /latest` | gas metric operations |
| Meter | `GET/PUT/DELETE /api/v1/smart/{id}/meters`, `GET/PUT/DELETE /api/v1/smart/{id}/meters/{meterId}` | account meter management |

## 5. Smart Sync vs Async
<sub>[Back to top](#soap2rest-rest-app)</sub>

### Synchronous smart write

`PUT /api/v1/smart/{id}?async=false`

- processes the request immediately through `SmartService`
- returns `200 OK`
- response body is a boolean result

### Asynchronous smart write

`PUT /api/v1/smart/{id}?async=true`

- stores an `ACCEPTED` async state
- publishes work to Artemis queue `smart-metrics-queue`
- returns `202 Accepted`
- response body is the request id as plain text

### Async result polling

`GET /api/v1/smart/async/{requestId}`

| HTTP status | Meaning |
| --- | --- |
| `202 Accepted` | request is still queued or running |
| `200 OK` | request completed successfully |
| `500 Internal Server Error` | async execution failed |
| `404 Not Found` | unknown request id |

Current implementation detail:
- async state is stored in memory, so results do not survive application restart

## 6. Data and Schema
<sub>[Back to top](#soap2rest-rest-app)</sub>

- Liquibase changelog root: `src/main/resources/liquibase/`
- Default DB: `jdbc:h2:mem:soap2rest;MODE=Oracle`
- H2 console path: `/console`
- gas and electric metric uniqueness is meter/date scoped

## 7. Security
<sub>[Back to top](#soap2rest-rest-app)</sub>

The application expects `X-API-KEY` on secured requests.

Local keys are stored in:
- `src/main/resources/api.keys`

For browser-based Swagger or H2 usage, use a header extension such as ModHeader or Requestly and add:

- header: `X-API-KEY`
- value: one of the keys from `api.keys`

## 8. Build and Test
<sub>[Back to top](#soap2rest-rest-app)</sub>

From repository root:

Run all module tests:
```bash
mvn -pl soap2rest/rest/rest-app -am test
```

Notes:
- unit tests cover controller and service behavior
- cucumber scenarios use RestAssured
- async cucumber scenarios start Artemis via Testcontainers

## 9. Run
<sub>[Back to top](#soap2rest-rest-app)</sub>

```bash
mvn -pl soap2rest/rest/rest-app -am spring-boot:run
```

Local URLs:
- Swagger UI: `http://localhost:8081/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8081/v3/api-docs`
- H2 console: `http://localhost:8081/console`

H2 login values:
- JDBC URL: `jdbc:h2:mem:soap2rest;MODE=Oracle;DB_CLOSE_DELAY=-1`
- user: `sa`
- password: `sa`

### Check local security mode

The default local setup uses the project's API-key security.

Quick checks:

```bash
# without API key
curl -i http://localhost:8081/v3/api-docs

# with API key
curl -i -H "X-API-KEY: <value-from-src/main/resources/api.keys>" \
  http://localhost:8081/v3/api-docs
```

How to interpret the result:
- `401` without the header and `200` with the header
  - you are in the normal project API-key mode
- startup log contains `Using generated security password:`
  - you are in Spring Boot default user/password mode
- `200` without any auth header
  - security is effectively disabled locally

### Local mode 1: project API-key security

This is the intended local mode and the current default.

For browser-based Swagger or H2 usage:
1. install a header extension such as ModHeader or Requestly
2. add header `X-API-KEY`
3. use one value from `src/main/resources/api.keys`
4. open:
   - `http://localhost:8081/swagger-ui/index.html`
   - `http://localhost:8081/console`

### Local mode 2: Spring Boot generated user/password

This mode is useful only for troubleshooting.

To switch to it:
1. disable the custom security configuration in `SecurityConfiguration`
2. start the application again
3. read the generated password from the startup log line:
   - `Using generated security password: ...`

Limitation:
- H2 console login still fails with `403` because the default Spring Security setup blocks the console flow with CSRF protection

### Local mode 3: no security

This is useful only for local debugging.

To switch to it:
1. keep `Application` excluding `SecurityAutoConfiguration`
2. disable the custom security configuration in `SecurityConfiguration`
3. start the application again

In this mode:
- Swagger UI is accessible without auth
- H2 console is accessible without auth
- requests to secured endpoints no longer require `X-API-KEY`

## 10. Troubleshooting
<sub>[Back to top](#soap2rest-rest-app)</sub>

- `401 Invalid API Key`
  - request is missing `X-API-KEY` or the key is invalid
- async request stays at `202`
  - check whether Artemis is reachable and the JMS listener is running
- async request returns `500`
  - check application logs for the business exception thrown during async processing
- `404` from async poll endpoint
  - request id does not exist in the in-memory async state map

## 11. Related Docs
<sub>[Back to top](#soap2rest-rest-app)</sub>

- REST parent module: [../README.md](../README.md)
- overall architecture: [../../ARCHITECTURE.md](../../ARCHITECTURE.md)
- SOAP facade details: [../../soap/README.md](../../soap/README.md)
