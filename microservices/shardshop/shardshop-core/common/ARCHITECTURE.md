# Common utilities architecture

[ShardShop](../../ARCHITECTURE.md) › [Core](../ARCHITECTURE.md) › **Common utilities** · [Runbook](RUNBOOK.md)

`shardshop-common`, in `shardshop-core/common`, owns reusable logic used across
ShardShop. All six applications depend on common directly, and
`shardshop-sharding` uses it for digest calculation. Add reusable validation and
utilities here when multiple modules need them.

- **Contains:** stateless validation, hashing, strict JSON, a bounded HTTP client,
  the currency policy and the shared JDBC and RabbitMQ engines.
- **Never contains:** Quarkus startup/CDI wiring, application dependencies, shard
  topology, ID generation, datasets or service wire models.

## Contents

| Area | Classes | Purpose |
|---|---|---|
| IDs and hashing | `IdParser`, `Sha256` | `IdParser` validates canonical positive IDs from text or a current JSON string token. `Sha256.unsignedDigest` hashes unchanged UTF-8 text as an unsigned big-endian integer. |
| Strict JSON | `StrictJson`, `JsonFields`, `JsonResponses`, `JsonRequests` | One strict reader of request and response bodies: strict UTF-8, unique members and no trailing tokens. `JsonRequests` and `JsonFields.nonblankText` decode request bodies strictly. |
| HTTP client | `JsonHttpClient`, `HttpResponseException` | The bounded JSON HTTP client. A rejected status carries an optional bounded error-code token, never a response body. |
| Currency | `CurrencyPolicy` | The authoritative seller-country currency policy and exact `NUMERIC(19,2)` money rules ([policy](../../shardshop-product/ARCHITECTURE.md#regional-currency-policy)). |
| JDBC (`common.jdbc`) | `DatabasePhases`, `Endpoint`, `JdbcUrls` | The bounded transaction engine, database endpoints, and the TLS and driver defaults. |
| RabbitMQ (`common.amqp`) | `ConfirmedPublisher`, `QueueConsumer`, `BrokerSettings` | The publisher and consumer of order and ledger, described below. |
| Logging | `Failures` | Log text for the failures of background workers, such as the order CDC readers and the ledger consumer. |

The JSON parser of common uses the platform-managed Jackson streaming API. The
unit tests and boundary vectors of these classes live in common.

## RabbitMQ engines

- `ConfirmedPublisher` replaces the copied RabbitMQ publishers of order and ledger:
  one ordered stream with persistent mandatory messages, publisher confirms and
  finite timeouts.
- `QueueConsumer` replaces the copied queue consumers of ledger and order: manual
  acknowledgements after the local commit, one prefetched delivery and reconnects
  with backoff and jitter.
- Both classes use `BrokerSettings`. Common declares the RabbitMQ client as an
  optional dependency, thus only order and ledger, which declare the client
  themselves, contain it.

## Resource ownership

Common manages no database connections: each application owns its pools,
endpoints, credentials and configuration, and the shared PostgreSQL code runs on
connections of these pools. Each application also owns its broker settings and
credentials; a publisher opens and closes only the connection of its own stream.

## HTTP deadline filters

The HTTP deadline filters of product and order stay in the applications, because
they need Vert.x, JAX-RS and CDI wiring. Their concurrency rule is common to both:

The deadline filters synchronize only their request-state decisions. Response
status reads, writes, connection closing and timer cancellation run outside that
monitor: Vert.x can invoke response-end callbacks while holding its connection
lock. A timeout claims the state before accessing transport, so a late writer is
suppressed; if a writer already claimed the response, the timeout closes the
connection instead of writing another response. Completion marks the state
finished before cancelling the timer, and a queued timeout then does nothing.

## Related contracts

The [core boundaries](../ARCHITECTURE.md) define dependency enforcement,
application-owned models and common release qualification. The
[identifier contract](../idgen/ARCHITECTURE.md#identifier-format-and-validation)
defines canonical ID validation; the [routing contract](../sharding/ARCHITECTURE.md#version-2-routing-and-topology)
defines digest use.
