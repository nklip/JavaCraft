# Core architecture

[ShardShop](../ARCHITECTURE.md) › **Core** · [README](README.md) · [Runbook](RUNBOOK.md) · [Implementation plan](../PLAN.md)

The core aggregator defines the Maven modules of ShardShop, their dependency rules,
the ownership of application IDs and the wire contracts between the services. Its
four libraries have their own documents.

- **Libraries:** [common utilities](common/ARCHITECTURE.md),
  [identifier generation](idgen/ARCHITECTURE.md), [shard routing](sharding/ARCHITECTURE.md)
  and [workload control](workload-control/ARCHITECTURE.md).
- **Runs as:** no pod of its own. Each application packages the libraries that it uses.
- **Related:** the [workload architecture](../shardshop-workload/ARCHITECTURE.md) and
  the [database layer](../database/ARCHITECTURE.md), which owns all schemas.

## Goal and module boundaries

Build a Kubernetes lab that generates product writes, heavy product reads, and
orders against sharded, replicated PostgreSQL. Capture committed order writes
using change data capture (CDC) and pass their immutable order information through
RabbitMQ to `shardshop-ledger`, which records it in a separate ledger database as
part of the saga.

RabbitMQ supports this pipeline as the message transport: Debezium performs the
database capture, and its official RabbitMQ sink demonstrates the supported
integration. RabbitMQ itself does not read PostgreSQL's write-ahead log (WAL).
See [Debezium Server's RabbitMQ sink](https://debezium.io/documentation/reference/stable/operations/debezium-server.html#debezium-server-rabbitmq-stream-sink-configuration).
This design embeds Debezium Engine in the existing order relay so it can retain
the publication bookkeeping and [mandatory-routing protocol](../shardshop-order/ARCHITECTURE.md#cdc-from-shard-writes-to-the-ledger);
Kafka and a separate Debezium Server deployment are not required.

### Modules

There are six top-level Maven modules and twelve Java modules: six deployable
applications, five library JARs and the test-only `shardshop-testing` module.

| Module | Responsibility | Deployment | Database access |
|---|---|---|---|
| `shardshop-workload` | Aggregate the [three workload applications](../shardshop-workload/ARCHITECTURE.md) | No aggregator pod; one seeder Job, two load Deployments and one interactive seeder Deployment | None: no tables, migrations, or database credentials |
| `shardshop-product` | Accept seller and product creation and product reader HTTP requests | Two identical product service pods behind one Kubernetes Service | Creates and reads sellers and products in schema `catalog` of the shared sharded PostgreSQL deployment |
| `shardshop-order` | Accept buyer and order HTTP requests, coordinate the saga including stock reservation, and relay captured order-outbox inserts to RabbitMQ | Two stateless HTTP pods and one separate saga pod | Buyers, orders, items, saga state, inbox, outbox, and CDC offsets in schema `ordering`; reads sellers and products and writes stock reservations and product stock in `catalog` |
| `shardshop-ledger` | Consume ledger commands and persist order ledger records | One ledger consumer pod plus a separate PostgreSQL pod initially | Schema `ledger` in a dedicated ledger database: entries, decisions, permanent result bindings, inbox, outbox, and command quarantine |
| `shardshop-core` | Aggregate `common` (canonical-ID parsing, UTF-8 SHA-256 and the bounded JSON HTTP client), `idgen` (bounded Snowflake generation), `sharding` (seller/buyer routing) and `workload-control` (live workload pacing, control API and page assets); idgen and sharding own their SmallRye/CDI wiring | No aggregator pod; common is inside all six applications, idgen and sharding are inside product/order, workload-control is inside the three workloads | None |
| `shardshop-testing` | Cucumber integration scenarios that start the packaged applications against a PostgreSQL test container and a k3s Kubernetes API server | Not deployed; runs only in Maven builds with `-Pintegration` | Its own test databases, migrated by Flyway with the lab's roles |

Product also handles seller and product creation so the product seeder can populate the
shared database through an application API. Ledger includes a consumer process:
the ledger database itself does not consume RabbitMQ messages.

No module owns a database schema or ships migrations: schemas, roles and
migrations belong to the [database layer](../database/ARCHITECTURE.md#database-change-management),
and each service connects with its own login role. PostgreSQL and RabbitMQ are
supporting infrastructure. The [planned layout](../PLAN.md#8-planned-layout-and-verification)
in PLAN shows the source tree.

## Dependency rules

| Library | Used by | Never used by |
|---|---|---|
| `shardshop-common` | All six applications and `shardshop-sharding` | — |
| `shardshop-idgen` | Product and order | Common, sharding, ledger, workloads |
| `shardshop-sharding` | Product and order | Common, idgen, ledger, workloads |
| `shardshop-workload-control` | The three workload applications | All other modules |
| `shardshop-datasets` | The three workload applications | Services and core modules |

- `shardshop-core` is a POM parent and aggregator for `common`, `idgen`, `sharding`
  and `workload-control`. The workload module is a POM aggregator that contains
  three applications and the ID-free `shardshop-datasets` library; product, order
  and ledger are the other three applications.
- Idgen and sharding each own their Quarkus wiring, while their domain classes
  remain free of framework imports. They do not depend on one another. All three
  libraries are ordinary Java JARs with no independent service; idgen also reserves
  the generator ID of each product/order JVM at startup.
- Common has no dependency back to idgen or sharding; common, ledger and workloads
  receive no shard-routing classes, generator classes or Snowflake.
- Maven Enforcer rejects direct (including optional) and transitive idgen, sharding
  and Snowflake dependencies in unauthorized consumers, including ledger and
  workloads. Idgen's separate direct and transitive Enforcer rules forbid common and
  sharding even though idgen skips the general ID-ownership rules to use Snowflake.
  Direct optional dependencies are also checked. Maven Enforcer rejects
  `shardshop-workload-control`, like the datasets, in every module other than the
  three workloads ([traffic control](workload-control/ARCHITECTURE.md)).
- `shardshop-testing` has no main classes and no runtime dependency on another
  module; its Cucumber scenarios start the packaged applications.
- Golden digests, shards and currency boundaries are computed independently of the
  implementation; never regenerate expected values with the code under test.

### Application ownership

Applications own their HTTP APIs, DTO/model classes, wire documents, document
decoding, business validation, persistence types and service-specific tests.
They use the shared utilities without depending on one another. Common contains no
datasets or service wire models: the workload-owned dataset library holds ID-free
definitions, and each application owns its service response decoding and request
construction. The seller-country `CurrencyPolicy` lives in common; the buyer-region
currency mix stays in the order producer. Catalog reads and stock reservations
retain their explicit database grants.

Common changes must pass its tests and all six application builds before release.
Each executable packages its own copy of common; running applications adopt a
common change when their rebuilt artifacts are deployed. This couples release
qualification without introducing a shared runtime service or requiring
simultaneous deployment for wire-compatible changes.

## ID ownership

Only `shardshop-product` and `shardshop-order` create application IDs. Workload
modules and ledger only consume and reuse service-issued IDs; they never generate,
derive, or allocate them locally.

| Service | Creates |
|---|---|
| Product | Seller and product IDs |
| Order | Buyer, order, saga, command, and message IDs, including IDs reserved for ledger results |
| Ledger and workloads | No IDs |

The [identifier contract](idgen/ARCHITECTURE.md) defines the format, the generator
and the allocation of generator IDs.

## Wire contracts

The owner-local, self-contained wire documents define the service contracts:

- [Product OpenAPI](../shardshop-product/src/main/resources/contracts/openapi.yaml):
  seller/product creation/read operations and lookup by creation key.
- [Order OpenAPI](../shardshop-order/src/main/resources/contracts/openapi.yaml):
  buyer creation, durable order allocations and order status.
- [RecordOrder schema](../shardshop-order/src/main/resources/contracts/record-order.schema.json):
  order's outgoing command and immutable catalog snapshot.
- [Ledger result schema](../shardshop-ledger/src/main/resources/contracts/ledger-result.schema.json):
  ledger's outgoing recorded/rejected outcomes.

OpenAPI 3.1.1 and JSON Schema 2020-12 define shapes and bounds; the provider tests
of the implementing steps enforce issuance, arithmetic, stored-state and
cross-envelope relationships. These documents are not shared runtime files or
generated client models. Clients retain their own DTOs and document decoding.

### Creation and entities

- Seller/product/buyer creation uses POST with ID-free payloads and a stable
  `Idempotency-Key` header. Product/order generate the IDs and return them in
  entity responses. Read-only lookups by creation key recover catalog IDs.
- Dataset versions and definitions stay inside workload modules; services expose
  no dataset API.
- Sellers are companies with `companyName`, `region`, `countryCode` and read-only
  `profitsEarned` currency balances. Products add `description` and `unitCost` to
  their name, price, currency and stock fields. Buyers are people with `firstName`,
  `surname`, `email`, `phone`, `address` (`line1`, `city`, `postalCode`,
  `countryCode`) and `region`.
- Creation accepts only the immutable payload; seller balances are service-owned
  and never supplied or reset by workloads.
- Prices/costs are nonnegative fixed-two-decimal strings; profit balances are
  signed canonical fixed-two-decimal strings, excluding negative zero.
- Allocation `requestOrdinal` is a nonnegative decimal string through the
  signed-long maximum, preserving retry-coordinate precision without making it an
  application ID. It permits `"0"`, which `IdParser` rejects; the order provider
  validates that additional value separately.
- Orders allow at most 1,000 distinct seller/product pairs; duplicate pairs are
  invalid, and item order participates in the normalized fingerprint. The order
  API specifies its exact SHA-256 serialization.

### Messages and timestamps

- The message field is `requestFingerprint`; envelope `occurredAt` is distinct
  from the ledger's permanent `decidedAt`. Result publication uses routing key
  `order.ledger-results` on `order.saga-results`. Each same-ID result replay
  preserves both timestamps.
- Message timestamps use uppercase UTC `T`/`Z` with at most six fractional digits.
  Truncate clock instants to microseconds before persisting or constructing
  envelopes, and use that same instant for the decision row, ledger entry and
  initial result.
- Reconciliation reads the retained decision instant; same-ID replay reads the
  exact stored envelope. This matches PostgreSQL `TIMESTAMPTZ` precision without
  rounding a published nanosecond instant into a different replay instant.
- Product store failures and unknown commits return `503 CATALOG_UNAVAILABLE`;
  strict replica failures use `503 READ_REPLICA_UNAVAILABLE`.

### Text and number validation

- Reject NUL and unpaired UTF-16 surrogates in company/person names, descriptions,
  address text and run names before IO; correctly paired supplementary Unicode
  characters remain valid.
- Integer-valued JSON numbers such as `2`, `2.0` and `2e0` normalize to the same
  integer. Validate exact decimal values and ranges before conversion: `2.5` must
  never truncate to `2`. Fingerprints serialize normalized quantities as base-10
  integers.
- Providers enforce this contract at JSON decoding, including unknown/duplicate
  object members and numeric tokens in string fields; default mapper coercion is
  insufficient.
