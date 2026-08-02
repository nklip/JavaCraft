# Elastic Search Service (ESS)
<sub>[Back to Microservices](../README.md#microservices)</sub>

`ess` is a multi-module Elasticsearch-backed service built around two main use cases:

- content search across several dataset indexes
- Reddit-style post submission, voting, and ranked feeds

The runtime lives in `ess-app`, shared models live in `ess-api`, and higher-level
integration coverage lives in `ess-testing`.

**Stack:** Spring Boot, Elasticsearch, Kibana, Spring Security, Swagger/OpenAPI, Cucumber

**Docs:** [ARCHITECTURE.md](ARCHITECTURE.md) · [ess-api](ess-api/README.md) · [ess-app](ess-app/README.md) · [ess-testing](ess-testing/README.md) · [ess-verification](ess-verification/README.md)

## Contents
1. [Quick Start](#1-quick-start)
2. [Module Map](#2-module-map)
3. [API Surface](#3-api-surface)
4. [Local Runtime](#4-local-runtime)
5. [Scheduler and Error Handling](#5-scheduler-and-error-handling)
6. [Tests](#6-tests)

## 1. Quick Start
<sub>[Back to top](#elastic-search-service-ess)</sub>

Prerequisites:

- Docker
- JDK + Maven

### Start Elasticsearch and Kibana

```bash
docker compose -f microservices/ess/compose.yaml up -d
```

By default the local stack exposes:

| URL | Description |
|---|---|
| http://localhost:9200 | Elasticsearch |
| http://localhost:5601 | Kibana |

### Start the application

```bash
mvn -pl microservices/ess/ess-app spring-boot:run
```

Application URLs:

| URL | Description |
|---|---|
| http://localhost:8001/swagger-ui/index.html | Swagger UI |
| http://localhost:8001/v3/api-docs | OpenAPI JSON |

### Create the required indexes

Before using the APIs, create the indexes you need through the admin endpoints:

- `PUT /api/admin/indexes/posts`
- `PUT /api/admin/indexes/user-votes`
- `PUT /api/admin/indexes/books`
- `PUT /api/admin/indexes/companies`
- `PUT /api/admin/indexes/movies`
- `PUT /api/admin/indexes/music`
- `PUT /api/admin/indexes/people`

Practical bootstrap order:

- for search demos: create the content indexes you plan to query
- for post/vote/ranking demos: create `posts` and `user-votes`

Full endpoint details and payload examples live in [ess-app → API Reference](ess-app/README.md#3-api-reference).

## 2. Module Map
<sub>[Back to top](#elastic-search-service-ess)</sub>

| Module | Responsibility |
|---|---|
| [ess-api](ess-api/README.md) | Shared DTOs, enums, validation annotations, API constants |
| [ess-app](ess-app/README.md) | Spring Boot runtime: controllers, services, Elasticsearch client config, scheduler |
| [ess-testing](ess-testing/README.md) | Cucumber integration tests, downloader utilities, dataset-oriented test support |
| [ess-verification](ess-verification/README.md) | Aggregate JaCoCo report for unit and Cucumber E2E coverage |

## 3. API Surface
<sub>[Back to top](#elastic-search-service-ess)</sub>

| Area | Base path | What it does |
|---|---|---|
| Admin | `/api/admin` | Creates Elasticsearch indexes for content, posts, and user votes |
| Search | `/api/services/search` | Generic wildcard fan-out search plus dedicated `wildcard`, `fuzzy`, `interval`, and `span` endpoints |
| Votes | `/api/services/user-votes` | Accepts `UPVOTE`, `DOWNVOTE`, and `NOVOTE`, stores one vote doc per `(userId, postId)` |
| Posts | `/api/services/posts` | Creates new post documents with generated IDs and initial ranking fields |
| Post ranking | `/api/services/posts/ranking` | Reads ranked feeds: `best`, `new`, `hot`, `rising`, `top`, and `top/{window}` |

Search notes:

- `SearchService.search()` uses Elasticsearch `msearch` and fans out wildcard queries across metadata-defined fields
- queryable fields are driven by `ess-app/src/main/resources/metadata.json`
- dedicated search endpoints use explicit query factories for wildcard, fuzzy, interval, and span queries

Ranking notes:

- `top` sorts by denormalized `karma`
- `best` uses the Wilson score lower bound
- `hot` uses a Reddit-style time-decayed score
- `rising` emphasizes recent vote velocity
- `top/{window}` supports `day`, `week`, `month`, and `year`

## 4. Local Runtime
<sub>[Back to top](#elastic-search-service-ess)</sub>

The repo-local Docker stack is defined in [compose.yaml](compose.yaml).

Default local behavior:

- Elasticsearch image: `docker.elastic.co/elasticsearch/elasticsearch:9.3.1-arm64`
- Kibana image: `docker.elastic.co/kibana/kibana:9.3.1-arm64`
- Elasticsearch security: enabled
- Elasticsearch HTTP SSL: disabled
- default Elasticsearch credentials:
  - user: `elastic`
  - password: `FverGoe0`
- default Kibana system password: `JgI820Ee`

Important application defaults from [ess-app/src/main/resources/application.yaml](ess-app/src/main/resources/application.yaml):

- app port: `8001`
- Elasticsearch host: `localhost:9200`
- SSL to Elasticsearch: disabled by default
- client timeouts and retry/backoff are configured in the custom Elasticsearch transport
- scheduler is enabled by default

To stop the local stack:

```bash
docker compose -f microservices/ess/compose.yaml down
```

If you need different local ports, passwords, or image tags, override the compose environment variables such as:

- `ES_LOCAL_VERSION`
- `ES_LOCAL_PORT`
- `KIBANA_LOCAL_PORT`
- `ES_LOCAL_PASSWORD`
- `KIBANA_LOCAL_PASSWORD`

## 5. Scheduler and Error Handling
<sub>[Back to top](#elastic-search-service-ess)</sub>

Scheduler behavior:

- `SchedulerJobs` runs hourly with cron `0 0 * * * *`
- the job is enabled when `scheduler.enabled=true`
- it removes `user-votes` documents older than `365` days

Error handling behavior:

- validation and malformed-request errors return `400`
- `IOException` returns `503`
- all other unhandled errors return `500`

Security note:

- the app currently allows anonymous access to all endpoints
- CSRF is disabled
- HTTP basic support is configured, but no route currently requires authentication

## 6. Tests
<sub>[Back to top](#elastic-search-service-ess)</sub>

```bash
# shared API models and validation
mvn -pl microservices/ess/ess-api test

# Spring Boot application tests
mvn -pl microservices/ess/ess-app test

# Cucumber + Testcontainers integration tests
mvn -pl microservices/ess/ess-testing -am verify

# Combined unit and E2E coverage report
mvn -pl microservices/ess/ess-verification -am clean verify
```

See module-specific docs for more detail:

- [ess-api → Models / Validation](ess-api/README.md)
- [ess-app → Tests](ess-app/README.md#9-tests)
- [ess-testing → Tests](ess-testing/README.md#5-tests)
- [ess-verification → Aggregate coverage](ess-verification/README.md)
