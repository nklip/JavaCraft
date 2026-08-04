# quarkus-weather
<sub>[Back to Blueprints](../README.md#blueprints)</sub>

A single-page weather dashboard for Glasgow, Samara and Nha Trang, built on Quarkus and served
from live [Open-Meteo](https://open-meteo.com/) data.

The point of the module is the framework contrast: every other web module in this repo runs on
Spring Boot, which wires dependency injection by reflection at startup. Quarkus resolves the same
graph at **build time** and bakes it into bytecode, which is why this module needs the
`quarkus-maven-plugin` and starts in about half a second.

**Stack:** Java, Quarkus, Qute, MicroProfile REST Client, Open-Meteo

## Contents
1. [Quick Start](#1-quick-start)
2. [Architecture](#2-architecture)
3. [Weather Data](#3-weather-data)
4. [Failure Handling](#4-failure-handling)
5. [What Quarkus Does Differently](#5-what-quarkus-does-differently)
6. [Tests](#6-tests)

---

## 1. Quick Start

**Prerequisites:** Java 25, Maven, outbound HTTPS to `api.open-meteo.com`. No API key — Open-Meteo
is free and unauthenticated for non-commercial use.

### Build it

```bash
mvn -pl blueprints/quarkus-weather -am package -DskipTests
```

### Run it

```bash
java -jar blueprints/quarkus-weather/target/quarkus-app/quarkus-run.jar
```

Then open <http://localhost:8095/>.

### Live-reload development

```bash
mvn -pl blueprints/quarkus-weather -am quarkus:dev
```

Edit a Java file or `weather.html` and refresh the browser — Quarkus recompiles on the next
request, with no restart.

---

## 2. Architecture
<sub>[Back to top](#quarkus-weather)</sub>

```mermaid
flowchart LR
    Browser(["Browser"]) -->|GET /| Resource["WeatherResource<br/><i>HTTP mapping</i>"]
    Resource --> Service["WeatherService<br/><i>orchestration + mapping</i>"]
    Service --> Client["OpenMeteoClient<br/><i>REST client, IO only</i>"]
    Client -->|HTTPS| API[("api.open-meteo.com")]
    Resource --> Qute["weather.html<br/><i>Qute template</i>"]
```

Layering follows the repo's standard split:

| Package | Responsibility |
|---|---|
| `domain` | `City`, `WeatherSnapshot`, `CityWeather`, `WeatherCondition` — immutable records and an enum, with **no framework imports** |
| `client` | `OpenMeteoClient` and its response DTO — IO only |
| `service` | `WeatherService` — fetches each city, maps payloads into the domain |
| `web` | `WeatherResource`, `Templates`, `WeatherTemplateExtensions` — HTTP and presentation |

`WeatherService` takes its client through the constructor, so its tests instantiate it directly
with a Mockito mock and need no CDI container at all.

---

## 3. Weather Data
<sub>[Back to top](#quarkus-weather)</sub>

Cities are pinned to WGS84 coordinates rather than looked up by name, which avoids a geocoding
round trip and keeps results deterministic:

| City | Country | Latitude | Longitude |
|---|---|---:|---:|
| Glasgow | United Kingdom | 55.8642 | -4.2518 |
| Samara | Russia | 53.2001 | 50.1500 |
| Nha Trang | Vietnam | 12.2388 | 109.1967 |

Each request asks for `temperature_2m`, `relative_humidity_2m`, `wind_speed_10m` and
`weather_code`, with `timezone=auto` so every card reports its **own** local time rather than the
server's — the three timestamps are normally hours apart.

`weather_code` is a [WMO 4677](https://open-meteo.com/en/docs) interpretation code. The full table
is finer-grained than three cards can usefully show, so `WeatherCondition` collapses neighbouring
codes into buckets (`61`, `63`, `65` all become `RAIN`). Unmapped or absent codes become `UNKNOWN`
rather than failing the card.

---

## 4. Failure Handling
<sub>[Back to top](#quarkus-weather)</sub>

`CityWeather` is a result type, not a bare snapshot: it holds either an observation or the reason
one is missing. A city that times out, returns an error, or sends an incomplete payload degrades
to its own "unavailable" card while the other two render normally.

The connect and read timeouts are explicit in `application.properties` (2s / 5s). Without them a
stalled upstream would hold the request thread indefinitely.

Failures log at WARN with the exception message only — no stack trace for a routine upstream
timeout, and the request URL is left out because it carries the city's coordinates.

---

## 5. What Quarkus Does Differently
<sub>[Back to top](#quarkus-weather)</sub>

Next to the Spring Boot modules in this repo, three things stand out:

- **Build-time DI.** The `quarkus-maven-plugin` `build` goal runs an augmentation step that
  resolves injection, indexes annotations, and writes the wiring into bytecode. Startup measured
  **0.537s** on JVM, with no classpath scanning.
- **Build-time template checking.** `@CheckedTemplate` validates every expression in
  `weather.html` against the declared parameter types *during the build*. Writing
  `{...temperatureCelsius.format("%.1f")}` fails the build with
  `Property/method [format("%.1f")] not found on class [double]` rather than rendering a broken
  page. That is why formatting lives in `WeatherTemplateExtensions` instead.
- **Declarative REST client.** `OpenMeteoClient` is an interface; the implementation is generated
  at build time from the annotations.

Two integration details this module had to solve, both worth knowing before adding more Quarkus:

- **`-parameters` is required.** `@CheckedTemplate` binds template parameters by name, so the
  module sets `maven.compiler.parameters`. Without it the build fails with
  `Parameter names not recorded`.
- **The Quarkus BOM is imported here, not in the root pom.** Quarkus manages Jackson, Netty and
  JUnit, so a global import would fight the root pom's deliberate JUnit → Netty → Spring Boot
  ordering. Importing it in this module's `dependencyManagement` keeps the blast radius local.

---

## 6. Tests
<sub>[Back to top](#quarkus-weather)</sub>

```bash
mvn -pl blueprints/quarkus-weather test
```

91 plain unit tests plus 4 `@QuarkusTest` cases that boot the application against a WireMock
stand-in for Open-Meteo, so no test touches the network. The stub deliberately fails Nha Trang,
which exercises both template branches and proves one unreachable city does not take the page
down.

### Coverage summary

| Module | Tests | Line coverage |
|--------|------:|--------------:|
| blueprints/quarkus-weather | 95 | 100.0% (101/101) |

> [!NOTE]
> This module deliberately does **not** use the `quarkus-jacoco` extension. It records coverage
> only for `@QuarkusTest` classes, so the 91 unit tests contributed nothing and the report read
> 87.3% when the real figure was 100%. The root pom's standard JaCoCo agent sees both kinds of
> test, because Quarkus runs `@QuarkusTest` in the same JVM.

> [!TIP]
> `wiremock-standalone` is used rather than the thin `wiremock` artifact. The latter expects
> Jetty 11 on the classpath, which Quarkus does not provide, and fails at startup with
> `Jetty 11 is not present`.
