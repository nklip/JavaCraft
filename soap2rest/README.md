# Soap to Rest (soap2rest)

`soap2rest` demonstrates a strangler-style migration where an existing SOAP contract is kept stable while the backend behavior is implemented by a REST application.

The module is split so each concern stays explicit:
- SOAP facade for legacy-compatible integration
- REST backend for persistence, validation, security, and async processing
- shared REST DTOs for cross-module contracts
- small shared utilities used by both sides

Detailed design notes live in [ARCHITECTURE.md](ARCHITECTURE.md).

## Contents
1. [What It Demonstrates](#1-what-it-demonstrates)
2. [Modules](#2-modules)
3. [Quick Flow](#3-quick-flow)
4. [Module Ports](#4-module-ports)
5. [Build and Test](#5-build-and-test)
6. [Run](#6-run)
7. [Where To Look Next](#7-where-to-look-next)

## 1. What It Demonstrates
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

- SOAP endpoint translating service orders into REST calls
- Spring Boot REST backend with JPA, Liquibase, H2, Swagger, and API-key security
- synchronous gas, electric, meter, and smart metric flows
- asynchronous smart metric submission over JMS/Artemis with polling
- module-focused testing with WireMock, Cucumber, Mockito, and Testcontainers

## 2. Modules
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

- [utils](utils/README.md)
  - shared utility code used by the REST and SOAP modules
  - currently provides the `@ExecutionTime` aspect for lightweight timing logs
- [rest](rest/README.md)
  - REST-facing part of the demo
  - contains the shared API model and the runnable REST backend
- [soap](soap/README.md)
  - SOAP endpoint and SOAP-to-REST translation layer
  - owns the WSDL-generated model and SOAP-side integration tests

## 3. Quick Flow
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

### Synchronous flow
1. Client sends a `DSRequest` to the SOAP endpoint.
2. `soap` resolves the target service and operation.
3. `soap` calls `rest-app` over HTTP.
4. `rest-app` validates, persists, and returns JSON.
5. `soap` maps the REST result back into `DSResponse`.

### Asynchronous smart flow
1. Client sends a SOAP smart `PUT` request with the async flag enabled.
2. `soap` forwards that call to `rest-app` with `?async=true`.
3. `rest-app` returns `202 Accepted` and a request id, stores `ACCEPTED`, and pushes work to Artemis.
4. A JMS listener consumes the queued message and calls the normal smart business logic.
5. Client later polls the result through a separate SOAP request, which `soap` translates into a REST async-result lookup.

## 4. Module Ports
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

- SOAP app default port: `8078`
- REST app default port: `8081`
- SOAP WSDL: `http://localhost:8078/soap2rest/soap/v1/DeliverServiceWS.wsdl`
- REST Swagger UI: `http://localhost:8081/swagger-ui/index.html`

## 5. Build and Test
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

From the repository root:

Run REST tests:
```bash
mvn -pl soap2rest/rest/rest-app -am test
```

Run SOAP tests:
```bash
mvn -pl soap2rest/soap -am test
```

## 6. Run
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

Start the REST backend first:
```bash
mvn -pl soap2rest/rest/rest-app -am spring-boot:run
```

Start the SOAP facade:
```bash
mvn -pl soap2rest/soap -am spring-boot:run
```

## 7. Where To Look Next
<sub>[Back to top](#soap-to-rest-soap2rest)</sub>

- module design and boundaries: [ARCHITECTURE.md](ARCHITECTURE.md)
- REST implementation details: [rest/README.md](rest/README.md)
- SOAP contract and request mapping: [soap/README.md](soap/README.md)
