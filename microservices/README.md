# Microservices
<sub>[Back to JavaCraft](../README.md#micro-java-samples)</sub>

Full-stack Java services, each with its own persistence layer, Docker infrastructure, and API surface. ShardShop uses Quarkus, and the other modules use Spring Boot.

## Modules

- [ess](ess/README.md) - Elasticsearch-backed content search and Reddit-style post voting with ranked feeds
- [ewrs](ewrs/README.md) - Event-Sourced Work Request Service
- [linker](linker/README.md) - URL shortening service with collision-safe IDs, redirects, and analytics backed by MongoDB
- [openflights](openflights/README.md) - OpenFlights dataset ingestion pipeline via Kafka into PostgreSQL with admin HTTP endpoints
- [shardshop](shardshop/README.md) - Sharded shop lab with Quarkus services, a regional PostgreSQL catalog, an order saga and an asynchronous ledger on a local kind cluster, and terraform with AWS deployment.
- [soap2rest](soap2rest/README.md) - Strangler-style SOAP-to-REST migration with synchronous and async JMS flows
