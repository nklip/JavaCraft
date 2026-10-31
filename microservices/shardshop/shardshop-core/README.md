# ShardShop core

[ShardShop](../README.md) › **Core**

The core aggregator defines the Maven modules of ShardShop, their dependency rules, the ownership of
application IDs and the wire contracts between the services. It has no process to start and no monitor of
its own. Each application packages the libraries that it uses, and the applications report their state.

| Library | Runs inside | Document |
|---|---|---|
| `shardshop-common` | All six applications | [Common](common/README.md) |
| `shardshop-idgen` | Product and order | [ID generation](idgen/README.md) |
| `shardshop-sharding` | Product and order | [Shard routing](sharding/README.md) |
| `shardshop-workload-control` | The three workloads | [Workload control](workload-control/README.md) |

Related: the [workload module](../shardshop-workload/README.md) and the
[database layer](../database/ARCHITECTURE.md), which owns all schemas.

## Design

### Goal and module boundaries

The lab generates product writes, heavy product reads and orders against sharded, replicated PostgreSQL.
Change data capture (CDC) captures committed order writes. RabbitMQ passes their immutable order information
to `shardshop-ledger`, which records it in a separate ledger database as part of the saga.

RabbitMQ is the message transport. Debezium reads the PostgreSQL write-ahead log (WAL), not RabbitMQ.
See [Debezium Server's RabbitMQ sink](https://debezium.io/documentation/reference/stable/operations/debezium-server.html#debezium-server-rabbitmq-stream-sink-configuration).
This design embeds Debezium Engine in the order relay. Thus the relay keeps the publication bookkeeping and the
[mandatory-routing protocol](../shardshop-order/ARCHITECTURE.md#cdc-from-shard-writes-to-the-ledger).
Kafka and a separate Debezium Server deployment are not necessary.

There are six top-level Maven modules and twelve Java modules: six deployable applications, five library
JARs and the test-only `shardshop-testing` module.

| Module | Responsibility | Deployment | Database access |
|---|---|---|---|
| `shardshop-workload` | Aggregate the [three workload applications](../shardshop-workload/README.md) | No aggregator pod. One seeder Job, two load Deployments and one interactive seeder Deployment | None: no tables, migrations or credentials |
| `shardshop-product` | Accept seller and product creation and product reader HTTP requests | Two identical pods behind one Kubernetes Service | Creates and reads sellers and products in schema `catalog` |
| `shardshop-order` | Accept buyer and order HTTP requests, coordinate the saga including stock reservation, relay captured order-outbox inserts to RabbitMQ | Two stateless HTTP pods and one saga pod | Buyers, orders, items, saga state, inbox, outbox and CDC offsets in schema `ordering`. Reads sellers and products, and writes stock reservations and product stock, in `catalog` |
| `shardshop-ledger` | Consume ledger commands and persist order ledger records | One consumer pod and a separate PostgreSQL pod | Schema `ledger` in a dedicated database: entries, decisions, result bindings, inbox, outbox, command quarantine |
| `shardshop-core` | Aggregate `common` (canonical-ID parsing, UTF-8 SHA-256, bounded JSON HTTP client), `idgen` (bounded Snowflake generation), `sharding` (seller/buyer routing) and `workload-control` (pacing, control API, page assets) | No pod. Common is in all six applications, idgen and sharding in product and order, workload-control in the three workloads | None |
| `shardshop-testing` | Cucumber scenarios that start the packaged applications against a PostgreSQL test container and a k3s Kubernetes API server | Not deployed. Runs only in Maven builds with `-Pintegration` | Its own test databases, migrated by Flyway with the lab roles |

Product also handles seller and product creation, so the product seeder can fill the shared database through
an application API. The ledger has a consumer process because the ledger database does not consume RabbitMQ
messages.

No module owns a database schema or ships migrations. Schemas, roles and migrations belong to the
[database layer](../database/ARCHITECTURE.md#database-change-management), and each service connects with its
own login role. PostgreSQL and RabbitMQ are supporting infrastructure.

### Dependency rules

| Library | Used by | Never used by |
|---|---|---|
| `shardshop-common` | All six applications and `shardshop-sharding` | — |
| `shardshop-idgen` | Product and order | Common, sharding, ledger, workloads |
| `shardshop-sharding` | Product and order | Common, idgen, ledger, workloads |
| `shardshop-workload-control` | The three workload applications | All other modules |
| `shardshop-datasets` | The three workload applications | Services and core modules |

- `shardshop-core` is a POM parent and aggregator for `common`, `idgen`, `sharding` and `workload-control`.
  The workload module is a POM aggregator for three applications and the ID-free `shardshop-datasets` library.
- Idgen and sharding each own their Quarkus wiring, and their domain classes have no framework imports.
  They do not depend on one another. Idgen also reserves the generator ID of each product and order JVM at startup.
- Common does not depend on idgen or sharding. Common, ledger and workloads receive no shard-routing classes,
  generator classes or Snowflake.
- Maven Enforcer rejects direct (also optional) and transitive idgen, sharding and Snowflake dependencies in
  unauthorized consumers. Idgen skips the general ID-ownership rules to use Snowflake, but separate Enforcer
  rules forbid common and sharding in idgen.
- Maven Enforcer rejects `shardshop-workload-control` and the datasets in every module other than the three
  workloads.
- `shardshop-testing` has no main classes and no runtime dependency on another module.
- Compute golden digests, shards and currency boundaries independently of the implementation. Never regenerate
  expected values with the code under test.

#### Application ownership

Applications own their HTTP APIs, DTO and model classes, wire documents, document decoding, business validation,
persistence types and service-specific tests. They use the shared utilities and do not depend on one another.
Common contains no datasets or service wire models. The workload-owned dataset library holds ID-free
definitions, and each application owns its response decoding and request construction. The seller-country
`CurrencyPolicy` is in common. The buyer-region currency mix stays in the order producer.

Common changes must pass its tests and all six application builds before release. Each executable packages its
own copy of common, so a running application adopts a change when its rebuilt artifact is deployed. Thus wire-compatible
changes do not need a simultaneous deployment.

### ID ownership

Only `shardshop-product` and `shardshop-order` create application IDs. Workloads and ledger only consume and
reuse service-issued IDs. The [identifier contract](idgen/README.md#design) defines the format, the generator
and the allocation of generator IDs.

| Service | Creates |
|---|---|
| Product | Seller and product IDs |
| Order | Buyer, order, saga, command and message IDs, including IDs reserved for ledger results |
| Ledger and workloads | No IDs |

### Wire contracts

Self-contained wire documents define the service contracts. Each is packaged under the `contracts/` classpath
directory of its owner. Applications do not import the schemas or models of another module, and clients keep
their own DTOs.

- [Product OpenAPI](../shardshop-product/src/main/resources/contracts/openapi.yaml): seller and product
  creation and read operations, and lookup by creation key.
- [Order OpenAPI](../shardshop-order/src/main/resources/contracts/openapi.yaml): buyer creation, durable order
  allocations and order status.
- [RecordOrder schema](../shardshop-order/src/main/resources/contracts/record-order.schema.json): the outgoing
  order command and the immutable catalog snapshot.
- [Ledger result schema](../shardshop-ledger/src/main/resources/contracts/ledger-result.schema.json): the
  outgoing recorded and rejected outcomes.

OpenAPI 3.1.1 and JSON Schema 2020-12 define shapes and bounds. Provider tests enforce issuance, arithmetic,
stored-state and cross-envelope relationships.

#### Creation and entities

- Creation of sellers, products and buyers uses POST with ID-free payloads and a stable `Idempotency-Key`
  header. Product and order generate the IDs and return them. Read-only lookups by creation key recover catalog IDs.
- Dataset versions and definitions stay inside the workload modules. The services have no dataset API.
- Sellers are companies with `companyName`, `region`, `countryCode` and read-only `profitsEarned` currency
  balances. Products add `description` and `unitCost` to name, price, currency and stock. Buyers are people with
  `firstName`, `surname`, `email`, `phone`, `address` (`line1`, `city`, `postalCode`, `countryCode`) and `region`.
- Creation accepts only the immutable payload. Workloads never supply or reset seller balances.
- Prices and costs are nonnegative fixed-two-decimal strings. Profit balances are signed canonical
  fixed-two-decimal strings, without negative zero.
- IDs are strings in `1..9223372036854775807`.
- Allocation accepts `runName` and a string `requestOrdinal` in `0..9223372036854775807`. It returns the same
  persisted order ID on retries and restarts. The ordinal is not an application ID. It permits `"0"`, which
  `IdParser` rejects, so the order provider validates this value separately.
- An order has at most 1,000 distinct seller and product pairs. Duplicate pairs are invalid, and item order
  changes the normalized fingerprint. The order API specifies its exact SHA-256 serialization, normalization,
  validation precedence and retries.

#### Messages and timestamps

- The message field is `requestFingerprint`. The envelope `occurredAt` is different from the permanent
  `decidedAt` of the ledger. Result publication uses routing key `order.ledger-results` on
  `order.saga-results`. A same-ID result replay keeps both timestamps and the exact stored envelope.
- Message timestamps use uppercase UTC `T` and `Z` with at most six fractional digits. Truncate clock instants
  to microseconds before persistence and before envelope construction. Use the same instant for the decision
  row, the ledger entry and the initial result. This matches PostgreSQL `TIMESTAMPTZ` precision.
- Product store failures and unknown commits return `503 CATALOG_UNAVAILABLE`. Strict replica failures return
  `503 READ_REPLICA_UNAVAILABLE`.

#### Text and number validation

- Reject NUL and unpaired UTF-16 surrogates in company and person names, descriptions, address text and run
  names before IO. Correctly paired supplementary Unicode characters are valid.
- Integer-valued JSON numbers such as `2`, `2.0` and `2e0` normalize to the same integer. Validate exact
  decimal values and ranges before conversion, so `2.5` never truncates to `2`. Fingerprints serialize
  normalized quantities as base-10 integers.
- Providers enforce this at JSON decoding: unknown and duplicate object members, and numeric tokens in string
  fields, are errors. The default mapper coercion is not sufficient.

## Operation

### HTTP and message contracts

| Owner | Contract |
|---|---|
| Product | [OpenAPI](../shardshop-product/src/main/resources/contracts/openapi.yaml): seller and product POST and GET, with lookup by creation key |
| Order | [OpenAPI](../shardshop-order/src/main/resources/contracts/openapi.yaml): buyer POST and GET, order-ID allocation, order PUT and status GET |
| Order | [RecordOrder JSON Schema](../shardshop-order/src/main/resources/contracts/record-order.schema.json) and [command example](../shardshop-order/src/main/resources/contracts/examples/record-order.json) |
| Ledger | [Result JSON Schema](../shardshop-ledger/src/main/resources/contracts/ledger-result.schema.json), [recorded example](../shardshop-ledger/src/main/resources/contracts/examples/ledger-recorded.json) and [rejected example](../shardshop-ledger/src/main/resources/contracts/examples/ledger-rejected.json) |

HTTP examples are inline. The two result examples show alternative decisions for one example command, never two
outcomes for one retained order. Example IDs show the format and are not an issued dataset. Workloads select a
pinned local dataset version, send its ID-free payloads with stable `Idempotency-Key` values and keep the
returned ID strings.

The product HTTP, order HTTP, v1 command and ledger result examples describe one EUR purchase from an EU seller
in France. Seller and buyer regions match the routing rule. The separate
[JPY v2 command example](../shardshop-order/src/main/resources/contracts/examples/record-order-jpy.json) uses
distinct identities for a US buyer who purchases from a Japanese seller routed to ASIA.

Providers decode strictly before they construct typed DTOs or touch storage. Enable unknown-property rejection
and duplicate-member detection, require JSON string tokens for string fields, and validate integral numbers
exactly before conversion. Contract and provider tests must include raw JSON with duplicate keys, numeric IDs,
`2.0`, `2e0`, `2.5`, NUL and unpaired surrogates.

### Contract checks

Each module that owns a contract checks it in a Maven unit test, without infrastructure: `ProductApiContractTest`,
`OrderApiContractTest`, `RecordOrderContractTest` and `LedgerResultContractTest`. They check:

- the document: the bundled OpenAPI 3.1 schema or the JSON Schema 2020-12 meta-schema, each schema object,
  in-document references, path parameters and unique operation IDs;
- every inline example, also in referenced responses, and an example for each response body;
- the boundaries of IDs, status URLs, money, text, timestamps, integers and the creation and response objects;
- the rules inside the module: the order fingerprint, the snapshot total, the reserved IDs and their times,
  the ledger decision fields, and the service-issued ID and home-region rules.

`Contracts.feature` of `shardshop-testing` checks what connects the contracts of different modules, and the
creation payloads of the workload datasets
([integration tests](../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)). Both use
`ContractDocument` and `ExampleIds` from the test JAR of `shardshop-common`, and validate with the
platform-managed Vert.x JSON Schema library.
[VERSIONS.md](../VERSIONS.md#review-log) records its qualification and the gaps that
the helper corrects.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

Packaged routing startup checks run in the default Maven `verify` lifecycle.
