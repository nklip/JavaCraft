# shardshop-common

[ShardShop](../../README.md) › [Core](../README.md) › **Common utilities**

`shardshop-common`, in `shardshop-core/common`, owns reusable logic for ShardShop. All six applications depend
on it directly, and `shardshop-sharding` uses it for digest calculation. Add reusable validation and utilities
here when multiple modules need them.

- **Contains:** stateless validation, hashing, strict JSON, a bounded HTTP client, the currency policy and the
  shared JDBC and RabbitMQ engines.
- **Never contains:** Quarkus startup or CDI wiring, application dependencies, shard topology, ID generation,
  datasets or service wire models.

## Design

### Contents

| Area | Classes | Purpose |
|---|---|---|
| IDs and hashing | `IdParser`, `Sha256` | `IdParser` validates canonical positive IDs from text or a JSON string token. `Sha256.unsignedDigest` hashes unchanged UTF-8 text as an unsigned big-endian integer. |
| Strict JSON | `StrictJson`, `JsonFields`, `JsonResponses`, `JsonRequests` | One strict reader of request and response bodies: strict UTF-8, unique members and no trailing tokens. `JsonRequests` and `JsonFields.nonblankText` decode request bodies strictly. |
| HTTP client | `JsonHttpClient`, `HttpResponseException` | The bounded JSON HTTP client. A rejected status carries an optional bounded error-code token, never a response body. |
| Currency | `CurrencyPolicy` | The seller-country currency policy and exact `NUMERIC(19,2)` money rules ([policy](../../shardshop-product/ARCHITECTURE.md#regional-currency-policy)). |
| JDBC (`common.jdbc`) | `DatabasePhases`, `Endpoint`, `JdbcUrls` | The bounded transaction engine, database endpoints, and the TLS and driver defaults. |
| RabbitMQ (`common.amqp`) | `ConfirmedPublisher`, `QueueConsumer`, `BrokerSettings` | The publisher and consumer of order and ledger. |
| Logging | `Failures` | Log text for failures of background workers, such as the order CDC readers and the ledger consumer. |

The JSON parser uses the platform-managed Jackson streaming API. The unit tests and boundary vectors of these
classes are in common.

### RabbitMQ engines

- `ConfirmedPublisher` is one ordered stream with persistent mandatory messages, publisher confirms and finite
  timeouts. It replaces the publishers that order and ledger copied.
- `QueueConsumer` uses manual acknowledgements after the local commit, one prefetched delivery and reconnects
  with backoff and jitter. It replaces the copied queue consumers.
- Both classes use `BrokerSettings`. Common declares the RabbitMQ client as an optional dependency, so only
  order and ledger, which declare the client themselves, contain it.

### Resource ownership

Common manages no database connections. Each application owns its pools, endpoints, credentials, broker
settings and configuration, and the shared PostgreSQL code runs on the connections of these pools. A publisher
opens and closes only the connection of its own stream.

`DatabaseTimings` is an optional, framework-free observer for fixed JDBC phases. `DatabasePhases.run` callers
use its no-op observer by default. An enabled observer must be fast, nonblocking and thread safe. An observer
failure does not replace the database result or error. Product owns the opt-in Micrometer adapter and the
[measurement definitions](../../shardshop-product/RUNBOOK.md#opt-in-read-diagnostics).

### HTTP deadline filters

The HTTP deadline filters of product and order stay in the applications because they need Vert.x, JAX-RS and
CDI wiring. Their concurrency rule is common to both.

The filters synchronize only their request-state decisions. Response status reads, writes, connection closing
and timer cancellation run outside that monitor, because Vert.x can invoke response-end callbacks while it holds
its connection lock. A timeout claims the state before it accesses the transport, so a late writer is
suppressed. If a writer already claimed the response, the timeout closes the connection and does not write
another response. Completion marks the state finished before it cancels the timer, and then a queued timeout
does nothing.

### Related contracts

The [core boundaries](../README.md#dependency-rules) define dependency enforcement, application-owned models and
common release qualification. The [identifier contract](../idgen/README.md#identifier-format-and-validation)
defines canonical ID validation. The [routing contract](../sharding/README.md#version-2-routing-and-topology)
defines digest use.

## Operation

### ID validation

All six applications use `dev.nklip.javacraft.shardshop.common.IdParser`. Its implementation and regression
tests are in this library only.

- `parse(String)` validates path and descriptor text and returns an exact positive `long`.
- `parseJson(JsonParser)` requires the current token to be a JSON string. Then it applies the same whole-string
  ASCII and signed-long range checks, and does not advance or close the parser of the caller. Call it before
  typed DTO binding, so numeric tokens cannot become strings.
- An invalid ID throws `IllegalArgumentException` with a fixed message that contains no input data. This
  includes malformed string content that Jackson detects when it reads the current token. HTTP providers map the
  exception to `400 INVALID_REQUEST`. Message consumers must apply their invalid-command handling.
- A plain `IOException` from the stream propagates unchanged. The providers and consumers keep the validation of
  the surrounding document, including the mapping of syntax errors to HTTP 400.

The allocation `requestOrdinal` permits `"0"`, so `IdParser` does not validate it
([wire contracts](../README.md#wire-contracts)).

Run the tests of this library alone:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/common clean verify
```

The [six-application audit](../../RUNBOOK.md#dependency-and-test-validation) runs all affected application
checks without infrastructure, including dependency and SBOM reports and packaged routing tests.
