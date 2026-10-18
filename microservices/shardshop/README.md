# ShardShop

[Architecture](ARCHITECTURE.md) · [Implementation plan](PLAN.md) ·
[Version policy](VERSIONS.md) · [Artifact lock](versions.lock.yaml) ·
[Changelog](CHANGELOG.md)

All six applications use **Quarkus 3.40.1** in JVM mode on Java 25.
Product now runs the seller/product HTTP API from step 3.6. The product seeder
from step 3.7 seeds and verifies the catalog through that API and then exits. The
product reader from step 3.8 reads the seeded products at a bounded rate until it
stops. Order now serves buyer creation, durable order allocation and order PUT/GET
from step 4.1, and its step 4.2 coordinator reserves stock, compensates rejected
orders and writes the ledger command outbox. Step 4.4 embeds one Debezium reader
per shard to deliver those envelopes to RabbitMQ with durable JDBC checkpoints.
Ledger consumes those commands in step 4.5, committing immutable decisions and
pending result outboxes. Its result publisher remains step 4.6. The order producer
still starts CDI and exits. Step 3.5
provides workload-owned dataset definitions and HTTP clients; the order workload
loop remains step 4.8. Maven module tests need no Docker or
Kubernetes; chart rendering and release orchestration tests require `helm` and
`helmfile` on `PATH`. The Cucumber scenarios of `shardshop-testing` run product, the seeder
and the reader against a PostgreSQL container in the `integration` profile
([step 3.10](#integration-tests-with-testcontainers-step-310)). Step 3.9 deploys product, the seeder Job and
the reader on the kind lab. The reader starts its load only after product serves
the complete dataset ([seeding gate](#product-deployment-and-seeding-gate-step-39)).
Product/order production
starts still require the routing snapshot and a live generator ID, which the JVM
reserves through the Kubernetes API at each start; tests use a fixed test ID or a
k3s API server.

| Maven module | Quarkus application class |
|---|---|
| `shardshop-product` | `dev.nklip.javacraft.shardshop.product.ProductApplication` |
| `shardshop-order` | `dev.nklip.javacraft.shardshop.order.OrderApplication` |
| `shardshop-ledger` | `dev.nklip.javacraft.shardshop.ledger.LedgerApplication` |
| `shardshop-workload/shardshop-product-seeder` | `dev.nklip.javacraft.shardshop.workload.seeder.ProductSeederApplication` |
| `shardshop-workload/shardshop-product-reader` | `dev.nklip.javacraft.shardshop.workload.reader.ProductReaderApplication` |
| `shardshop-workload/shardshop-order-producer` | `dev.nklip.javacraft.shardshop.workload.producer.OrderProducerApplication` |

The ShardShop parent has six children and eleven Java modules: six applications,
four library JARs and the test-only `shardshop-testing` module. `shardshop-core`
aggregates `common`, `idgen` and `sharding`.
Common provides canonical ID parsing, SHA-256 and bounded JSON HTTP transport;
it contains no dataset definitions or service wire models. Only product/order
use idgen and sharding; their plain domain classes and Quarkus configuration
remain separate. Workloads and ledger have no Snowflake dependency, generator
allocation or allocator access. Only product/order create entity and message IDs.

`shardshop-workload` aggregates the three applications and the pure Java
`shardshop-datasets` library. This library contains **all workload datasets** and
has no runtime dependencies. Only workload applications consume it. Product,
order, ledger and the core libraries do not package dataset classes or resources.
Applications do not depend on one another and own their service request/response
models. All six still depend on common directly.

The regional model assigns `shard-a=US`, `shard-b=EU`, and `shard-c=ASIA`.
Seller/buyer creation requests specify an immutable home region. The service
generates an ID whose route matches that region; products inherit their seller's
region. Buyers may purchase across regions under the currency and stock rules.
There are no service dataset endpoints or fixture-ID whitelists.

## Workload-owned datasets and service-issued IDs (step 3.5)

All definitions and source data live in
[`shardshop-workload/shardshop-datasets`](shardshop-workload/shardshop-datasets).
`CatalogDataset("catalog-v1")` and `BuyerDataset("buyers-v1")` expose immutable,
ID-free definitions. Regional TSV resources are under
`src/main/resources/datasets/catalog-v1/{US,EU,ASIA}.tsv` and
`src/main/resources/datasets/buyers-v1/{US,EU,ASIA}.tsv`. Both datasets are the
initial unreleased v1; edits before their first release or use keep that version.
Unsupported versions fail locally before any HTTP request.

| Definition | Per region | Total | Payload |
|---|---|---|---|
| Company sellers | 50 | 150 | Published company names and immutable home region |
| Products | 100 | 300 | Two synthetic products per seller, USD then EUR; name, description, price `19.95`, unit cost `12.50`, initial stock 1000 |
| Person buyers | 1000 | 3000 | First name, surname, email, phone, postal address and immutable home region |

The regional coverage is US, the EU27 single market, and an ASIA dataset limited
to China, Vietnam, South Korea and Japan. Company selection uses published brand
value as a popularity proxy, with company deduplication; it is not a ranking of
sales or all companies. Name/surname inputs, country coverage, company selection
and source limitations are recorded in
[`SOURCES.md`](shardshop-workload/shardshop-datasets/SOURCES.md). Buyer profiles and
contact details are synthetic. Emails use normalized ASCII
`firstName.surname` at the reserved domains `example.com`, `example.net` or
`example.org`. Every region reuses numbers from the fictional NANPA range
`+12025550100`–`+12025550199`; these numbers do not represent local phone formats.
Phone numbers and addresses are fixed in the resources so retries reproduce
the same payload. ASIA name pools use MIT-licensed Faker lists for the four
countries; these plausible names are not a population-frequency ranking.

Use `CatalogDataset.sellersByRegion()` and `BuyerDataset.buyersByRegion()` to
select regional definitions. `CatalogDataset.productsBySeller()` groups products
by the local seller key. Retry keys include the version, region, entity type and
regional ordinal, such as `catalog-v1.US.seller.1`, `catalog-v1.US.product.1` and
`buyers-v1.US.buyer.1`. These groups do not generate or select entity IDs.

A product definition references its local `sellerKey`, which is resolved to the
seller ID returned by product before sending its creation request. These keys
are **retry coordinates, not IDs**: workloads never turn them into Snowflakes or
hash them to construct an entity ID. After a dataset has been released or used,
changing a definition's payload requires a new key/version; a restart reuses its
original key and exact payload.

The clients send `POST /api/v1/sellers`,
`POST /api/v1/sellers/{returnedSellerId}/products`, and `POST /api/v1/buyers`.
`Idempotency-Key` carries the definition's stable key. The service generates and
persists the entity ID before returning `201`; a retry returns `200` with the
same ID. A conflicting payload under that scope/key returns `409`. Seller/buyer
keys are scoped by requested region; product keys are scoped by parent seller ID.
Product's step 3.6 provider persists these keys in the catalog. Order's step 4.1
provider persists buyer creation keys in ordering, including recovery after restart.

Seller requests contain `companyName` and `region`. Seller responses also include
service-owned `profitsEarned` balances by currency, starting with `USD: "0.00"`
and `EUR: "0.00"`. Additional currency codes are allowed. Profit means confirmed
sales margin: `(unitPrice - unitCost) × quantity`, using stored order-item
snapshots. Losses use negative amounts; currencies are never added together.
Retries return current profits and never reset them. Workloads neither send nor
calculate seller balances. Product requests include `description` and `unitCost`;
products remain grouped by their returned seller ID. Buyer requests include
`firstName`, `surname`, `email`, `phone`, and `address` with `line1`, `city`,
`postalCode` and `countryCode`.

Existing catalog/ordering V1 migrations remain immutable. Step 3.6 adds catalog
creation fields, durable keys and profit storage through V2. Step 4.1 adds ordering
fields and unit-cost snapshots through its own V2; confirmed-item profit crediting remains step
4.7. Pending, reserved, cancelled or rejected orders earn no profit.

The seeder retains returned seller IDs while creating products. Reader and order
producer resolve catalog IDs through ordinary read-only resource lookups:
`GET /api/v1/sellers/by-key/{creationKey}?region=US` and
`GET /api/v1/sellers/{sellerId}/products/by-key/{creationKey}`. The services look
up persisted entities and do not hold or generate workload datasets. Reader GETs
use the recovered seller/product IDs. The workloads start their load only after
these lookups succeed, thus no load runs against missing resources. Buyer creation retries similarly recover the same buyer ID.
Live order IDs continue to come from order's durable allocation API.

`SeederDatasets`, `ReaderDatasets` and `ProducerDatasets` use the local definitions,
send creation/lookup requests, validate returned payloads and canonical string IDs,
and preserve those IDs unchanged. Common's transport bounds response bytes and
the complete response deadline, rejects invalid UTF-8/duplicate JSON properties,
and cancels stalled or interrupted requests. Workload tests use recorded responses
with arbitrary large IDs, including values above 2^53, rather than deriving IDs.
Rejected responses throw `HttpResponseException` with `statusCode()` and an
optional validated `errorCode()`. Malformed error bodies preserve the status;
exception messages contain no response payload. Callers can distinguish a missing
resource, a rejected request and a retryable service failure; retry policy stays
with the workload.
Steps 3.7 and 3.8 added the seeder and reader loops. The order-producer loop and
the buyer/order providers remain later plan steps.

After a change to common, run the
[six-application audit](#dependency-and-test-validation).

Maven Enforcer rejects direct, optional and transitive dataset dependencies
outside workloads. `DatasetSourcesTest` checks the retained name pools, the
name/email correspondence of the buyers, the regional coverage and the retained
source hashes. `Contracts.feature` of `shardshop-testing` checks that all 150 seller,
300 product and 3000 buyer creation payloads match the product and order contracts
([contract checks](#http-and-message-contracts-step-31)):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-datasets -am clean verify
```

Source notices and licenses ship in the dataset JAR under
`META-INF/shardshop-datasets`. Name pools use US Census public-domain data,
CC BY 4.0 Onomaverse data for EU, and MIT-licensed Faker lists for ASIA; see the
[source terms and ranking limitations](shardshop-workload/shardshop-datasets/SOURCES.md).

## Offline buyer dataset generator (step 3.19)

The development-only Java `BuyerDatasetGenerator` reproduces all three frozen
`buyers-v1` TSVs byte for byte from the retained source pools, without network
calls or entity IDs. It lives in test sources and is excluded from production
JARs. Run this explicit Maven command with `JAVA_HOME` pointing to JDK 25 or newer:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml \
  test-compile exec:exec@regenerate-buyers
```

Outputs are fixed under the dataset module's `target/generated-datasets/buyers-v1/`.
Ordinary builds and lifecycle commands never overwrite source fixtures. The tests
compare generated bytes with all three checked-in files; add `-o` to Maven after
its dependencies are cached for an entirely offline run. See the dataset
[`SOURCES.md`](shardshop-workload/shardshop-datasets/SOURCES.md) for provenance and
comparison commands. The former Python generator has been removed.

## Seller and product provider (step 3.6)

Product serves the six seller/product POST and GET routes in its
[OpenAPI contract](shardshop-product/src/main/resources/contracts/openapi.yaml).
Creation returns 201; an identical retry returns 200 with the same ID and current
profits or stock. Conflicting payloads return 409. Missing product parents return
422; missing GET resources return 404. Invalid wire input returns 400 before any
database access, including POST bodies exceeding the 32 KiB limit, which return the
JSON error `400 INVALID_REQUEST`. SQL failures, deadline exhaustion and uncertain commits return
503: retry creation with the original key, path and payload.

Seller keys choose a fixed shard among the inventory's ordered shards in the
requested region: unsigned SHA-256 of UTF-8 `seller\0region\0key`, modulo the
regional shard count (`\0` denotes one NUL byte). The service generates at most
1024 ID candidates until the existing version-2 ID router chooses that shard.
This works when several shards share a region. Keep inventory membership, order
and region assignments immutable while retaining data. Product IDs are generated
once, after parent validation, and stored on the parent's shard.

Each creation transaction takes a PostgreSQL transaction advisory lock derived
from the collection, scope and key before checking the persisted mapping. The
database uniqueness constraint and transaction retain the entity/key together;
retries and process restarts do not depend on in-memory state. The first seller
transaction also creates USD/EUR profit rows at the database's zero default.

Run the normal external migration workflow before starting the API. The additive
`V2__catalog_creation.sql` preserves V1, exposes `company_name` from the existing
stored `name`, backfills descriptions/costs and profit rows, and adds key lookup
indexes and the per-order/product profit-credit identity. Product can initialize
zero balances but cannot update or reset them. Applying confirmed margin remains
step 4.7; step 4.2 implements stock mutation. No SQL functions or triggers are added.

Connection configuration is deployment-only. Defaults target each inventory
cluster's `-rw` and `-ro` Services in database `shardshop`, using `product_app` and
`sslmode=verify-full`. Mount each `<shard>-ca` Secret's `ca.crt` at
`/etc/shardshop/catalog/<shard>/ca.crt`; `root-certificate` can override that path.
Certificate trust and the Service hostname must both verify.
Supply passwords through an uncommitted external configuration/Secret; no
credentials belong in the repository. Per-shard overrides can be included beside
the routing snapshot loaded by `shardshop.routing.config`:

```properties
shardshop.catalog.connections.shard-a.primary-url=jdbc:postgresql://shard-a-rw:5432/shardshop
shardshop.catalog.connections.shard-a.replica-url=jdbc:postgresql://shard-a-ro:5432/shardshop
shardshop.catalog.connections.shard-a.root-certificate=/etc/shardshop/catalog/shard-a/ca.crt
shardshop.catalog.connections.shard-a.username=product_app
shardshop.catalog.connections.shard-a.password=${SHARD_A_PRODUCT_PASSWORD}
shardshop.catalog.max-pool-size=4
shardshop.catalog.max-id-candidates=1024
shardshop.catalog.read-profile=primary
```

Bare URL overrides also receive the secure TLS defaults. An explicit URL
`sslmode` overrides the default; use `sslmode=disable` only for a deliberate local
plaintext test. The step 3.10 scenarios keep `verify-full` with a test CA.
Repeat credentials/overrides for every shard. Unknown connection names fail
startup. `max-pool-size` is bounded to 1..32 per shard/endpoint, with no initial
connections; the default is four. Writes always use primaries. The optional
`replica` read profile uses only replica endpoints and returns
`READ_REPLICA_UNAVAILABLE` on failure, without primary fallback. Every read issues
SQL; there is no product cache. The complete database phase has a three-second
deadline, including pool acquisition, connection setup, locks and commit. HTTP
requests have a four-second deadline: before response serialization it returns
503; if output has already started, it closes the connection. Each phase runs on
its own virtual thread and waits at most one second for a pooled connection, thus
a burst queues; a pool that stays full gives 503 within the budget. Uncertain
writes are recovered through their durable key, without an internal write retry.
The phase engine, TLS defaults and strict request decoding are shared with order in
`shardshop-common`. `--check-startup` validates configuration and exits for packaged
startup checks; ordinary product startup waits for shutdown and serves HTTP.

Build product and run its scenarios of step 3.10:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

`CatalogMigration.feature`, `ProductApi.feature` and `ProductTransport.feature`
apply the catalog stream with Flyway, run the packaged product against four shard
databases (two US) over `verify-full` TLS, and check the stored rows. The
[integration tests](#integration-tests-with-testcontainers-step-310) section lists
their cases. HTTPS and CNPG primary promotion remain deployment/recovery milestones.

The scenarios do not apply migrations to the retained kind lab. Apply them there
with `up.sh` and its Helm migration hooks. Every invocation runs Flyway against
the actual history, including unchanged inputs; no pending versioned migrations
means a validated no-op. See the [migration runbook](#database-schemas-and-migrations-step-23).
For the Maven command, prepare `$snapshot` with the
[kind snapshot runbook](#kind-verification-snapshot-step-315) after migration:

```bash
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/up.sh # Flyway validates again; application pods restart with fresh generator IDs
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-topology
```

The [changelog](CHANGELOG.md#step-36) records whether the lab qualification has
actually passed; isolated checks alone do not complete it.

## Product seeder (step 3.7)

`shardshop-product-seeder` is the finite application for the seeder Job. It sends
the `catalog-v1` definitions to the product API in dataset order: each seller, and
then the USD and EUR products of that seller. Each POST has the definition key in
`Idempotency-Key`. Product requests use the seller ID from the seller response.
After all creations, the seeder reads each seller and product by its returned ID
and by its creation key. Each read must return the acknowledged ID and the dataset
payload. In the default product read profile, these GETs read the primaries.
Step 6.1 makes the seeder repeat a verification read that gets `404` for a limited
time, because replica-profile reads can lag.

The seeder keeps no local state. A new run sends the same keys and payloads again.
Product returns `200` with the stored ID for an entity that exists and creates only
the missing entities. Thus a run after a partial failure keeps the IDs, home regions
and payloads that the failed run received.

| Product result | Seeder action |
|---|---|
| `201` or `200` with a valid ID and the dataset payload | Keep the returned entity |
| `503`, a response timeout, or a refused or closed connection | Send the same request again after a backoff, up to `max-attempts` attempts |
| A different status, an invalid response, or a read with a different ID or payload | Stop the run at once |

The seeder exits with code 0 only after it created and verified all 150 sellers and
300 products. Then it logs one summary line:

```text
Seeded and verified catalog-v1 [US 50 sellers, 100 products {USD=50, EUR=50}; EU 50 sellers, 100 products {USD=50, EUR=50}; ASIA 50 sellers, 100 products {USD=50, EUR=50}], key-to-ID SHA-256 <hex>
```

The digest is the SHA-256 of one `key=sellerId` line per seller and one
`key=sellerId/productId` line per product, each with LF, in dataset order. Equal
digests show that two runs recovered the same IDs. A failure logs one error line
with the operation, the creation key, and the HTTP status and error code or the
failure type. Then the seeder exits with code 1. Log lines contain no response payloads.

| Property | Default | Purpose |
|---|---|---|
| `shardshop.seeder.product-url` | None. Startup fails without it | Base URL of the product Service |
| `shardshop.seeder.catalog-version` | `catalog-v1` | Dataset version. Other versions fail before HTTP |
| `shardshop.seeder.connect-timeout` | `2s` | HTTP/1.1 connection deadline |
| `shardshop.seeder.request-timeout` | `5s` | Complete deadline of one attempt, including the response body |
| `shardshop.seeder.max-attempts` | `5` | Attempts for each request, 1–10 |
| `shardshop.seeder.retry-backoff` | `500ms` | First delay. Each failed attempt doubles it |
| `shardshop.seeder.max-retry-backoff` | `5s` | Maximum delay |

With the defaults, one request stops after a maximum of approximately 33 seconds.
The Job deadline of 300 seconds limits the complete run. Environment
variables such as `SHARDSHOP_SEEDER_PRODUCT_URL` also set these properties.
`--check-startup` validates the configuration and exits without HTTP requests.
Step 3.9 runs the seeder as a Kubernetes Job, beside the reader that waits for its
data ([seeding gate](#seeding-gate)).

`ProductSeeder.feature` of [step 3.10](#integration-tests-with-testcontainers-step-310)
runs the packaged seeder against the packaged product and four shard databases
(two US). It does not connect to the kind lab. A database session holds the
advisory lock of `catalog-v1.EU.seller.10`, so the first seeder run fails with
`503` after 59 sellers and 118 products. After a product restart with a new
generator ID, a complete run must keep those rows unchanged. Two complete runs
must store identical rows and log the same digest. The scenario calculates the
expected payloads from the dataset TSVs and the digest from the stored IDs. It
also checks seller ID routes, creation-key homes, product colocation and zero
USD/EUR profits. A last run against product with unavailable replica reads must
fail and leave the rows unchanged:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

## Product reader (step 3.8)

`shardshop-product-reader` is the long-running application for the reader
Deployment. At startup, it looks up each `catalog-v1` seller by its creation key
and region. Then it looks up each product by its creation key under the returned
seller ID. Each response must agree with the dataset payload. The reader keeps the
returned IDs; it never generates or derives an ID. Then it logs one line:

```text
Resolved catalog-v1 [US 50 sellers, 100 products; EU 50 sellers, 100 products; ASIA 50 sellers, 100 products], key-to-ID SHA-256 <hex>
```

The digest uses the same lines as the [seeder digest](#product-seeder-step-37).
Equal digests show that the reader found the IDs that the seeder verified. While
an entity is missing or product is unavailable, the reader waits and repeats the
lookups, as the [seeding gate](#seeding-gate) defines. Another failed lookup, or
the end of the wait, logs one error line, and the reader exits with code 1 before
the load starts.

After the lookups, the workers read randomly selected products with
`GET /api/v1/sellers/{sellerId}/products/{productId}`. One shared schedule gives
the request starts a fixed total rate. After a stall, the reader does not send the
missed requests later. Each response must keep the issued IDs and the dataset
payload; only the stock can change.

The reader sends HTTP requests with the classic Apache HttpClient 5 client,
which the Quarkus platform manages. The JDK HTTP client has no setting for a
connection limit or a connection lifetime. The defaults agree with the
[HTTP connection policy](ARCHITECTURE.md#http-connection-policy):

| Item | Reader behavior |
|---|---|
| Pool | A strict pool of up to 16 HTTP/1.1 connections. Apache opens a connection only when no free connection exists, and uses free connections in FIFO order. After the first lifetime, the number of open connections follows the load: request rate multiplied by latency |
| Warm-up | 16 workers send the 450 startup lookups at the same time, thus all 16 connections open before the load starts |
| Rotation | Apache's time to live of 30 s: the pool closes an older connection when it would lease it again, thus after its current request. The replacement connection gives the Service a new backend selection |
| Idle connection | Apache's idle-connection evictor checks each second and closes a connection that is not used for 10 s |
| Failed connection | Apache closes a connection after a failure or a cancel |
| Deadlines | 2 s to connect. A scheduled cancel ends the complete read after 5 s, including the wait for a connection and all attempts |
| Repeated attempts | Only after a failure without an HTTP response, for example a refused, reset or closed connection: a maximum of 3 attempts, 100 ms apart. Timeouts are not repeated |
| Response body | A body larger than 2 MiB is an invalid response; the reader reads at most 2 MiB plus one byte |

An HTTP status is a result, not a reason to send the request again, because
product already limits and repeats its own database work. Each read has one of
these outcomes:

| Outcome | Meaning |
|---|---|
| `OK` | `200` with the issued IDs and the dataset payload |
| `NOT_FOUND` | `404`, for example a product that a standby has not replayed yet |
| `REPLICA_UNAVAILABLE` | `503 READ_REPLICA_UNAVAILABLE` |
| `UNAVAILABLE` | Another `503`, for example `CATALOG_UNAVAILABLE` |
| `HTTP_ERROR` | A different status |
| `INVALID_RESPONSE` | A body that is too large or not valid JSON, or that has a different ID or payload |
| `DEADLINE` | No result in 5 s |
| `TRANSPORT` | No HTTP response after the permitted attempts, for example a refused connection |

The reader logs one metric line for each report interval:

```text
Product reads, interval 30.0 s: 6000 requests, 200.0/s, outcomes {OK=6000}, latency ms {p50=2, p95=4, p99=7, max=15}, regions {ASIA=1996, EU=2009, US=1995}, pool {leased=3, pending=0, available=13, max=16}, catalog-v1 key-to-ID SHA-256 <hex>
```

Each interval and total report repeats the digest of the resolved catalog being
read. Live verification reads at most 100 recent lines and 64 KiB, so it can compare
the reader and seeder digests after the startup summary has rotated away.

The latency percentiles are in whole milliseconds, rounded up. They include the
wait for a connection and all attempts. `regions` counts reads by seller home
region. `pool` is the state of Apache's pool when the line is written: `leased`
connections are in use, `pending` reads wait for a connection, and `available`
connections are open and free. The level is INFO when all reads are `OK`, and
WARN when they are not. At SIGTERM, the reader stops its workers and writes one
`Product reads, total` line as the last report. The deadline of each read limits
this stop. Log lines contain no response payloads. The reader has no HTTP server,
thus workload packages still contain no Vert.x or Netty runtime.

| Property | Default | Purpose |
|---|---|---|
| `shardshop.reader.product-url` | None. Startup fails without it | Base URL of the product Service |
| `shardshop.reader.catalog-version` | `catalog-v1` | Dataset version. Other versions fail before HTTP |
| `shardshop.reader.request-rate` | `200` | Request starts per second for all workers, 1–10000 |
| `shardshop.reader.workers` | `16` | Concurrent workers, from the number of connections to 256 |
| `shardshop.reader.connections` | `16` | HTTP/1.1 connections, 1–64 |
| `shardshop.reader.connect-timeout` | `2s` | Connection deadline. It cannot be longer than the request deadline |
| `shardshop.reader.request-deadline` | `5s` | Complete deadline of one read. Startup fails below 5 s, because product returns `503` within its 4-second server deadline |
| `shardshop.reader.max-attempts` | `3` | Attempts after failures without an HTTP response, 1–5 |
| `shardshop.reader.retry-interval` | `100ms` | Fixed delay before a new attempt |
| `shardshop.reader.max-connection-lifetime` | `30s` | Apache time to live of a connection |
| `shardshop.reader.idle-timeout` | `10s` | Time before an unused connection closes |
| `shardshop.reader.report-interval` | `30s` | Time between metric lines, at least 1 s |

For a saturation test, increase `request-rate`, and increase `workers` and
`connections` together. One pod sends at most `workers` divided by the latency:
16 workers at 3 ms send approximately 5,300 requests/s. When product saturates,
the reader does not send missed requests later, thus the reported rate is the
accepted rate. Environment variables such as `SHARDSHOP_READER_PRODUCT_URL` also set these
properties. `--check-startup` validates the configuration, including the URL and
the timeouts, and exits without HTTP requests. An unexpected worker failure logs
one error line and stops the reader with exit code 1. Step 3.9 adds the Deployment,
its resource budget, the per-pod product counters and the [seeding gate](#seeding-gate).

`ProductReader.feature` of [step 3.10](#integration-tests-with-testcontainers-step-310)
runs the packaged reader with its default policy and 5-second reports against the
packaged product, and seeds the catalog with the packaged seeder. It does not
connect to the kind lab. It takes approximately 70 seconds:

1. The reader starts before the seeder. It must wait for the catalog and must not
   start its load. After the seeder run, it must start its load, and its resolution
   digest must equal the seeder digest.
2. Three report intervals must have only `OK` reads at 180–201 requests/s, with all
   three regions, the 16 open connections of the warm-up and no read that waits for
   a connection.
3. While product stops, then starts without replicas, and then starts with
   primaries, the reader must record `TRANSPORT` and `REPLICA_UNAVAILABLE`
   outcomes, continue to run, and recover. No read takes longer than 5.1 seconds.
4. After SIGTERM, the total line must be the last report. The stored rows must not
   change.
5. Against product without read replicas, a reader with a 10-second maximum wait
   must exit with code 1 and one `REPLICA_UNAVAILABLE` lookup error, before the load
   starts.

The connection rotation after the 30-second lifetime and the 5-second read deadline
are client behavior. `ProductRequestsTest` and `ProductReaderTest` check them
against a loopback server. `ReaderConfigurationTest` checks that the packaged
defaults are the time limits of the
[HTTP connection policy](ARCHITECTURE.md#http-connection-policy): a 2-second connect
timeout, a 5-second deadline, a 30-second lifetime and a 10-second idle timeout.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

## Product deployment and seeding gate (step 3.9)

Product, the seeder Job and the reader run on the lab. Build and qualify the current
sources, migrate and deploy with one `up.sh` invocation:

```bash
bash microservices/shardshop/scripts/up.sh
```

`up.sh` resolves the ShardShop POM from its own location and runs the full
`mvn -B -ntp -f microservices/shardshop/pom.xml clean install -DinstallAtEnd=true` build,
which includes verification and caches the verified artifacts for later kind checks. The commands
above use repository-relative paths; invoking `up.sh` by its absolute path works
from any directory. The Maven build is the source for the four freshly built
images; no separate Maven or image-build command is required. Audit builds remain
optional. The POM sets `project.build.outputTimestamp`, thus the same sources give
the same packages. Docker then uses its cached image layers again, and an unchanged
rebuild keeps its image IDs. Thus `kind load` skips the nodes, and the digests stay
the same.

`up.sh` uses Skaffold for image builds and Helmfile for real Helm releases.
`cluster-create.sh` syncs `cnpg` and `shardshop-infrastructure`. After allocator
validation and image delivery, `up.sh` syncs only `shardshop-migrations` and
`shardshop-application`.
Helm waits for the application resources and its seeder hook. The application
release then runs the native Helmfile `postsync` acceptance hook with
`kind-release | kind-deployment`. `kind-release` checks the snapshot images,
rendered desired configuration, current deployed Helm revision and Deployment
generations, pod ownership and runtime digests, the current successful seeder, and
ready product EndpointSlices. When kind reports an archive-index runtime digest,
the observer reads its single platform descriptor through containerd and requires
the qualified manifest; `kind-deployment` retains the traffic through both
product pods and the accepted reader load. A failed acceptance hook fails Helmfile
and `up.sh`; Helm may still show the release as deployed. Acceptance failure does
not roll back the release: correct the cause and rerun startup. The observer never
repairs resources. Diagnostics are bounded and contain no secrets.
The reader remains running and disruptive drills remain opt-in. A new
`kind-release` run requires `images.json` in the snapshot, including the deployed
`restartToken` alongside `images`; standalone legacy
`kind-deployment` runs do not. The four maintained lifecycle entry points are
`cluster-create.sh`, `up.sh`, `down.sh` and `cluster-destroy.sh`. Create the foundation
with `cluster-create.sh`, then use `up.sh` for application startup and deployment.
Every `up.sh` restarts application pods, including unchanged builds.
`cluster-create.sh` applies and verifies the retention admission policy, then creates
the generator registry and immutable identity on its new cluster. `up.sh` only checks
existing allocator state: missing, partial, malformed, or mismatched
allocator state stops startup and requires independent allocator recovery. `up.sh` never
resets retained generator state.

### Application images

`infra/images/application/Dockerfile` is the one recipe for the six application
images. Its context is the complete `target/quarkus-app` directory of one
application. All images use the pinned Canonical OpenJDK 25 JRE, the working
directory `/app`, user `10001:10001` and `HOME=/tmp`, and their entrypoint starts
`quarkus-run.jar`. The images contain no kubectl: since step 3.11, product and order
reserve their generator IDs with Java code in their own JVM.

`up.sh` currently builds and qualifies `flyway`, `product`, `product-seeder` and
`product-reader`. Step 4.9 adds the order, ledger and order producer images. It:

1. Uses [`skaffold.yaml`](skaffold.yaml) to build the four ARM64 artifacts from
   current packaged Maven outputs and the reviewed Dockerfiles. Skaffold owns the
   artifact list, Docker recipes and SHA-256 tag policy. Its local build uses Docker
   BuildKit, disables provenance/SBOM output for reproducible manifests, and writes
   a private build-result JSON file. An empty invocation-local artifact cache forces
   fresh builds while keeping Docker layer reuse and avoiding Skaffold's asynchronous
   pruning of rebuilt images. Flyway uses the
   [migration image](#database-schemas-and-migrations-step-23) recipe in
   `infra/images/flyway`; applications use `infra/images/application/Dockerfile`.
2. Prints the OS package inventory (`docker scout sbom`) and the advisories of all
   package types and severities (`docker scout cves`). Docker Scout does not index
   six Ubuntu packages of the JRE base, glibc among them. Thus the inventory and the
   gate do not include them. [VERSIONS.md](VERSIONS.md#os-packages-without-an-advisory-check)
   records this gap.
3. Stops if Docker Scout finds a critical or high advisory without a reviewed
   statement in `infra/images/application/vex/`. Statements identify the named
   image recipes and exact package versions independently of Skaffold's content tags.
   A failed gate prevents loading the image.
   All four enabled images are built and scanned before any image is loaded into
   kind.
4. After provisioning, loads each qualified image into the kind nodes, one node at a
   time, and requires the same
   loaded platform manifest on each node. Then it registers that digest under the image name.
   `kind load` skips a node that already has the same image ID. Then the check uses
   the loaded platform manifest digest of the earlier import on that node.
   For `flyway`, the loaded platform manifest must also be the pinned digest; otherwise the script
   stops before it registers the digest, and the image pin needs a review.
   It also tags the image `sha256-<digest>` on each node. kind names each import
   `import-<date>@<digest>`, without a registry. Without the extra tag, the CRI uses
   that name after the original tag changes or is removed. It cannot find the earlier image,
   thus new containers of the deployed release fail with `CreateContainerError`.

Skaffold is used in build-only mode: it has no deployer in this workflow and does
not load images into kind itself. The small native `kind load` adapter in `up.sh`
performs delivery and checks each loaded config ID against the qualified build;
Helmfile owns deployment.

`up.sh` captures the loaded platform manifest digests of this invocation's qualified builds on
every node before running migrations or applying a new application release. A
failure while loading or registering nodes can leave a partial image load, but
prevents the new release. Docker Scout
needs a network connection. [VERSIONS.md](VERSIONS.md#step-39-application-images)
records the scan results and the reviewed statements.

### Seeding gate

The reader owns its start condition, as the
[workload lifecycle](ARCHITECTURE.md#workload-lifecycle) defines. It sees the
catalog only through product's HTTP API. At startup, it looks up the 150 sellers
and 300 products of `catalog-v1` by creation key, and it sends load only after all
lookups succeed and match the dataset:

- While an entity is missing or product is unavailable, slow or not reachable,
  SmallRye Fault Tolerance repeats the lookups with exponential backoff from 1 to
  15 seconds. Each repeat logs one INFO line that names the failed lookup. The
  reader sets the Apache `HttpRequestRetryExec` logger to WARN, thus a refused
  connection adds no line for each repeated request.
- After 5 minutes, the reader stops with exit code 1, and Kubernetes restarts the
  pod.
- A response that differs from the dataset stops startup at once.

The MicroProfile Fault Tolerance keys
`dev.nklip.javacraft.shardshop.workload.reader.config.CatalogWait/resolve/Retry/maxDuration`,
`.../Retry/delay` and `.../ExponentialBackoff/maxDelay` change these bounds, in
milliseconds.

`shardshop-application` owns routing, the product ConfigMap, Service and
Deployment, and the reader Deployment with one replica. Each Helm revision runs a
new `post-install`/`post-upgrade` seeder Job named
`shardshop-application-seeder-<revision>`. No user run name is required. The Job has
`restartPolicy: Never`, `backoffLimit: 3` and `activeDeadlineSeconds: 300`; Helmfile
uses a bounded 600-second release timeout and Helm waits for the hook result.

An unchanged invocation still validates migrations and seeds idempotently, while
each startup rolls the product and reader pods. Product also rolls when its image,
resources or configuration change. Its pod checksum is SHA-256 of the complete
rendered `application.properties`, using the same named template as the mounted
file, including static defaults. Reader image, HTTP settings, resources and
dataset version are direct template inputs. Helm revision numbers stay outside
both application pod templates; the startup restart token controls the rollout. The reader Deployment's `shardshop.javacraft/seeder-job` annotation names
the latest seeder Job without replacing the reader pod. Standalone verification
prefers that annotation and retains the old pod run-label fallback for manual
legacy deployments.

Successful and failed seeder Jobs retain their logs for 24 hours before Kubernetes
TTL cleanup. After that evidence expires, run `up.sh` again before standalone
workload verification. Each seeding attempt recovers the same catalog IDs. If the
catalog is already complete, the reader can continue load while seeding repeats.

Inspect the automatic release:

```bash
kubectl --context kind-shardshop -n shardshop get jobs -l app.kubernetes.io/name=shardshop-product-seeder
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-product-reader
```

### Product pods

| Item | Value |
|---|---|
| Pods | Two replicas behind the Service `shardshop-product:8080` with `sessionAffinity: None` |
| Start | The product JVM reserves a generator ID at startup with the projected token of the `shardshop-product` ServiceAccount, before it opens its port. `SHARDSHOP_ID_REGISTRY_UID` comes from the `shardshop-generator-identity` ConfigMap |
| Routing | The `application.properties` key of the `shardshop-routing` ConfigMap at `/etc/shardshop/routing` |
| Database TLS | Only the `ca.crt` key of each `<shard>-ca` Secret, at `/etc/shardshop/catalog/<shard>/ca.crt`. The CA Secrets also hold `ca.key` |
| Password | `PRODUCT_APP_PASSWORD` from the `product-app` Secret. The `shardshop-product-config` ConfigMap, at `/app/config`, sets each shard's password to `${PRODUCT_APP_PASSWORD}`. Quarkus reads `config/application.properties` in the working directory of the JVM |
| Readiness | A TCP probe on port 8080 every 2 s. Quarkus opens the port only after the routing, generator and catalog checks |
| Security | Restricted Pod Security without fixed user IDs in the pod, a read-only root file system and an `emptyDir` at `/tmp` |

Each product pod start reserves one generator slot: a rollout, a replacement or a
container restart. `up.sh` changes product pod templates when the product image,
configuration or relevant topology changes; a mounted configuration change also
changes the template's configuration hash.

Product counts its HTTP requests with the Quarkus Micrometer Prometheus extension.
`/q/metrics` on port 8080 shows the counter
`http_server_requests_seconds_count{method,outcome,status,uri}`, with the route
template as `uri`. Each pod has its own counters. Read them through the API server
pod proxy:

```bash
kubectl --context kind-shardshop get --raw \
  "/api/v1/namespaces/shardshop/pods/<product-pod>:8080/proxy/q/metrics" |
  grep '^http_server_requests_seconds_count.*products/{productId}'
```

### Workload pods

The seeder and the reader get only the product URL
`http://shardshop-product.shardshop.svc:8080` in `SHARDSHOP_SEEDER_PRODUCT_URL` and
`SHARDSHOP_READER_PRODUCT_URL`. They have no API token, no Secret, no database
setting and no Service variables. The reader Deployment uses the `Recreate`
strategy, thus a maximum of one reader runs.

The resource budgets come from measurements on the lab on 2026-10-09. At 200
reads/s, each product pod used approximately 10% of one core and 310 MiB of
anonymous memory for its two JVMs. The reader used 7% of one core and 148 MiB.
Since step 3.11, each product pod runs one JVM. On 2026-10-13, at approximately 190
reads/s and a few minutes after a pod replacement, each product pod used 17–23% of
one core and 268 MiB of anonymous memory. The budgets stay the same.
With a 1-CPU limit, the JVM starts were throttled for many seconds, and the reader's
startup lookups failed. Thus each container has a 2-CPU limit.

| Container | CPU request | CPU limit | Memory request | Memory limit |
|---|---|---|---|---|
| Product | 250m | 2 | 384Mi | 512Mi |
| Seeder | 100m | 2 | 192Mi | 256Mi |
| Reader | 100m | 2 | 192Mi | 384Mi |

After a product rollout, the new product JVMs are cold. The 16 concurrent startup
lookups of the reader can then receive `503`, because product allows one second to
open or lease a database connection. The reader then waits and repeats the lookups,
as the [seeding gate](#seeding-gate) defines. Against warm product pods, the lookups
take less than one second.

### Live check

Prepare the [kind verification snapshot](#kind-verification-snapshot-step-315) from
this lab's inputs, then run the default deployment suite:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot"
```

The lab must already be running, migrated and deployed through `up.sh`, with two
Ready product pods and one running reader. The scenarios
observe this deployment and preserve reader replicas. They check the deployed
routing and database topology, workload credential restrictions, the saved IDs and
rows for all 150 sellers and 300 products, and the reader's resolution digest.
Within 120 seconds, the read counters of both Ready product pods must increase;
a fresh reader report interval must contain only `OK` reads at 180/s or more.
Use `-Dshardshop.kind.tags=kind-workload` for the workload checks alone.

Repeated seeding and product pod replacement are explicit disruptive drills:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-workload-drill
```

The drill runs repeated temporary seeder Jobs and compares their digests and the
saved seller/product IDs with the existing catalog. It replaces one product pod
and requires a distinct generator ID greater than the registry `highWaterMark`
saved before replacement, and no greater than the current high-water mark. After
replacement it repeats the two-pod traffic check and requires an all-OK interval
that starts after recovery. Temporary Jobs are removed and the already-running
reader keeps its replicas. The pod replacement consumes generator allocations;
cleanup never rewinds allocator state.

On the lab, the catalog is already complete, thus the reader does not wait there.
`CatalogWaitTest` of the reader module shows the wait: the reader sends no load
while an entity is missing, starts after the entity appears, and stops after the
maximum wait.

## Integration tests with Testcontainers (step 3.10)

The test-only module `shardshop-testing` holds Cucumber scenarios that run the
packaged product, seeder and reader against a real PostgreSQL and a real Kubernetes
API server, and that check the contracts across modules. Failsafe runs them with
`-Pintegration`, as the `*-testing` modules of the other projects in this repository
do. The module has no main classes and installs nothing. Its POM dependencies on the
three applications have no transitive dependencies; they only make `-am` package the
applications first. The contract checks use `ContractDocument` from the test JAR of
`shardshop-common`, not application classes. Docker must run,
and `helm` must be on the `PATH` (tested with 4.3.0), because the integration
profile renders the generator objects of the chart:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

On 2026-10-13, the 42 scenarios took approximately four minutes with the
kind lab stopped. While the lab runs, they take approximately six minutes. Cucumber
writes its
reports to `shardshop-testing/target/cucumber-reports`, and the application logs
stay in `shardshop-testing/target/scenario-runs`. Run them only through Maven,
which sets the image digests, the source directory and the path of the rendered chart
objects.

| Item | Test setup |
|---|---|
| PostgreSQL | One container of the pinned `tc.image.postgresql` (18.6) for the run, with its port bound to `127.0.0.1`. Each scenario creates its own databases and drops them at its end |
| Roles and databases | The catalog roles and memberships of the CNPG `DatabaseRole`s, the bootstrap hardening, and the schema `catalog` of `catalog_owner`, as on the lab |
| Migrations | The Flyway **13.8.1** Java API applies `database/shard/catalog` as `catalog_migrator` with the settings of its `flyway.toml` and the inventory placeholders, as the migration Jobs do |
| TLS | `openssl` in the container makes a test CA and a server certificate for `localhost` only. Product connects with its `verify-full` default |
| Kubernetes API | For each generator scenario, one container of the pinned `tc.image.k3s` (k3s v1.36.5) with `server --disable-agent`: no kubelet, no privileges, and the API port bound to `127.0.0.1`. The `pre-integration-test` phase renders the chart with `helm template` and the shared chart, kind environment values and ordered inventory. Each scenario applies the rendered `generator-allocation.yaml` and creates the registry with the same fresh-cluster create-then-pin sequence used by `cluster-create.sh`. For the host name check, product gets an OpenJDK hosts file (`jdk.net.hosts.file`) that points a name in the `.test` domain to `127.0.0.1`. The k3s certificate does not name it |
| Applications | The packaged `quarkus-app` folders, started as JVMs with an empty environment. Product gets a new fixed test generator ID at each start, or in the generator scenarios it reserves an ID in the k3s API server with a service account token. It gets four shards (`shard-d` is a second US shard) and at most two connections for each endpoint |
| Logs | After each scenario, a hook stops product and fails the scenario if a product JVM logged a WARN or ERROR line. This also covers the invalid requests, deadlines and outages that the scenarios cause. The generator scenarios check their expected startup failures separately: the log has only the fixed message, without the token or API response text |
| Cleanup | Testcontainers runs without its Ryuk reaper, which publishes a port on all host interfaces and runs privileged with the Docker socket. A JVM shutdown hook removes the container; only a killed test JVM leaves it. Testcontainers logs one `Ryuk has been disabled` WARN line for each run |

| Feature | Replaces | Cases |
|---|---|---|
| `CatalogMigration.feature` | `verify-catalog-migration.py`, `verify-catalog-lab.sh` | V1 to V2 upgrade of legacy rows and backfill, history validation of a rerun, object owners, no functions or triggers, creation-key and product constraints, profit-credit identities, the privileges of each role and login, and a changed grants file that Flyway reapplies and that removes stale privileges |
| `ProductApi.feature` | `verify-product-provider.py` | Seller and product creation, retries and conflicts on the shard of the key or seller, current stock and profits, integer forms, concurrent creation, lost responses, invalid input over the real HTTP stack, 32 KiB bodies, an incomplete body, a held lock with a full pool, restarts, and unavailable primaries or replicas |
| `ProductTransport.feature` | `verify-product-transport.py` | TLS sessions, an untrusted CA, a host name that the certificate does not name, and pool recovery after a PostgreSQL restart without a product restart |
| `ProductSeeder.feature` | `verify-product-seeder.py` | The [seeder runbook](#product-seeder-step-37) cases |
| `ProductReader.feature` | `verify-product-reader.py` | The [reader runbook](#product-reader-step-38) cases |
| `Contracts.feature` | `verify-contracts.py` and `verify-datasets.py` (step 3.12), for what connects the contracts of different modules | The RecordOrder example buys the product of the product creation example; both ledger result examples answer the RecordOrder example with its reserved result message ID, amount and currency, after its time; the illustrative IDs of all examples follow the service-issued ID rules; each row of catalog-v1 and buyers-v1 is a valid creation request of product or order |
| `GeneratorAllocation.feature` | `verify-generator-allocation.sh` (step 3.11) | A reservation at each start and restart, the generator ID of new sellers, six concurrent starts, the permissions of the product, order, ledger, workload and default service accounts, the retention policy, and the failures: another registry UID, an exhausted, missing or unreadable registry, a refused PATCH, an untrusted CA, a host name that the server certificate does not name and an API server that does not answer |

The expected bodies come from the product OpenAPI document; an error body has only
a code and a nonempty message. Since step 3.12, `CatalogApi` also checks each product
response against that document: the document must define its status for the
operation, and the body must match the schema of that status. The full run checks 189
responses. The scenarios calculate routes, key homes and advisory locks again with
the JDK, and they read the dataset TSV files, not the Java definitions. On the lab,
every `up.sh` invocation runs Flyway through Helm hooks and validates actual
history under the [migration rules](#database-schemas-and-migrations-step-23).

## Buyer and order acceptance (step 4.1)

Order is now a long-running HTTP/1.1 provider. `--check-startup` validates its
routing, generator and pool configuration and exits without opening a database
connection. Its Kubernetes deployment and producer workload remain steps 4.9 and
4.8. Apply the external catalog and ordering migrations before serving requests;
the application never runs Flyway.

`POST /api/v1/buyers` persists the person profile and region-scoped
`Idempotency-Key` together. Buyer key placement uses unsigned SHA-256 of UTF-8
`buyer\0region\0key` modulo the ordered shards in that region, then bounded ID
generation selects an ID routed to that exact shard. A matching retry returns
the stored buyer with `200`, including after a restart; first creation is `201`,
and a changed payload is `409 BUYER_IDEMPOTENCY_CONFLICT`.

`POST /api/v1/buyers/{buyerId}/order-allocations` persists a generated order ID
under `(buyerId, runName, requestOrdinal)`. New mappings return `201`, retries
return `200`, and allocation creates no order or saga. Both operations recover
existing identities without consuming another generator ID.

`PUT /api/v1/buyers/{buyerId}/orders/{orderId}` accepts only an allocation for
that buyer. It checks quarantine and the saved request fingerprint under a
transaction advisory lock, releases the transaction and connection, and resolves
products through their sellers' primaries. Catalog outcomes have fixed precedence:
unavailable, missing product, mixed currencies, then currency mismatch. A second
locked transaction repeats the durable checks before using those outcomes.
Concurrent creation or quarantine therefore takes precedence over a failed lookup.

Each creation takes a transaction advisory lock before its first read: the signed
low 64 bits of the SHA-256 of `buyer\0region\0key`,
`allocation\0buyerId\0runName\0requestOrdinal` or `order\0\0orderId`, as for the
product keys. A writer of order quarantine must take the same order lock, so that
the final locked check sees it.

Acceptance atomically saves the order, ordered item snapshots (including unit
cost), and one `PENDING_STOCK` saga. Prices and totals come from the catalog and
use exact arithmetic; totals outside `NUMERIC(19,2)` return `400`. The response is
`202` with a relative `Location` equal to `statusUrl`. Identical PUT retries and
status GETs use saved state, even during a catalog outage. Pending retries return
`202`; terminal retries return `200`. Acceptance does not change stock or create
an outbox record; the background stock coordinator performs those subsequent steps.

Primary pools and lookup concurrency are bounded. Each database phase runs on its
own virtual thread and waits at most one second for a pooled connection, thus a
burst queues instead of failing. A pool that stays full or an unknown commit
result returns `503`; clients retain the same key, allocation or order payload
when retrying. Request validation rejects malformed/duplicate/unknown
fields, numeric IDs, fractional quantities and invalid Unicode before database
access. Bodies are limited to 256 KiB, enough for the contract's 1,000 items.
HTTP/2 is disabled until its qualification in step 6.5.

| Property | Default | Purpose |
|---|---|---|
| `shardshop.order.max-pool-size` | `4` | Primary connections per shard, 1–32 |
| `shardshop.order.max-id-candidates` | `1024` | Buyer ID placement attempts, 1–65536 |
| `shardshop.order.catalog-concurrency` | `4` | Concurrent catalog lookups per request, 1–64 |
| `shardshop.order.catalog-deadline` | `3s` | Shared catalog deadline, 2–3 s; leaves room for the one-second pool acquisition bound |
| `shardshop.order.stock.enabled` | `true` | Run the stock coordinator; tests can isolate HTTP acceptance with `false` |
| `shardshop.order.stock.poll-interval` | `250ms` | Delay between scans, 100 ms–30 s |
| `shardshop.order.stock.lease` | `30s` | Durable per-saga ownership, renewed before each catalog phase; 10 s–5 min |
| `shardshop.order.stock.batch-size` | `8` | Maximum sagas processed per shard per scan, 1–100 |
| `shardshop.order.connections.<shard>.primary-url` | `jdbc:postgresql://<shard>-rw:5432/shardshop` | Primary endpoint for both ordering and catalog IO |
| `shardshop.order.connections.<shard>.username` | `order_app` | Runtime role with ordering and catalog grants |
| `shardshop.order.connections.<shard>.password` | Empty | Supply through protected runtime configuration |
| `shardshop.order.connections.<shard>.root-certificate` | `/etc/shardshop/order/<shard>/ca.crt` | CA for the default `verify-full` TLS mode |

Each database phase has a three-second limit, including at most one second of pool
acquisition, lock acquisition and commit; the whole HTTP request has a ten-second
limit for two database phases and the catalog phase. No order connection or lock
is held across catalog IO. The phase engine, TLS defaults and strict request
decoding are shared with product in `shardshop-common`; the HTTP deadline filter
stays in each application.

Ordering V2 keeps V1 and historical rows intact. Legacy buyers without complete
person profiles fail closed with `ORDER_STORE_UNAVAILABLE` until an operator
supplies truthful data. Historical item costs remain NULL, meaning unknown;
they must be reconciled before later profit crediting. New API writes always
persist complete profiles and costs. Existing table-level runtime grants cover
the new columns without broadening privileges.

Run the module checks and packaged-provider PostgreSQL scenarios:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-product -am -Paudit verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify -Dcucumber.filter.tags=@order-api
```

The scenarios use disposable PostgreSQL containers with the pinned test image,
owner migrations, runtime credentials and verified TLS. They check regional and
concurrent retries, restart recovery, exact snapshots, rollback, outages and races
with blocked catalog IO. Concurrent requests never retry a `503`: with one
connection per shard pool, they must queue for the connection. Each creation must
wait for its advisory lock, and an order lock held past the phase budget gives
`503` without writes. Unit tests inject unknown commit results and generator
failure, and assert deterministic catalog precedence with reversed completion
order. The packaged startup tests of product and order show that every invalid
setting stops the start before a generator reservation. The first command also
builds product, because product shares the phase engine. The retained kind lab is
not changed by these checks.

## Stock reservation and compensation (step 4.2)

After HTTP acceptance commits, the order process polls each buyer shard for
`PENDING_STOCK` sagas and cancelled sagas with unfinished releases. It claims a
durable versioned lease in a short transaction, then reserves each seller shard's
items in ascending product-ID order. Reservation insertion and conditional stock
decrement share one `READ COMMITTED` transaction. Retries of an existing active
reservation keep its original quantity and do not decrement again.

Once every reservation succeeds, a single buyer-shard transaction changes the
order and saga to `PENDING_LEDGER`, reserves distinct transport/result IDs, and
inserts the complete `RecordOrder` envelope. No broker is required by this step;
CDC delivery and ledger consumers follow in steps 4.4–4.6.

Insufficient stock commits `CANCELLED` with `OUT_OF_STOCK` before compensation.
Each seller shard atomically marks active reservations released and restores
their quantities. Completion is saved in `stock_released_at` only after all
shards succeed. A permanent `catalog.stock_reservation_guards` row closes each
order on release, including shards with no reservation, so a paused former owner
cannot reserve after compensation. These guards and reservation history must be
retained. The Java API offers no quantity changes, reactivation or deletion.

Catalog outages, unknown commit outcomes and unavailable transport IDs keep work
pending. Persisted retries back off for 1, 2, 4, 8, 16 and then at most 30 seconds;
stock retries use a separate counter from ledger reconciliation. A crashed
worker's lease expires; another process resumes the same immutable snapshots.
Each database phase keeps the existing three-second deadline. No order
transaction, connection or advisory lock spans catalog writes.

The fix-forward V3 migrations add catalog guards and ordering lease/retry fields.
Apply both streams through the existing migration workflow before starting the
new worker. V1/V2 are unchanged, and there are no SQL functions or triggers.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify '-Dcucumber.filter.tags=@stock-reservation or @order-api or @catalog-migration'
```

The stock scenarios run actual packaged coordinators and runtime-role SQL against
disposable PostgreSQL databases. They verify multi-region envelopes, competing
workers and scarce stock, partial compensation, outages, restarts, reserve/release
rollback under injected constraints, and denied `TRUNCATE`. Every phase checks
`stock = initial_stock - active reserved quantities`. The separate order API
scenarios disable the worker to isolate acceptance's atomicity and validation.

## Order outbox CDC relay (step 4.4)

Order starts one independently supervised Debezium 3.7.0.Final reader for each
routing shard. The reader snapshots retained pending outboxes, then captures
committed inserts from `ordering.order_outbox` through `pgoutput` and the
administratively provisioned `ordering_order_outbox` publication. Slots and
connector identities use `shardshop_order_<shard_name>`, replacing hyphens with
underscores. Slots are retained and failover-enabled; the shard's session advisory
lock prevents overlapping readers from writing the shared JDBC offset store.

The relay sends the stored `RecordOrder` JSON unchanged, with persistent delivery,
mandatory routing and publisher confirms. A return or negative/missing confirm
leaves the row unpublished. After confirmation, an `order_app` transaction marks
`published_at`; only then may Debezium checkpoint that source record. Snapshot
rows already published, metadata updates, deletes and connector control records
do not become commands. Redelivery retains the original message identity.

Each reader sends a Debezium heartbeat every 10 seconds. Its action query,
`SELECT pg_logical_emit_message(true, 'shardshop-cdc-heartbeat', '')`, emits a
transactional logical decoding message. PostgreSQL permits this function for every
login, and it writes no table. Thus an empty first snapshot saves its first offset
and reader history, and an idle outbox still confirms the WAL of other writes.
Heartbeat records and heartbeat messages never become commands.

Each shard uses its existing `shardshop.order.connections.<shard>` primary URL
and TLS certificate. Configure the replication login separately:

```properties
shardshop.order.cdc.enabled=true
shardshop.order.cdc.connections.shard-a.username=ordering_cdc
shardshop.order.cdc.connections.shard-a.password=${ORDERING_CDC_PASSWORD}
shardshop.order.cdc.broker.host=shardshop-rabbitmq
shardshop.order.cdc.broker.port=5672
shardshop.order.cdc.broker.virtual-host=shardshop
shardshop.order.cdc.broker.username=shardshop-order
shardshop.order.cdc.broker.password=${RABBITMQ_ORDER_PASSWORD}
shardshop.order.cdc.broker.timeout=5s
```

When CDC is enabled, startup fails before the generator reservation unless every
routing shard has `shardshop.order.cdc.connections.<shard>.password` and the broker
has `shardshop.order.cdc.broker.password`. The error names the property, never a value.
Replication credentials only
read/snapshot the source; the existing application credentials write publication
marks and `ordering.debezium_offset_storage`. Apply ordering V4 before enabling
CDC: its permanent `cdc_reader_state` marker is inserted atomically with the first
durable offset and survives transport cleanup.

Ordinary failures restart only the affected reader, after 0.5 s and then with a
delay that doubles up to 30 s. Each retry logs
`CDC reader for <shard> stopped; retrying in <delay> ms: <cause>`. Before each
start, the reader compares its offset rows, reader history and slot, so the
decision comes from durable state and never from an error message. A slot without
an offset and without reader history belongs to a first start that failed before its
first checkpoint. The reader then drops that slot with the replication login, and
Debezium creates a new slot with a consistent exported snapshot. These states stop
the reader with `CDC recovery required for <shard>: <reason>`:

- `durable offset exists without its replication slot`
- `replication slot exists without a durable offset`
- `reader history exists without its offset and replication slot`
- `durable offset exists without reader history`
- `replication slot is ahead of its durable offset`
- `replication slot was invalidated (<reason>)`, for example after `wal_removed`
- `replication slot is not a failover pgoutput slot of this database`
- an offset row that is malformed, that belongs to another connector, or that has
  no usable source position

Reconcile the retained
outbox and permanent saga/ledger state before an operator-directed resnapshot;
do not clear the marker or choose `trust_slot` to bypass recovery.

CDC is enabled by default on normal order starts. `--check-startup` starts no
workers, but it checks the CDC credentials. Tests can set
`shardshop.order.cdc.enabled=false` to isolate HTTP or stock behavior. Kind deployment of order and its Secret mounts remains step 4.9.
Crash injection and synchronized-standby failover drills remain steps 5.5 and 6.2.
Run the relay acceptance against disposable PostgreSQL and RabbitMQ containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am -Paudit clean install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@cdc-relay'
```

## Ledger command consumer (step 4.5)

Ledger consumes `ledger.record-order` with manual acknowledgement and one
prefetched delivery per process. It validates the complete `RecordOrder` schema,
canonical string IDs, timestamp, immutable snapshot arithmetic and normalized
request fingerprint before opening a bounded transaction on its own database.
The parser enforces the broker's 1 MiB payload limit before JSON materialization.
It never reads the order or catalog databases and generates no IDs.

The transaction locks the order, saga, command and transport identities in a stable
order with PostgreSQL advisory locks. A new supported-currency command inserts
one permanent decision and ledger entry, its reserved result binding, inbox and
pending result outbox. The default allowlist is `USD`; other valid currencies save
a permanent `REJECTED` decision with `UNSUPPORTED_CURRENCY` and no entry. The
consumer acknowledges only after commit. Malformed commands use
`basic.reject(requeue=false)`; transient transaction failures use
`basic.reject(requeue=true)` and the broker's bounded delayed-retry/DLQ policy.

An identical command always rearms its saved result, including an inbox hit or a
delivery after transport cleanup. Its durable publication attempt increments in
the same transaction as the pending outbox. A new reconciliation transport pair
uses the saved business outcome and `decidedAt`, even after allowlist changes.
Conflicting logical IDs, snapshots or transport bindings commit to
`ledger.conflicting_commands` before acknowledgement, without replacing the
decision or generating a rejection. Repeated identical conflicts increment their
delivery count; different canonical payloads remain separate incidents.

Apply ledger V2 before starting the consumer. It permanently retains each original
command envelope in `ledger_result_ids`, so a changed same-ID payload or timestamp
is still detected after inbox cleanup. V2 backfills existing bindings from their
inboxes and deliberately stops if that original payload is unavailable; reconcile
such retained data before retrying the migration. V1 remains unchanged.

Configure the dedicated ledger database and broker credentials:

```properties
shardshop.ledger.enabled=true
shardshop.ledger.database.url=jdbc:postgresql://ledger-db-rw:5432/ledger
shardshop.ledger.database.username=ledger_app
shardshop.ledger.database.password=${LEDGER_DATABASE_PASSWORD}
shardshop.ledger.database.root-certificate=/etc/shardshop/ledger/ca.crt
shardshop.ledger.max-pool-size=4
shardshop.ledger.allowed-currencies=USD
shardshop.ledger.broker.host=shardshop-rabbitmq
shardshop.ledger.broker.port=5672
shardshop.ledger.broker.virtual-host=shardshop
shardshop.ledger.broker.username=shardshop-ledger
shardshop.ledger.broker.password=${RABBITMQ_LEDGER_PASSWORD}
shardshop.ledger.broker.timeout=5s
```

`-Dquarkus.config.locations=file:/path/to/ledger.properties` loads an external
configuration file. Normal starts enable the consumer; `--check-startup` validates
configuration and exits without consuming or connecting to external services.
An enabled consumer requires both passwords. `shardshop.ledger.enabled=false`
disables the worker. Database phases have a three-second deadline; broker
connections use the configured timeout and reconnect with capped backoff.
The default database URL names `ledger-db-rw`, the read-write Service of the
`ledger-db` cluster.

The consumer log tells an operator why a delivery did not complete. Log lines can
contain validated IDs and fixed rule texts, but never the command body or a password:

- `Ledger command consumer stopped; retrying in <delay> ms: <cause>` follows a broker
  connection failure, a closed channel or a cancelled consumer.
- `Ledger command transaction failed; requested broker retry: <cause>` names the
  database error, for example a check constraint, without the row values.
- `Malformed ledger command rejected to its dead-letter queue: <rule>` names the
  rule that the command does not obey.
- `Conflicting ledger command <messageId> for order <orderId> committed to quarantine: <reason>`
  names the conflict reason that `ledger.conflicting_commands` stores.

Build the ledger and run its disposable PostgreSQL/RabbitMQ acceptance:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-ledger -am -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@ledger-consumer or @ledger-migration'
```

Step 4.5 leaves result outboxes pending for the publisher in step 4.6. Application
deployment to kind remains step 4.9; the crash and failover drills remain later
milestones.

## RabbitMQ topology and delivery policies (step 4.3)

`cluster-create.sh` provisions one persistent RabbitMQ 4.3.6 broker through the
infrastructure Helm release. `shardshop-rabbitmq.shardshop.svc:5672` serves AMQP;
port 15672 serves management inside the cluster. The `shardshop` vhost contains
the durable direct exchanges `ledger.commands`, `order.saga-results` and
`shardshop.dlx`, with these single-member quorum queues:

| Queue | Complete policy | Dead-letter destination |
|---|---|---|
| `ledger.record-order` | `ledger-command-delivery` | `ledger.record-order.dlq` |
| `order.ledger-results` | `order-result-delivery` | `order.ledger-results.dlq` |
| Both `.dlq` queues | `parking-capacity` | None |

Source policies use failed-delivery retry, delays from 1000 to 5000 ms,
`delivery-limit=5`, and at-least-once dead-lettering. All queues use reject-publish
limits of 1000 messages and 16 MiB; quorum admission can briefly overshoot these
thresholds. When a full parking queue rejects a dead letter, the source retains
it until the broker's next internal confirmation retry (180 seconds by default).
Parking queues disable the delivery limit
with `x-delivery-limit=-1`, with no TTL, expiry or automatic consumer. The broker
accepts messages up to 1 MiB, has a 512 MiB memory watermark, 100 MB free-disk
threshold and a 1 GiB container limit. `stream_queue` is enabled by the broker's
stable feature defaults and checked by acceptance tests.

The StatefulSet retains its 2 GiB PVC across restarts and deletion. A stable
node name keeps the same broker database. `rabbitmq.runAsUser` can be any positive
UID; `fsGroup` makes storage writable, and cookie/plugin/runtime files live in
`/tmp`. The root filesystem is read-only and all Linux capabilities are dropped.
Startup copies only the two named definition files out of Kubernetes projected
volumes, avoiding their metadata directories. A ConfigMap checksum rolls the
broker when public configuration changes. One broker provides persistence but
no availability during broker or node downtime.

Creation generates the `shardshop-rabbitmq` Secret once. Its `users.json`,
`admin-password`, `order-password`, `ledger-password` and `erlang-cookie` keys are
preserved on reruns. `shardshop-admin` owns topology; `shardshop-order` can publish
commands and read results; `shardshop-ledger` can read commands and publish
results. Service users cannot configure resources or use management. Back up
this Secret with broker data. If the Secret disappears while the StatefulSet or
PVC remains, creation stops: restore the original Secret before retrying. A
partial Secret also fails without printing credentials. Credential rotation
requires a coordinated broker restart and consumer update; it is not performed
by ordinary startup.

Run provisioning and the read-only live readiness/topology gate:

```bash
bash microservices/shardshop/scripts/cluster-create.sh
```

`up.sh` also runs that gate before migrations. To verify the chart, lifecycle
preservation, non-image UID, permissions, native retry boundary, persistent
message recovery and full-queue backpressure in isolated containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pintegration \
  -Dcucumber.filter.tags=@rabbitmq verify
```

The scenarios publish persistent mandatory messages and require publisher
confirms. They verify that `basic.nack(requeue=true)` preserves the failure
budget, while `basic.reject(requeue=true)` spends it, and malformed-message
rejection parks immediately. Test containers and their temporary volumes are
removed on completion. The test-only Java client uses its socket transport.
Application consumers, CDC publication and the full operational drills remain
the later steps 4.4–4.6 and 5.4.

## HTTP and message contracts (step 3.1)

| Owner | Contract |
|---|---|
| Product | [OpenAPI](shardshop-product/src/main/resources/contracts/openapi.yaml): seller/product POST and GET, including lookup by creation key |
| Order | [OpenAPI](shardshop-order/src/main/resources/contracts/openapi.yaml): buyer POST/GET, durable order-ID allocation, order PUT/status GET |
| Order | [RecordOrder JSON Schema](shardshop-order/src/main/resources/contracts/record-order.schema.json) and [command example](shardshop-order/src/main/resources/contracts/examples/record-order.json) |
| Ledger | [Result JSON Schema](shardshop-ledger/src/main/resources/contracts/ledger-result.schema.json), [recorded example](shardshop-ledger/src/main/resources/contracts/examples/ledger-recorded.json) and [rejected example](shardshop-ledger/src/main/resources/contracts/examples/ledger-rejected.json) |

Each document is self-contained and packaged under its owner's `contracts/`
classpath directory. Applications do not import another module's schemas or models;
clients keep their own DTOs. HTTP examples appear inline. The two result examples
show alternative decisions for one example command, never two outcomes for one
retained order. Example IDs illustrate the format and are not an issued dataset.
The product and order HTTP providers and their contract tests are implemented in
steps 3.6 and 4.1; ledger message processing remains steps 4.5–4.6.

Workloads select a pinned local dataset version and send its ID-free creation
payloads with stable `Idempotency-Key` values. Product/order generate and return
IDs; clients preserve those strings on subsequent requests. Catalog lookups by
creation key recover existing IDs without creating rows or sharing a manifest.

IDs are strings in `1..9223372036854775807`. Prices and costs are nonnegative
decimal strings with exactly two fractional digits. Profit balances have the same
format, but they can be negative. Allocation accepts `runName` and a string
`requestOrdinal` in `0..9223372036854775807`, and returns the same persisted order ID
on retries/restarts. Orders accept at most 1,000 distinct seller/product pairs.
The order contract defines normalization, validation precedence and retries;
message schemas define `requestFingerprint`, command/result correlation, immutable
snapshots and timestamp-preserving replay. Cross-field arithmetic, issuance and
stored-state checks require provider tests beyond schema validation.

The examples form one EUR purchase: seller/product IDs use generator 2 on
2026-02-01, the buyer ID uses generator 1 on 2026-03-01, and
order/saga/command/transport IDs use generator 1 at 2026-10-06T11:59:59Z, before
command publication. Seller and buyer regions match the existing routing rule.
These are illustrative wire examples, not workload IDs; actual creation responses
use the services' live generators. Workload definitions contain no IDs.

Message timestamps require uppercase UTC `T`/`Z` and at most six fractional digits.
Producers truncate instants to microseconds **before** both persistence and envelope
creation. Replays retain the saved instant and, for the same message ID, the exact
saved envelope. Company/person names, product descriptions, address text and run
names reject NUL and unpaired UTF-16 surrogates before IO,
while supplementary Unicode characters are valid. Integer fields accept `2`, `2.0`
and `2e0` as the same exact value and reject fractional values such as `2.5`; the
fingerprint serializes the normalized integer.

Providers enforce strict decoding before they construct typed DTOs or touch
storage. Product and order both enforce this. Enable unknown-property rejection and duplicate-member
detection, require JSON string tokens for string fields, and validate integral
numeric values exactly before conversion. Do not rely on Jackson's default string
coercion or float-to-int truncation. Contract/provider tests must include raw JSON
with duplicate keys, numeric IDs, `2.0`, `2e0`, `2.5`, NUL and unpaired surrogates.

### Contract checks (step 3.12)

Each module that owns a contract checks it in a Maven unit test, without
infrastructure: `ProductApiContractTest`, `OrderApiContractTest`,
`RecordOrderContractTest` and `LedgerResultContractTest`. They check:

- the document: the bundled OpenAPI 3.1 schema or the JSON Schema 2020-12
  meta-schema, each schema object against the meta-schema, in-document references,
  path parameters and unique operation IDs;
- every inline example, also in referenced responses, the message examples, and an
  example for each response body;
- the boundaries of IDs, status URLs, money, text, timestamps, integers and the
  creation and response objects;
- the rules inside the module: the order fingerprint, the snapshot total, the
  reserved IDs and their times, the ledger decision fields, and the illustrative
  service-issued ID and home-region rules.

`Contracts.feature` of `shardshop-testing` checks what connects the contracts of
different modules, and the creation payloads of the workload datasets
([integration tests](#integration-tests-with-testcontainers-step-310)). The tests and
the scenarios use `ContractDocument` and `ExampleIds` from the test JAR of
`shardshop-common`. They validate with the platform-managed Vert.x JSON Schema
library; [VERSIONS.md](VERSIONS.md#step-312-contract-test-dependencies) records its
qualification and the gaps that the helper corrects.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

Packaged routing startup checks run in the default Maven `verify` lifecycle.

## ID validation and currency selection (step 3.2)

All six applications use `dev.nklip.javacraft.shardshop.common.IdParser` from
`shardshop-common`; its implementation and regression tests are maintained once
in that library. `parse(String)`
validates path/descriptor text and returns an exact positive `long`.
`parseJson(JsonParser)` requires the current token to be a JSON string, then
applies the same whole-string ASCII and signed-long range checks without
advancing or closing the caller's parser. Call it before typed DTO binding so
numeric tokens cannot be coerced into strings. Invalid IDs, including malformed
string content detected by Jackson when reading the current token, throw
`IllegalArgumentException` with a fixed message that contains no input data;
HTTP providers map this to `400 INVALID_REQUEST` (product since step 3.6), and message
consumers must apply their invalid-command handling. Plain `IOException` from
the underlying stream propagates unchanged. Surrounding document validation,
including mapping syntax errors from the caller's token reads to HTTP 400,
remains with those providers/consumers.

The allocation `requestOrdinal` permits `"0"`, thus `IdParser` does not validate
it ([wire contracts](ARCHITECTURE.md#wire-contracts-step-31)).

The order producer's `CurrencySelector` validates an order-service-issued ID,
uses the shared `Sha256.unsignedDigest` to hash its unchanged UTF-8 text, and takes the full unsigned digest
modulo 100. It returns EUR for buckets below the rejection percentage and USD
otherwise. The default is 10; the integer constructor accepts 0 through 100.
Tests pin the architecture's buckets 0, 9, 10 and 99, plus exact large-ID values.
The future producer loop supplies its configured percentage and retains the
chosen payload across retries. The routing library uses the same digest helper;
the order producer has no shard-routing dependency.

Run the shared utility tests alone with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/common clean verify`.

The [six-application audit](#dependency-and-test-validation) runs all affected
application checks, including dependency/SBOM reports and packaged routing tests,
without infrastructure.

## Bounded Snowflake generation (step 3.3)

Product and order each inject one eager, process-scoped `IdGenerator` through the
shared CDI producer in `shardshop-idgen` (`shardshop-core/idgen`). That library
owns the generator, checked time source, exception and their unit tests in
`dev.nklip.javacraft.shardshop.idgen`, and declares the Snowflake dependency.
Its SmallRye/CDI wiring and injection tests live in
`dev.nklip.javacraft.shardshop.idgen.config`. The generator domain classes have no
framework imports. Common, sharding, ledger and workloads contain neither
generator classes nor Snowflake.
The generator uses the pinned Snowflake 0.0.2 library with epoch `2026-01-01T00:00:00Z`,
a 41/10/12-bit layout and
`THROW_EXCEPTION` sequence overflow. A checked `MonotonicTimeSource` validates
the exact returned millisecond tick before the library masks it; negative ticks
and ticks at or above `2^41` fail. The last valid millisecond is
`2095-09-07T15:47:35.551Z`.

`nextId()` returns a positive `long`, or throws the shared
`IdGenerationUnavailableException` with code `ID_GENERATION_UNAVAILABLE`.
Sequence exhaustion (4,096 IDs in one millisecond), backwards time, clock-source
failure and timestamp overflow never return an ID or trigger a spin/retry loop.
HTTP providers map this exception to `503 ID_GENERATION_UNAVAILABLE` and abort their
transaction. Since step 3.6, product does this. Consumer delivery handling remains
part of the later service milestones.

A production start requires a generator ID in `1..1023`; there is no production
default. Generator 0 remains reserved for historical illustrative fixtures; no
runtime creation path or workload emits generator-0 IDs. A deployed JVM reserves its
ID through the registry ([steps 3.4 and 3.11](#generator-allocation-at-jvm-startup-steps-34-and-311)).
The fixed `shardshop.id.generator-id`, also configurable through
`SHARDSHOP_ID_GENERATOR_ID`, is only for disposable tests without retained data. It
does not reserve or persist an ID, and a packaged JVM in a Kubernetes pod refuses it.

Controlled-clock tests exercise concurrency, sequence exhaustion/recovery, source
failures, backwards time, epoch/layout compatibility, exact timestamp limits and
the maximum positive `long`. CDI tests check shared instance identity; packaged
startup tests cover system-property and environment-variable configuration,
including missing, empty, malformed and out-of-range live IDs. Run the shared
ID-generation library's unit and CDI suite with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/idgen -am clean verify`.
Run routing's unit and CDI suite with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/sharding -am clean verify`.
Run both owners and their shared libraries:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
```

The [six-application audit](#dependency-and-test-validation) produces the
dependency/SBOM reports for the ownership checks.

## Generator allocation at JVM startup (steps 3.4 and 3.11)

Product and order reserve a fresh generator ID in their own JVM at every start, also
after a container restart in the same pod (step 3.11). The eager CDI producer of the
generator in `shardshop-idgen` does this before the generator exists:

1. It reads `shardshop-snowflake-generators` through the Kubernetes API with Apache
   HttpClient 5. The request has the token of the pod's service account. The client
   trusts only the CA of the service account volume, and the server certificate must
   name the host of the URL.
2. It sends one JSON patch that tests the UID, the resource version and
   `data.highWaterMark` of the registry, and replaces the counter.
3. Only an acknowledged reservation becomes the generator ID. The JVM then logs
   `Reserved generator ID <n> for this JVM start.` at INFO.

No kubectl binary, launcher process, init container or sidecar takes part.
Higher-priority configuration cannot substitute an ID for the reservation: a fixed
`shardshop.id.generator-id` together with a registry UID stops the start. The
producer has a later startup priority than the other startup beans, and a bean
that injects the generator creates it at once. Thus product and order create their
stores and checked service settings at an earlier priority than their services:
the checks of the routing snapshot, the connections, the pools and the service
settings come first, and a local configuration error stops the start before a
reservation. The packaged startup tests set a registry UID without a CA file and
show this for every setting.

| Property | Environment variable | Default and rule |
|---|---|---|
| `shardshop.id.registry.uid` | `SHARDSHOP_ID_REGISTRY_UID` | None. A value selects the reservation. The product Deployment takes it from the `registryUid` key of the immutable `shardshop-generator-identity` ConfigMap |
| `shardshop.id.registry.url` | `SHARDSHOP_ID_REGISTRY_URL` | `https://kubernetes.default.svc`. Only `https`, with a host and an optional port, and no path, query or user information |
| `shardshop.id.registry.namespace` | `SHARDSHOP_ID_REGISTRY_NAMESPACE` | `shardshop` |
| `shardshop.id.registry.token-file` | `SHARDSHOP_ID_REGISTRY_TOKEN_FILE` | `/var/run/secrets/kubernetes.io/serviceaccount/token`, read again for each request |
| `shardshop.id.registry.ca-file` | `SHARDSHOP_ID_REGISTRY_CA_FILE` | `/var/run/secrets/kubernetes.io/serviceaccount/ca.crt` |
| `shardshop.id.generator-id` | `SHARDSHOP_ID_GENERATOR_ID` | None. A fixed ID for disposable tests outside Kubernetes. A packaged JVM in a pod (`KUBERNETES_SERVICE_HOST` is set) refuses it |

Allocation has a 30-second budget, requests of at most five seconds, and retry
backoff from 25 to 500 milliseconds. A conflict, a policy denial, a server error or a
lost response burns its candidate: the next attempt advances beyond both the
observed counter and the uncertain candidate. A late acknowledgement cannot start the
application. A refused PATCH (401, 403 or 404) stops the start at once.
Missing, malformed, inaccessible, replaced or exhausted state fails startup.
The diagnostics identify exhaustion, stale identity, allocation deadlines, the API
client configuration, and read and update failures of the registry, with their next
checks. Only these fixed messages reach the log; API responses, exception texts and
tokens do not.
Generator 0 remains reserved; at most 1,023 starts, including burned slots, fit
one retained lab dataset.

The infrastructure chart creates only product/order ServiceAccounts and grants
them `get`/`patch` on the single named allocator ConfigMap. Ledger, workloads and
the default account have no allocation permissions.
A fail-closed admission policy rejects counter rollback, out-of-range values,
individual and collection deletion, and changes to the immutable `shardshop-generator-identity`
ConfigMap. That identity pins the registry's original UID. Neither state object
is rendered by Helm, so reapplying the chart cannot reset it.
The guard matches the admitted object's name, including collection deletes with
an empty request name. `up.sh` probes both allocator objects through raw collection
requests with `dryRun=All`, as well as individual dry-run requests. Namespace deletion
cannot remove those two objects and
leaves the namespace terminating while the guard is active; other ConfigMaps
remain outside the guard.
The guard uses Kubernetes' [validating admission policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)
and the allocator uses [conditional API updates](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources).

On first setup, `cluster-create.sh` initializes the allocator automatically after admission is
effective. Recovery of missing or partial state is an operator procedure for a lab
with retained IDs; never create a replacement registry based on absence alone.

```bash
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
```

Automatic creation uses `create`, never apply/replace/patch, and refuses to proceed
unless both state objects are absent. A lost creation response can leave partial
state; startup does not repair it by guessing. Establish its history before operator
recovery. Normal
stop/start, pod replacement and database restore preserve the ConfigMaps. Keep
the latest allocator identity and high-water backup outside kind, separately
from database dumps. Never apply an older allocator snapshot over live state.
Restoring an entire older etcd/cluster snapshot also restores its admission
history; that is unsupported without independently establishing the latest mark.
If that mark is unavailable, keep ID-producing services stopped. Removing the
guard or resetting state is allowed only after retiring all prior emitters,
databases, broker data, backups and other replay inputs.

Build and verify the affected modules, then run the generator scenarios:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

`GeneratorAllocation.feature` of [`shardshop-testing`](#integration-tests-with-testcontainers-step-310)
runs the packaged product against a real Kubernetes API with the RBAC and the
retention policy of the chart. It checks concurrent starts, restarts, the IDs of the
created sellers, the permissions of each service account, the policy, and the
failures. It replaces the step 3.4 drill and consumes no lab slots. The
[step 3.9 lab check](#live-check) shows a new generator ID after a product pod
replacement. The [product image and Deployment](#product-deployment-and-seeding-gate-step-39)
(step 3.9) and the order image and Deployment (step 4.9) start `quarkus-run.jar`
directly. They use the owning service's ServiceAccount and set
`SHARDSHOP_ID_REGISTRY_UID` from the identity ConfigMap. The images contain no
kubectl, and no tool binaries are downloaded or stored in the repository.

## Java and Maven

Set `JAVA_HOME` to a JDK **25 or newer**. The SDKMAN `25-open` installation
works; on this machine it reports `25+36-3489`. For example:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25-open"
```

The lock records an official macOS arm64 OpenJDK 25 archive and its SHA-256 as a
reference download. The build has no vendor or exact-patch restriction. Use the
same JDK's `bin/java` to launch the packaged applications.

Application and Flyway containers use the pinned Canonical OpenJDK 25 JRE on
Ubuntu 26.04. The [migration runbook](#database-schemas-and-migrations-step-23)
describes the Flyway image. The container runtime does not change the local
`25-open` installation or the JDK that `JAVA_HOME` selects.

Maven comes from the command line: use the `mvn` on your `PATH`. ShardShop keeps
Java **25** as its minimum and requires Maven **3.9.16** or newer, matching the
Quarkus plugin's declared minimum. Its Toolchains execution selects the
`JAVA_HOME` JDK, so compilation and forked tests use the same JDK as Maven; see the
[JDK selection parameters](https://maven.apache.org/plugins/maven-toolchains-plugin/select-jdk-toolchain-mojo.html).
Compilation targets release 25 with preview features disabled, whichever JDK
builds it. These rules are inherited only by ShardShop's children.

## Build and run

Run from the repository root with `JAVA_HOME` already set. Always select a
ShardShop POM; do not invoke the repository root reactor. Build one application
with `-pl`; `-am` also builds the common, idgen and sharding libraries for product
and order:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am clean verify
# After cluster-create.sh and up.sh have prepared and deployed the lab:
kubectl --context kind-shardshop -n shardshop get configmap shardshop-routing \
    -o jsonpath='{.data.application\.properties}' > /tmp/shardshop-routing.properties
kubectl --context kind-shardshop -n shardshop create token shardshop-product \
    --duration=10m > /tmp/shardshop-product.token
kubectl --context kind-shardshop config view --raw --minify \
    -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' | base64 -d > /tmp/kind-shardshop-ca.crt
export SHARDSHOP_ROUTING_CONFIG=file:/tmp/shardshop-routing.properties
export SHARDSHOP_ID_REGISTRY_URL=$(kubectl --context kind-shardshop config view --minify \
  -o jsonpath='{.clusters[0].cluster.server}')
export SHARDSHOP_ID_REGISTRY_TOKEN_FILE=/tmp/shardshop-product.token
export SHARDSHOP_ID_REGISTRY_CA_FILE=/tmp/kind-shardshop-ca.crt
export SHARDSHOP_ID_REGISTRY_UID=$(kubectl --context kind-shardshop \
  -n shardshop get configmap shardshop-generator-identity -o jsonpath='{.data.registryUid}')
# Every successful reservation in this host example permanently consumes one real slot,
# even if the application subsequently fails or exits immediately.
"$JAVA_HOME/bin/java" -jar microservices/shardshop/shardshop-product/target/quarkus-app/quarkus-run.jar
```

Verify all six one at a time, without installing anything. Product/order run their
packaged startup tests during `verify`; the loop also launches the four packages
that do not generate IDs:

```bash
for module in \
  shardshop-product \
  shardshop-order \
  shardshop-ledger \
  shardshop-workload/shardshop-product-seeder \
  shardshop-workload/shardshop-product-reader \
  shardshop-workload/shardshop-order-producer
do
  mvn -B -ntp -f microservices/shardshop/pom.xml -pl "$module" -am clean verify || exit 1
  case "$module" in
    shardshop-ledger|shardshop-workload/*)
      "$JAVA_HOME/bin/java" -jar \
        "microservices/shardshop/$module/target/quarkus-app/quarkus-run.jar" || exit 1
      ;;
  esac
done
```

Command-mode tests start each application through Quarkus. Sharding tests CDI
injection of the configured topology and router; idgen tests injection of its
singleton ID generator. Each library owns its test configuration, and
product/order each verify packaged startup. JaCoCo reports go to each Java module's
`target/site/jacoco/`. JVM packages live in `target/quarkus-app/`;
deploy that entire directory. All six applications start with `quarkus-run.jar`.
Product and order then reserve their generator ID, as in the host example above.

Product/order load the external routing file through `quarkus.config.locations`,
retaining the `SHARDSHOP_ROUTING_CONFIG` override and default path
`file:/etc/shardshop/routing/application.properties`. Their eager CDI producers
validate the snapshot before the command runs. The Maven plugin uses only the
bundled `application.properties` during augmentation, so packaging does not need
a deployed snapshot. Production startup still fails for a missing file, missing
shard/region keys or an invalid topology; packaged integration tests cover those
failures and a valid external snapshot.

Step 0.3 adds scoped dependency/plugin checks, SBOMs, pinned test images, and
integration-test configuration (commands below). Step 0.1 pins the
user-authorized kind 1.36.4 fallback and Canonical OpenJDK 25 JDK/JRE images on
Ubuntu 26.04, and the lock records their verified digests.
Steps 1.1–2.1 create the cluster, as the
[cluster section](#local-kubernetes-cluster-operator-and-databases-steps-1121)
describes. Step 3.9 builds the [application images](#application-images).

## Dependency and test validation

The scoped Quarkus **3.40.1 platform BOM** manages application/test dependencies.
The local parent aligns inherited JUnit **6.1.3** and Mockito **5.21.0** with that
platform, overrides Netty to **4.1.139.Final**, and pins all 17 build/report
plugins. Plugin security overrides are confined to their own classpaths; the
official Quarkus plugin's three XML-library exceptions are recorded in
[the version policy](VERSIONS.md#quarkus-plugin-xml-libraries). Comments in the
parent POM explain the aligned JUnit/Netty/Mockito/Lombok properties and name the
advisory each plugin override fixes.
No application dependency is added merely because the BOM manages it.

Surefire runs `*Test` during `test`. Product/order each run twenty-four packaged routing, generator and reservation-pin
startup cases through Failsafe during **every `verify`**, without a profile or
infrastructure. Opt-in `-Pintegration` runs other `*IT` tests and excludes those
already executed routing cases. Ordinary tests disable automatic Dev Services.
Surefire/Failsafe supply Mockito's startup agent through their plugin dependencies;
common and idgen declare Mockito for their controlled-source tests.
Product and workload clients declare Mockito for their controlled IO tests. `shardshop-sharding`
tests its router against golden vectors whose expected shards were computed
independently. PostgreSQL 18.6 and RabbitMQ 4.3.6 test-image
properties use the lock's immutable digests. Only the `shardshop-testing`
scenarios start a container, and only with `-Pintegration`
([step 3.10](#integration-tests-with-testcontainers-step-310)).

Idgen and sharding retain the test JVM option
`--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`. On 2026-10-06, removing their
two POM `argLine` properties and running the command below on OpenJDK
**25+36-3489** with Quarkus **3.40.1** reproduced ten warnings, five per library:
`Could not get access to jdk.internal.module API` and `FallbackModulesReconfigurer`
failures to open `java.lang` to `org.jboss.threads`. Tests still passed; restoring
the option eliminated those bootstrap warnings.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
```

For a fast unit-only loop or explicit integration check:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am clean test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am -Pintegration clean verify
```

Add the `audit` profile to write dependency and plugin reports for one
application:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am -Pintegration,audit clean verify
```

It writes `effective-pom.xml`, `dependencies.json`, `plugins.txt`, `sbom.json`
and `sbom.xml` to that application's `target/audit/`. The SBOM goal needs Maven
online mode; with `-o` it is skipped with a warning. Unit coverage is under
`target/site/jacoco`; integration coverage uses `jacoco-it.exec` and
`target/site/jacoco-it`. `-Djacoco.skip=true` works for both runners.

After a change to common or another shared library, run the six-application
audit. It builds all six applications with their libraries and writes the same
reports for each application:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger,shardshop-workload/shardshop-product-seeder,shardshop-workload/shardshop-product-reader,shardshop-workload/shardshop-order-producer \
  -am -Paudit clean verify
```

## Shard inventory and routing

[`infra/shards.yaml`](infra/shards.yaml) is the only maintained list of shards.
Its order defines routing indexes; each entry has an immutable home region. The
initial inventory is:

```yaml
shards:
  - name: shard-a
    region: US
  - name: shard-b
    region: EU
  - name: shard-c
    region: ASIA
```

[`infra/helm/shardshop/`](infra/helm/shardshop/) contains shared templates for
clusters, databases/roles, migration Jobs, and routing configuration. Common
settings live in `values.yaml`; kind storage/placement settings live in
`kind-values.yaml`. Helmfile syncs real Helm releases for their lifecycle,
preserving existing database names and PVCs. Namespace, credential Secrets and
allocator registry/identity remain outside Helm release ownership. There are no per-shard directories,
committed generated manifests, or per-shard Helm releases. The separate ledger
is excluded from the shard list. Names must start with a lowercase letter, contain
only lowercase letters, digits and hyphens, end in a letter/digit, and fit 44 characters.
The chart bounds the migration hook release prefix; stream, inventory index and
revision identify each attempt.
Empty/duplicate or malformed names, `ledger-db`, and missing/invalid regions fail
before deployment. Regions use only uppercase `US`, `EU`, and `ASIA`; a custom
inventory may assign multiple shards to one region. The chart adds the
`shardshop.javacraft/region` label to each shard Cluster and its inherited metadata.
These are business regions in the local lab, not separate geographic locations.

Preview without Kubernetes access using native Helm. These repository-root commands
use the same shared chart, environment values and ordered inventory as the lifecycle:

```bash
chart=microservices/shardshop/infra/helm/shardshop
environment=${SHARDSHOP_ENV_VALUES:-$chart/kind-values.yaml}
inventory=${SHARDSHOP_INVENTORY:-microservices/shardshop/infra/shards.yaml}
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=infrastructure
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=migration --set-literal migrationShard=shard-a --set-literal migrationStream=catalog
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=migration --set-literal migrationShard=shard-a --set-literal migrationStream=ordering
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=migration --set-literal migrationShard=ledger-db --set-literal migrationStream=ledger
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=routing
```

The migration preview selects `migrationShard` and `migrationStream` explicitly.
Its Job, SQL ConfigMap, migrator Secret and component names follow the stream;
both shard streams receive `shardRegion`, `shardIndex` and `shardCount`. The ledger
stream has no shard placeholders or region labels. The chart rejects ledger
streams on shards and shard streams on `ledger-db`. Previews do not apply resources
or run migrations. `cluster-create.sh` provisions all three schemas and their roles;
`up.sh` runs catalog on every shard, then ordering on every shard, then ledger
once, and waits for current-generation ordering outbox publications before
activating routing.

For application or seeder previews, select `render=applications` or `render=seeding`
with the same Helm arguments, provide a valid 1–32 character `run` and each required
`images.<application>` digest reference using `--set-literal`. Normal deployments
use Helmfile's releases and revision-owned migration/seeder hooks through `up.sh`.
`SHARDSHOP_INVENTORY=/path/inventory.yaml` selects another inventory;
`SHARDSHOP_ENV_VALUES=/path/values.yaml` replaces the kind overrides.

`cluster-create.sh` provisions the declared shards and ledger. `up.sh` checks current-generation
cluster/database readiness, migrates sequentially, then publishes `shardshop-routing`.
That ConfigMap contains `application.properties` with aligned
`shardshop.routing.shards` and `shardshop.routing.regions` lists. Its `version` key
remains SHA-256 of the comma-joined UTF-8 names; `regionVersion` is SHA-256 of the
comma-joined UTF-8 `name=REGION` pairs in inventory order. Before running any
migrations, `up.sh` creates the immutable `shardshop-migration-topology` reservation
with both hashes and checks it against the invocation snapshot.
Both existing ConfigMaps must contain matching `version` and `regionVersion`
values. The scripts refuse missing hashes or a changed saved or published topology
or region mapping; absent ConfigMaps are allowed for initial bootstrap.
The saved reservation survives partial migrations and publication failures;
matching reruns reuse it. Publication is atomic;
a failed migration leaves the previous routing configuration intact. Startup and
migration snapshot the input inventory for the duration of each run.

Product and order import `/etc/shardshop/routing/application.properties` at startup;
future Deployments must mount the ConfigMap there. Local JAR launches copy
its properties to a file and set `SHARDSHOP_ROUTING_CONFIG` as shown above. The Java
library requires names and regions, rejects invalid entries or mismatched list
lengths, and keeps an immutable list of shards with their regions. It routes the
unsigned SHA-256 digest of a positive decimal ID modulo the list size.
Missing or invalid configuration fails startup. The initial three-shard vectors are
unchanged.
Applications do not watch cluster health or reload routing while running; a failed
primary remains on the same shard, behind the same `-rw` Service.

Adding a shard means adding one inventory entry; all corresponding resources and
routing settings are generated. Changing membership **or order** changes ownership
of existing IDs. `up.sh` refuses a list that differs from an already
published version; a changed published region assignment is also refused.
Schema V1 is independent of the unchanged version-2 ID-routing algorithm.

For a different topology, reset this disposable lab:

1. Stop the applications and workloads.
2. Retire all old backups, broker data and other replay inputs. The new lab gets a
   new generator registry, thus it can issue IDs that old data already uses
   ([generator identity](ARCHITECTURE.md#generator-identity-across-processes-and-restarts)).
3. Run `bash microservices/shardshop/scripts/cluster-destroy.sh` to delete the lab
   and its data, volumes and kind kubeconfig.
4. Run `cluster-create.sh` to create the foundation and initialize the allocator
   after applying its admission guard, then `up.sh` to migrate, publish the new
   routing and seed the deployed release.
5. Export the new configuration for any separate local application launches.

Online data redistribution and rolling topology changes are outside this
implementation; deleting the routing ConfigMap alone would bypass the guard
without moving any data.

## Local Kubernetes cluster, operator and databases (steps 1.1–2.1)

The lab runs on kind, and everything through the final acceptance run works on
this one machine; the AWS track with Terraform (PLAN milestone 7) is optional and
comes later. Take `docker`, `kind`, `kubectl`, `helm`, `helmfile`, `skaffold`,
`openssl`, and `jq` from your `PATH`, like Maven; this project never downloads or pins tool binaries. It was tested with
Docker Desktop 4.92.0, kind 0.33.0, kubectl 1.36, Helm 4.3.0, and jq 1.7.1.
The revised workflow additionally requires Helmfile 1.8.0 and Skaffold 2.25.0;
install tools on `PATH` and follow [VERSIONS.md](VERSIONS.md) for qualification.
The repository contains their declarative configuration, not downloaded binaries.
The four lifecycle commands require no Python runtime. The node image's advisories were reviewed on
2026-09-28 and accepted for this loopback-only, disposable lab. Give Docker
Desktop's VM at least 12 GB of memory (Settings → Resources); both startup scripts stop early
if it has less. Migrations also require Docker's Buildx plugin and Docker Scout:
`up.sh` builds and scans the OpenJDK Flyway image before mutating the cluster,
then loads it into every node of the prepared kind cluster.

[`infra/kind.yaml`](infra/kind.yaml) defines one control plane and three workers,
one per instance of each three-instance shard, on the digest-pinned Kubernetes
1.36.4 node image, with the API server on 127.0.0.1. kind cannot add nodes, so a
cluster created with fewer workers must be deleted and recreated once.
`up.sh` compares the retained node names, roles, image pins and API port binding
before starting stopped containers. It rejects other kind creation settings,
including changed networking, mounts or ports; it never recreates retained nodes.
Manifests follow the [portability rules](PLAN.md#portability-beyond-kind): a
shared chart in `infra/helm/shardshop/` with kind settings in `kind-values.yaml`.

Create the cluster foundation once, then deploy applications from the repository root:

```bash
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
```

`cluster-create.sh` owns creation. It first caches the repository and microservices
parent POM metadata with non-recursive `install:install-file` calls, without
building either parent reactor. It builds, verifies and installs only the safety observer and its shared common
test-helper dependencies with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true clean install -DinstallAtEnd=true`,
validates the pinned kind configuration, creates the four nodes, and prepares the namespace, credentials,
CloudNativePG, databases, RabbitMQ and allocator identity. It verifies DNS,
admission, database readiness and the broker's effective delivery topology.
It does not build/load application images, run Flyway or deploy
application workloads. On an existing cluster it resumes stopped nodes and safely
reconciles supported infrastructure changes after retained-state guards pass; it
never reinitializes retained allocator history.

Fresh allocator initialization belongs exclusively to this creation invocation;
`up.sh` never creates a cluster or initializes/replaces allocator history. If creation
fails after nodes exist, inspect its failed phase. Once both allocator objects exist,
`cluster-create.sh` can validate retained state and reconcile the foundation. Missing/partial
allocator state fails closed: recover independent backups for retained data, or
explicitly destroy and recreate a demonstrably unused failed bootstrap. Never treat
an existing cluster as fresh to bypass this guard.

`up.sh` requires the prepared cluster and reports the creation command if it is
missing. It resumes stopped nodes, builds current sources, validates/scans images,
checks the prepared infrastructure, migrates and deploys the applications. It never
creates namespaces or credentials or syncs infrastructure releases.
Every invocation replaces the product and reader pods, even when code and
configuration are unchanged. Helm applies a fresh shared restart annotation to
both Deployment templates; its seeder hook and final acceptance gate verify the
new workload.
Each restarted product JVM consumes a fresh generator ID; the retained lab has
1,023 non-reusable slots, so an ordinary two-replica rollout uses at least two.
Changed code is rebuilt and deployed through this same command.

Both creation and startup snapshot the shared chart, database migration files, inventory, environment
values, kind configuration, operator chart/values and Helmfile configuration once per
invocation; startup also snapshots Skaffold configuration. On retained clusters they
start only stopped nodes. After API readiness, both reject
changed published/reserved topology or invalid retained allocator state before
protected mutations. For Helm-owned databases, the retention check compares
protected settings against the stored infrastructure release manifest and checks
the live declared values for drift. API-added defaults are not treated as requested
changes. Missing/mismatched Helm ownership or history, pending revisions and a
revision changing during the check fail closed; completed failed revisions remain
retryable when their protected settings match. `cluster-create.sh` creates the namespace and missing credential
Secrets outside Helm, then syncs the infrastructure stage of
[`infra/helmfile.yaml.gotmpl`](infra/helmfile.yaml.gotmpl). Helm waits up to 600
seconds for each release, installs CloudNativePG 1.30.1 from the vendored chart,
and reconciles the shared infrastructure chart. `cluster-create.sh` creates the `shardshop`
namespace with the `restricted` Pod Security label; the infrastructure release creates three
three-instance clusters (`shard-a`/US, `shard-b`/EU, `shard-c`/ASIA), and the single-instance
`ledger-db` cluster, plus the persistent broker described in
[step 4.3](#rabbitmq-topology-and-delivery-policies-step-43).
It waits up to 300 seconds for every database cluster to be
Ready and for every database pod to use the requested PostgreSQL image, operator
image and resource budget, accounting for Kubernetes defaulting an omitted resource
request to its limit. Explicit requests still take precedence. It verifies every
`-rw` Service, every configured PostgreSQL setting on every instance (including
pending restarts), and the exact
two streaming quorum standbys of each shard. It also creates random password Secrets only if absent: `catalog-migrator`,
`ordering-migrator`, `product-app`, `order-app`, and `ordering-cdc` are shared by
the shards; `ledger-migrator` and `ledger-app` belong to the separate ledger.
It applies CNPG `DatabaseRole`s and `Database` resources for catalog, ordering,
and ledger, waits for roles at their current generation, then waits up to 180
seconds each for a Database's current generation and `applied` status. `up.sh`'s Flyway
Jobs then create tables and other schema contents (step 2.3 below).
Resource budgets, PostgreSQL settings and reviewed operator/same-major PostgreSQL
updates can converge in place. Changes to storage, placement, bootstrap settings,
replica count or PostgreSQL major version fail before apply; the local storage
class does not support expansion. New schemas and roles use current-generation
operator checks. `up.sh` observes these infrastructure readiness checks before migration, application
deployment and the final `kind-release | kind-deployment` acceptance; standalone `kind-deployment` remains available for compatibility and
focused reruns.
`cluster-create.sh` owns node creation, infrastructure reconciliation, credentials
and fresh allocator initialization; `up.sh` owns current application builds,
Maven/Scout, migration and application image delivery. Both may resume retained
nodes and share snapshot, lock, rendering, verifier and readiness functions in
`scripts/lib/lifecycle.sh`. This sourced helper is not another entry point.
`down.sh` and `cluster-destroy.sh` use it only for the lock and the signal traps. Skaffold owns image
build configuration. Helmfile owns release dependencies and phase hooks; Helm
owns declarative resources and migration/seeder hook Jobs. Kubernetes and
CloudNativePG own reconciliation. Namespace, DNS probes and immutable allocator
identity are rendered by Helm but applied as bootstrap resources outside release
ownership. The opt-in Maven `kind` profile owns the configuration, retained-state,
admission, DNS and database assertions, including resource quantity comparison.
There is no quantity parser or embedded Python in `up.sh`. Flyway continues to own
schema migrations and the Java applications own allocation, seeding and reader gating.

Both scripts run safety scenarios at their phase boundaries using the same private
snapshot throughout. Creation uses `shardshop.infrastructureOnly=true` to disable
application reactor-order dependencies for the observer build and direct Failsafe
gates, so it neither builds applications nor requires previously installed
application POMs. Default builds keep those dependencies. Startup's initial
`clean install -DinstallAtEnd=true` includes all verification and installs
the successful reactor artifacts into the local Maven cache. It then invokes Failsafe
directly against those current packages without repeating the build.

### Stop and resume the entire cluster (step 3.18)

```bash
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/up.sh
```

`down.sh` stops every ShardShop kind node, including the control plane and all
workers, with one `docker stop --time 10` call. Docker stops the nodes in parallel.
It sends SIGKILL to a node process that did not stop after ten seconds. It returns
an error if a node does not stop. Docker does not change a node that is already
stopped, and a missing cluster needs no Docker call. A failed stop returns nonzero
and releases the lifecycle lock. Inspect the nodes and run `down.sh` again. If
Docker Desktop does not respond, `down.sh` waits until you stop it with Ctrl+C. `up.sh` resumes the same containers, checks the prepared infrastructure and
redeploys applications, preserving databases, PVCs, Secrets and allocator identity/history.
Each restarted product JVM still reserves a new generator ID.

### Permanently destroy the cluster

```bash
bash microservices/shardshop/scripts/cluster-destroy.sh
```

This command immediately and irreversibly deletes the fixed `shardshop` kind
cluster, running or stopped, with all its databases, PVC data, Secrets, Helm
releases, Kubernetes configuration and generator history. Use `down.sh` when you
want to retain data. The script takes the lifecycle lock and runs
`kind delete cluster --name shardshop` with kind's Docker provider. kind removes the
node containers with `docker rm -f -v`, which also removes their anonymous `/var`
volumes. Then kind removes the `kind-shardshop` context, cluster and user entries
from the selected `KUBECONFIG` (or the default kubeconfig). kind returns an error if
one of these steps fails. A missing cluster is safe to repeat. Docker keeps a volume
that another container uses. If Docker cannot remove a node volume, `docker volume ls`
still shows it after the deletion. Remove it with `docker volume rm`.
Exported kubeconfig copies outside that selection and external backups must be
managed separately.

The supported lab stores its data in Docker node volumes. `infra/kind.yaml` adds no
host mounts. Destruction preserves repository
source/configuration, Docker Desktop, unrelated containers, shared images/build
caches and the shared kind network. Run `cluster-create.sh` and then `up.sh` to create a fresh lab;
previous entity IDs and allocator history cannot be recovered by restarting it.

### Recover retained data or failed startup

A failed `up.sh` can leave completed migrations and a partially updated release.
Inspect its bounded diagnostics and the retained migration/seeder Job logs before
retrying. Correct the source configuration or forward migration that caused the
failure, then run `bash microservices/shardshop/scripts/up.sh` again. Flyway validates
actual schema history on every run; the seeder retries the same durable keys and
payloads, preserving existing entity IDs. Do not edit an applied versioned migration,
clear Flyway history, or delete topology guards to force a retry.

For a stopped lab, run `up.sh` to resume the retained nodes. If prepared
infrastructure needs reconciliation, run `cluster-create.sh` first; it validates
retained topology and allocator state and never resets their history. Neither
command reconstructs deleted rows or lost storage. Do not destroy the cluster as a
recovery step when those data must survive.

Missing, partial or mismatched allocator state requires an independent record of
the original registry identity and latest high-water mark. Keep ID-producing
services stopped until those facts are established. An older database dump is not
allocator recovery evidence. The admission guard pins the original registry UID,
so creating a replacement ConfigMap or applying an old snapshot cannot safely
repair it. There is no automated allocator reconstruction or supported generic
restore command; follow the [allocator recovery constraints](#generator-allocation-at-jvm-startup-steps-34-and-311)
and preserve the latest mark without rollback.

Coordinated database backups, isolated restores and restore audits remain PLAN
steps 6.3–6.4; these lifecycle commands create no backups. If storage is lost,
retain any surviving volumes and independent recovery artifacts before attempting
restoration. The [backup policy](PLAN.md#7-replication-and-backup-policy) requires
external dumps with routing/schema identities and separate latest allocator
history, and the [restore audit](ARCHITECTURE.md#orders-lost-by-asynchronous-failover-or-restore)
defines reconciliation for future order/ledger state. Without usable recovery
artifacts, recovery of deleted history is unavailable. A deliberate fresh lab
requires retiring all prior emitters, data and replay inputs, then running
`cluster-destroy.sh`, `cluster-create.sh` and `up.sh` in that order; it starts a new
identity history rather than recovering the old one.

### One-time adoption of an existing apply-managed lab

Fresh labs need no adoption. A retained lab created by the earlier `kubectl apply`
workflow must explicitly transfer the matching infrastructure and application
resources to `shardshop-infrastructure` and `shardshop-application` before the first
Helmfile sync. Existing immutable SQL ConfigMaps whose names and contents match the
current migration snapshot also transfer to `shardshop-migrations`; historical
ConfigMaps outside that exact set are not adopted. Normal `up.sh` does not use `--take-ownership` or steal resources
from another Helm release.

First run the retained topology, allocator and infrastructure safety checks against
the same chart/inventory snapshot. Inspect current Helm ownership and compare the
exact resource names/kinds in the rendered infrastructure/application manifests
and current immutable SQL ConfigMaps. Stop if any resource already belongs to a
different Helm release or another declared manager. Check the full resource set
before changing any metadata. For only that reviewed
resource set, add `app.kubernetes.io/managed-by=Helm`,
`meta.helm.sh/release-name=<matching-release>` and
`meta.helm.sh/release-namespace=shardshop`. Use metadata-only JSON patches that
test the observed UID and resourceVersion and preserve other labels/annotations;
never change specifications or data during adoption. This preserves resource UIDs
and PVC bindings. The application set includes `shardshop-routing`.
Do not adopt the namespace, credential Secrets, generator registry/identity,
operator-created Pods/PVCs, or legacy migration/seeder Jobs. The existing `cnpg`
release retains its own ownership. Keep an inventory of UIDs, PVC bindings and
allocator high-water before and after the transfer, then run `up.sh`.

ShardShop releases use Helm's three-way merge (`serverSide: false`) for the
previously apply-managed resources. The existing `cnpg` release retains
server-side updates.

### Kind verification snapshot (step 3.15)

Standalone kind checks require a snapshot of the rendered inputs from the running
lab. Prepare it once and retain it until all selected checks finish:

```bash
snapshot=$(mktemp -d)
cp -R microservices/shardshop/infra/helm/shardshop "$snapshot/chart"
cp microservices/shardshop/infra/kind.yaml "$snapshot/chart/kind.yaml"
cp "${SHARDSHOP_ENV_VALUES:-microservices/shardshop/infra/helm/shardshop/kind-values.yaml}" "$snapshot/environment.yaml"
cp "${SHARDSHOP_INVENTORY:-microservices/shardshop/infra/shards.yaml}" "$snapshot/inventory.yaml"
probe_nodes=$(kind get nodes --name shardshop)
for mode in inventory kind-configuration infrastructure dns-probes; do
    case "$mode" in
        inventory) output=inventory.json ;;
        kind-configuration) output=kind.json ;;
        infrastructure) output=infrastructure.yaml ;;
        dns-probes) output=dns-probes.yaml ;;
    esac
    helm template shardshop "$snapshot/chart" --namespace shardshop \
        --values "$snapshot/environment.yaml" --values "$snapshot/inventory.yaml" \
        --set-string "render=$mode" --set-literal "probe.nodes=${probe_nodes//$'\n'/,}" \
        --set-literal "probe.run=check-$(openssl rand -hex 6)" |
        if [[ "$output" == *.json ]]; then sed '/^# Source:/d; /^---$/d'; else cat; fi >"$snapshot/$output"
done
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-infrastructure
```

The required snapshot files are `chart/`, `environment.yaml`, `inventory.yaml`,
`inventory.json`, `kind.json` and `infrastructure.yaml`; `dns-probes.yaml` is also
needed for `kind-dns`. The inventory is read from the snapshot. Keep `-Dshardshop.kind.snapshot="$snapshot"` on every standalone
invocation; the suite does not create or reconcile the lab.

The `kind` profile defaults to `kind-deployment`, which combines `kind-topology`
and `kind-workload`. It observes an already-migrated and deployed lab with two
Ready product pods and one running reader. With the snapshot above, run the default
suite separately from the Testcontainers `integration` profile:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot"
```

Disruptive scenarios require explicit selection; `kind-drill` combines
`kind-database-drill` and `kind-workload-drill`:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-drill
```

The property uses JUnit tag expressions (without Gherkin's leading `@`); a
selection matching no scenarios fails. Existing lifecycle gates remain selectable:
`-Dshardshop.kind.tags='kind-config | kind-nodes | kind-retention | kind-allocator | kind-infrastructure'`
selects those checks together. `kind-dns` creates short-lived probe Pods from the
snapshot's `dns-probes.yaml`, with cleanup on failure. Each probe has a 120-second
DNS execution limit and a 17-minute overall Pod deadline to accommodate cold image startup.
Every node must resolve cluster DNS successfully before provisioning continues.
`kind-admission-bootstrap` is for fresh initialization only and requires
`-Dshardshop.kind.fresh=true`; do not use it on a retained lab. Migration safety
checks are also selectable: `kind-migration-reservation`, `kind-migrations-idle`
and `kind-publications`. Helmfile runs them before migrations or routing/application
publication. The idle guard rejects active, pending or terminating Flyway Jobs,
including legacy Jobs. Publication observation waits up to 180 seconds for every
inventory Publication to be applied at its current generation. The basic standalone
snapshot above is sufficient for these observations and `kind-deployment`. Normal builds and `-Pintegration` do not access the kind lab. Cucumber reports are under
`shardshop-testing/target/kind-reports/`.

The workload credential gate accepts only the documented reader/seeder HTTP
settings with literal typed values. It rejects unknown environment variables,
JVM configuration overrides, command/argument overrides and executable hooks or
probes. Product URLs must contain no user information, query or fragment.
Reader images built before periodic digest reporting need a rebuild and deployment
for verification to survive startup-log rotation.

Both drill families share the `shardshop-kind-drill` ConfigMap lock. Recovery
registers before each temporary mutation and runs in reverse order on failure or
normal completion, with a JVM termination hook for ordinary shutdown. Fence updates
atomically test the observed Cluster UID and resource version; concurrent changes
stop the mutation rather than overwrite another actor's fence. If recovery
fails, the lock stays and the report identifies the remaining temporary changes.
After an unclean process termination, ensure no drill is running, inspect the
report and lock, restore fenced instances and wait for database/product readiness,
then remove only the drill's temporary Jobs, SQL objects and slots before releasing
the lock. Never restore an older generator registry or lower `highWaterMark`.

After the last check, remove the local snapshot:

```bash
rm -rf -- "$snapshot"
```

Each `up.sh` invocation rebuilds and qualifies the enabled images before cluster mutation.
On each run, kind refreshes and selects the `kind-shardshop` context in the caller's
`KUBECONFIG` files, or `~/.kube/config` when unset. The recovered endpoint remains
available to subsequent `kubectl` commands and standalone Maven checks. The script
passes that context on every command and never deletes a cluster, namespace, or
volume. Check the result:

```bash
kubectl --context kind-shardshop get nodes
kubectl --context kind-shardshop get storageclass
kubectl --context kind-shardshop get namespace shardshop --show-labels
kubectl --context kind-shardshop get crds
helm --kube-context kind-shardshop -n cnpg-system list
kubectl --context kind-shardshop -n cnpg-system get deployment cnpg-cloudnative-pg
kubectl --context kind-shardshop -n shardshop get clusters,pods,pvc -L shardshop.javacraft/region
```

Stop the lab when you do not need it, to free Docker memory:

```bash
bash microservices/shardshop/scripts/down.sh
```

It stops the four node containers without deleting them; volumes, databases and
cluster state stay inside. PostgreSQL shuts down cleanly within seconds, and Docker
stops the rest after its default 10-second timeout. Rerun `up.sh` to start the same
containers; it waits for the API server and for every `-rw` Service, because the
stored Ready statuses predate the restart. Before it starts the nodes, it builds,
tests and scans the images again, thus it needs network access for Docker Scout.
During step 3.14 qualification, an already-started lab took approximately four
minutes when images did not change and seven minutes when it loaded new images;
those measurements precede the migration/deployment phases added in step 3.16.
Only `kind delete cluster --name shardshop` deletes the lab's data.

A node start can give the nodes new IP addresses. `up.sh` restarts kindnet after
starting any stopped node, then checks DNS from all four nodes and verifies both streaming
standbys of every shard. If automatic repair or the database-parameter wait fails,
`up.sh` stops with recovery guidance; retained data and allocator state remain
untouched. CoreDNS runs on the control plane, so a missing route prevents Service
resolution and can stop streaming, with an error such as
`<cluster>-rw does not accept connections from <pod>`. Each node must have a route to
the pod network (`CIDR`) of each other node:

```bash
kubectl --context kind-shardshop get nodes -o 'custom-columns=NAME:.metadata.name,IP:.status.addresses[0].address,CIDR:.spec.podCIDR'
docker exec shardshop-control-plane ip route
```

If automatic kindnet repair does not restore the route, inspect the reported node and
resolve the condition before rerunning `up.sh`. To refresh a stale kindnet process
on a running node, replace only that node's kindnet pod:

```bash
kubectl --context kind-shardshop -n kube-system delete pod -l app=kindnet --field-selector spec.nodeName=shardshop-control-plane
```

Do not delete allocator state or
database volumes as a workaround.

[`infra/helm/cnpg/`](infra/helm/cnpg/) holds the official CloudNativePG chart
0.29.1, verified against the lock's SHA-256 and its cosign signature, and
`values.yaml`, which pins the operator by tag and digest. The chart uses that one
reference for both the operator image and `OPERATOR_IMAGE_NAME`, and it keeps its
CRDs if the release is ever uninstalled, so the database clusters survive. The
operator installs before the shared database resources so its APIs and webhooks are
ready for database resources. To upgrade, review the lock's support deadline, then
vendor and verify the new chart and update `values.yaml` and the lock together;
a later cloud target installs the same chart and values.

Each shard runs PostgreSQL 18.6 with one primary and two standbys, each on a separate
worker with its own 2 GiB PVC. The nine pods share three workers; anti-affinity is
scoped to each shard. Each shard is an independent database: physical replication
never copies rows between shards. The version tag lets CNPG identify PostgreSQL
upgrades; the accompanying digest pins the image bytes. The chart declares the
database, resources and replication settings; kind values select `standard`
storage and require placement on distinct workers for every CNPG `Cluster`,
so additional inventory entries need no placement changes. No pod security context is
hard-coded. CNPG generates each shard's `<shard>-app` Secret at runtime for the
`shardshop_owner` bootstrap role, which owns only the `shardshop` database. When a
cluster is created, its `postInitApplicationSQL` revokes `TEMPORARY` on the
database and all access to schema `public` from `PUBLIC`; `CONNECT` stays. No
passwords are committed or printed; catalog and ordering roles are provisioned
by the database layer (steps 2.3 and 2.5).

Step 2.1 reduces each database's memory limit from 1 GiB to 512 MiB after
measuring the tiny-data lab; requests stay at 512 MiB and 250m CPU, with a one-CPU
limit. The nine databases reserve 4.5 GiB in total. This measurement precedes the
ledger, broker and application workloads: retain the 12 GB VM minimum and
remeasure under load before treating these limits as a full-lab capacity budget.
After all three drills on 2026-09-29, the database pods used about 563 MiB of
working-set memory and the four kind nodes used 2.78 GiB. The
[changelog](CHANGELOG.md#step-21) records the evidence. The estimate for the complete lab is 6-10 GiB in steady
state. Each kind node reports the full VM as its capacity, thus an overcommitted
lab shows OOM kills or swapping, not Pending pods.

The default quorum waits for durable WAL acknowledgement from any one standby.
One absent standby leaves writes available; two absent standbys block synchronous
commits. Logical-slot synchronization prepares the shard for CDC, which waits for
both standbys. Ten replication slots and WAL senders leave room for the two
physical replicas, CDC and recovery. `max_slot_wal_keep_size=512MB` bounds slot
retention at checkpoints, while `max_wal_size=256MB` fits the small lab volumes.
`wal_keep_size=128MB` replaces CNPG's 512MB default: the replication slots already
retain the WAL each standby needs, so a large extra floor only fills the 2 GiB volume.
These are not hard disk-usage caps: monitor free space and lag, and recover an
invalidated slot explicitly after an outage that exceeds the retained WAL budget.

After catalog and ordering migrations, prepare the
[kind verification snapshot](#kind-verification-snapshot-step-315) and run the
nondisruptive topology/routing check on every inventory primary:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-topology
```

It verifies every shard's writable primary, streaming quorum standbys, independent
database lineage, `-rw`/`-ro` Service endpoints and distinct Bound PVCs/backing
volumes. It compares PostgreSQL SHA-256 arithmetic and the deployed seller and
buyer placement constraints against the shared golden fixtures used by Java.
Counts come from the snapshot inventory; this check creates no SQL objects.

Run the reversible database drill explicitly in this disposable lab. It defaults
to the first inventory shard; select another shard by inventory name:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-database-drill \
    -Dshardshop.kind.drillShard=shard-b
```

A probe row must reach both standbys, writes on standbys must fail, and the probe
schema must remain absent from peer shards. The drill requires the lab's three
instances per shard. It checks failover-slot synchronization and WAL retention,
replaces one standby pod while preserving its PVC, and temporarily fences one then
both standbys to observe available and blocked quorum commits. Reads and writes
are disrupted during the drill. It restores standbys and removes its temporary
SQL objects and slots without deleting a PVC. The shared lock and failure recovery
rules in the [snapshot runbook](#kind-verification-snapshot-step-315) apply.

## Separate ledger database (step 2.2)

[`clusters.yaml`](infra/helm/shardshop/templates/clusters.yaml) adds a separate
CloudNativePG cluster with one PostgreSQL instance, separate from the nine shard
pods. It uses the same pinned PostgreSQL image and its own 2 GiB PVC. The kind
values place it on a worker using `standard` storage; `cluster-create.sh` creates it and
waits for readiness alongside the shards. Its 250m CPU/512 MiB memory requests
and one-CPU/512 MiB limits match the tiny-data baseline. The ten databases now
request 5 GiB of memory in total; remeasure when workloads arrive.

Connect to database `ledger` through the internal Service
`ledger-db-rw.shardshop.svc:5432`. CNPG generates `ledger-db-app` at runtime for
bootstrap owner `ledger_owner`; application clients use the separate `ledger-app`
Secret from step 2.6. Secret values are never committed or printed. TLS clients
can mount only `ca.crt` from `ledger-db-ca` and use `sslmode=verify-full`. The bootstrap owner
owns only the database; `ledger_schema_owner` owns schema `ledger`, its objects,
and its Flyway history. Step 2.6 below describes its migrations and runtime grants.
No application is wired to the bootstrap owner. Like the shards, the cluster
revokes `TEMPORARY` and access to schema `public` from `PUBLIC` when it is created.

The ledger uses ordinary WAL and local durable commits, with no synchronous
standby requirement or CDC setup; its planned outbox relay polls its own tables.
It has **no database failover target**: ledger access stops during pod restarts
or worker outages. Its PVC preserves data across ordinary pod replacement, but
local-path storage cannot survive loss of its worker or volume and is not a
backup. Backup and restore arrive in step 6.3.

Inspect the database and its Service without exposing credentials:

```bash
kubectl --context kind-shardshop -n shardshop get cluster ledger-db
kubectl --context kind-shardshop -n shardshop get pods,pvc -l cnpg.io/cluster=ledger-db
kubectl --context kind-shardshop -n shardshop get service ledger-db-rw
kubectl --context kind-shardshop -n shardshop get endpointslices -l kubernetes.io/service-name=ledger-db-rw
```

Step 2.2's live checks use temporary restricted client Jobs, the generated Secret,
and TLS through that Service. They verify a committed row after replacing the
ledger pod, unchanged PVC/credential identities and startup reruns, then remove
their Jobs and SQL probe. The shard topology drill continues to target only the
nine shard instances. The [changelog](CHANGELOG.md#step-22) records the evidence.

`cluster-destroy.sh` removes the whole lab, including its storage and kind kubeconfig; kind storage is disposable and is not a backup.

## Database schemas and migrations (step 2.3)

No Maven module owns a schema: databases, roles and migrations belong to the
database layer in [ARCHITECTURE.md](ARCHITECTURE.md#database-change-management).
Keep all Flyway SQL simple and declarative: tables, constraints, indexes, grants,
and data changes only. Do not create SQL functions or triggers; use Java for
business logic and scripts for deployment checks.
`cluster-create.sh` prepares and reconciles the database layer; `up.sh` checks it and runs migrations:

- `cluster-create.sh` applies the shared declarative infrastructure: the clusters and their creation-time
  hardening, the schema roles, and each shard's `Database` resource,
  which creates schemas `catalog` and `ordering` with their respective owners.
  Its `Publication` resource selects only inserts into `ordering.order_outbox`;
  CNPG reconciles it after the migration creates the table. The shared
  [`databases.yaml`](infra/helm/shardshop/templates/databases.yaml) template
  renders the roles and `Database` for every inventory entry. The separate
  [`ledger-database.yaml`](infra/helm/shardshop/templates/ledger-database.yaml)
  declares `ledger-db-ledger`, schema `ledger`, and its four roles.
- `up.sh`'s Flyway Jobs fill the schemas by running each stream in
  [`database/`](database/) on every shard primary and then on ledger once. It uses
  the migration image that `up.sh` loaded; no separate Maven
  build, application JAR, or database superuser session is needed.

After `cluster-create.sh` prepares the foundation, the normal migration and
deployment command is:

```bash
bash microservices/shardshop/scripts/up.sh
```

Skaffold builds the migration image from its reviewed Dockerfile, pinned inputs
and `SOURCE_DATE_EPOCH=0`. `up.sh` scans it with the gate of the
[application images](#application-images), then its native kind adapter loads the
ARM64 image and checks each node's loaded platform manifest digest against the chart image pin. A build, scan or digest failure stops it before it registers the
image. Every enabled image must have the verified loaded platform manifest digest on every node
before `up.sh` creates SQL ConfigMaps or migration Jobs or applies a new release.

[`database/shard/catalog/`](database/shard/catalog/) is the catalog stream.
`V1__catalog.sql` creates all tables, including seller home regions, named region
and ID-placement checks, and regional comments. `R__catalog_grants.sql` holds the
complete privilege matrix; Flyway reapplies it whenever it changes. `flyway.toml` fixes the
schema, the history table `catalog.flyway_schema_history`, retries, and the 5-second connect, 30-second
socket, 15-second statement and 5-second lock timeouts. It also starts every
connection as `catalog_owner`, so the migrator login never owns an object.

The script runs the required `database/shard/` streams in the order `catalog`,
then `ordering`, followed by `database/ledger/ledger` once. For each shard stream it
renders the snapshotted files into an immutable
`<stream>-migrations-<content-hash>` ConfigMap, then runs one Job named from the
release, stream, inventory index and Helm revision per inventory entry from the
shared [`migrations.yaml`](infra/helm/shardshop/templates/migrations.yaml) and
[`_migrations.tpl`](infra/helm/shardshop/templates/_migrations.tpl) templates,
one shard at a time. Each Job uses the matching `<stream>-migrator` Secret and
stream component label. The template supplies the same `shardRegion`, `shardIndex`,
and `shardCount` Flyway placeholders for both streams from the inventory, and sets
`SHARD_NAME` and the shard's CA Secret. Kubernetes expands `FLYWAY_URL` using the
preceding environment variable. The ledger Job uses its content-addressed
ConfigMap, `ledger-migrator`, `ledger-db-ca`, and the `ledger-db-rw` endpoint/database `ledger`,
without shard placement settings. The migration readiness gates check the cluster
and Database's current-generation `applied` status before SQL runs. Each Job runs
Flyway OSS 13.8.1 on the pinned Canonical OpenJDK 25 runtime. Its
[`Dockerfile`](infra/images/flyway/Dockerfile) copies only Flyway's core and
PostgreSQL plugin libraries, the PostgreSQL JDBC driver and the licenses from the
pinned official distribution image;
its final base is `ubuntu/jre` on Ubuntu 26.04. The Job logs in as the stream's
migrator (`catalog_migrator` for catalog) over TLS with `verify-full` and the
cluster's CA. It has a 180-second deadline and no automatic pod retry. Helm stops
at the first failed hook, leaving later Jobs and the new routing/application
release unattempted. Successful databases stay migrated because migrations are
not a transaction across databases.
Correct the failure and rerun. Retained versioned migrations are immutable; fix
forward with another version. Never repair checksums automatically, bypass
validation, or clean a catalog that holds data. Normal Flyway configuration keeps
`clean` disabled.

Every `helmfile sync` creates revision-named migration hooks in stream/inventory
order. Helm waits for each weighted hook, so later SQL cannot start after a failure.
Before the migration release, Maven checks current infrastructure, allocator state,
topology reservation and the absence of active, pending or terminating Flyway Jobs.
If a caller stops while SQL is running, inspect the retained Job and wait for its
terminal result. Also inspect the affected release's Helm status and history:

```bash
helm --kube-context kind-shardshop -n shardshop status shardshop-migrations
helm --kube-context kind-shardshop -n shardshop history shardshop-migrations
```

Use `shardshop-application` instead if its deployment/seeder operation was
interrupted. A killed Helm client can leave `pending-install` or `pending-upgrade`
release bookkeeping even after all hook Jobs terminate. The idle guard does not
clear that Helm operation. Confirm no lifecycle/Helm process or hook Job is active,
then recover the release bookkeeping explicitly for the observed status before
retrying `up.sh`. Inspect existing resources and preserve database/PVC/allocator
state during that recovery; do not blindly delete release records or uninstall the
release. Once release bookkeeping permits a new revision, Flyway validates again.

Flyway history is authoritative on every invocation. Unchanged streams validate
and perform a no-op; repeatable grants rerun when their checksum differs, including
an A-to-B-to-A change. There is no cache of completed migration Jobs or generic
comparison of desired and live Job specifications. Successful migration hooks are
deleted after Helm observes completion; failed hooks retain bounded diagnostic
availability until their default 24-hour TTL. Inspect a failed Job before it
expires. SQL snapshots are immutable and remain content-addressed ConfigMaps.

Helmfile does not automatically roll back or uninstall a failed release. Previously
committed SQL is retained: correct the problem and rerun, using fix-forward
versioned migrations where required. Never repair checksums automatically, delete
history or PVCs to force validation, or invoke Flyway clean on retained data.
Run migrations and application deployment through `up.sh` so the Helmfile gates stay in effect.

`cluster-create.sh`, `up.sh`, `down.sh` and `cluster-destroy.sh` share the local exclusive directory
lock `${TMPDIR:-/tmp}/shardshop-up.lock`. Use the same `TMPDIR` for all four. A
concurrent call fails before changing the lab; retry it after the first finishes.
Ordinary failure and INT/TERM release the lock after the active command ends.
After SIGKILL or a machine failure, verify that no invocation remains active and
inspect retained Jobs before removing a stale lock directory.

After correcting the failure and any pending Helm bookkeeping, rerun with the
same inventory.
[Shard inventory and routing](#shard-inventory-and-routing) describes the topology
reservation, the routing publication and the export for local launches. Inspect
the migration resources:

```bash
kubectl --context kind-shardshop -n shardshop get jobs,pods -l app.kubernetes.io/name=schema-migration
kubectl --context kind-shardshop -n shardshop logs \
    -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=catalog,shardshop.javacraft/migration-target=shard-a \
    --all-containers=true --tail=100
kubectl --context kind-shardshop -n shardshop get databaseroles,databases
```

The migration image and Job both select UID/GID 10001. The Job uses a
read-only root filesystem, `HOME=/tmp`, no service-account token, and telemetry
disabled. That fixed ID is an exception to the
[portability rules](PLAN.md#portability-beyond-kind), which OpenShift's assigned
UIDs would need to replace.

### Catalog roles and tables

[ARCHITECTURE](ARCHITECTURE.md#roles) defines the catalog roles and their
memberships. `cluster-create.sh` generates catalog login passwords into the Secrets
`catalog-migrator` and `product-app`, shared by all three shards; neither is
committed or printed. The `order_app` credentials use the separate `order-app`
Secret.
Runtime logins do not own objects or have SQL privileges for DDL, temporary
tables or `SELECT` on the Flyway history.

The implemented V1 `catalog.sellers` holds positive `BIGINT` seller IDs, names, and immutable
`US`/`EU`/`ASIA` regions. Its checks require the deployed region and the exact
shard selected by the unchanged version-2 SHA-256 rule. A `CHECK` expression
uses PostgreSQL's built-in SHA-256, hexadecimal encoding, exact `NUMERIC`
conversion, and modulo operator, preserving all 256 digest bits. No custom SQL
function, trigger, or function execution grant is needed. `catalog.products`
references its local seller and stores a positive `BIGINT` ID, a name of at most 200
characters (not empty or only spaces), a non-negative `NUMERIC(19,2)` price
(excluding NaN), an uppercase three-letter currency, and `initial_stock` and
`stock`, with `0 <= stock <= initial_stock`. The product service sets `stock` to
`initial_stock` when it creates a product. `catalog.stock_reservations` keeps one
row per order and product, with the quantity the buyer ordered. The order service
reserves with `INSERT … ON CONFLICT (order_id, product_id) DO NOTHING` and,
only for a new reservation, conditionally decrement stock in the same Java-managed
shard transaction. Insufficient stock rolls back the reservation. Release
marks an active reservation released and restores its quantity in one transaction;
retries must change stock at most once. There are no ID sequences. Products
inherit the seller's region and store no independent region column. The local
seller foreign key and placement checks prevent creating products on a different
region's shard. Runtime roles cannot change a seller's region.

Step 3.6 adds company-name, product-description, unit-cost and creation-key
storage through `V2__catalog_creation.sql`, plus service-owned `seller_profits`
and immutable `profit_credits` identities. Legacy descriptions backfill from the
product name; legacy unit cost and initial USD/EUR balances are zero. Profit
posting and the order role's profit-write privileges remain step 4.7. Neither
`catalog_reserver` nor `order_app` can initialize profit currencies, change
balances, or access profit credits. The repeatable grants revoke obsolete table
and column permissions before reapplying the current privilege matrix, including
premature profit permissions from earlier development revisions.

The stock-column grant permits direct updates without allowing changes to product
names, prices or other columns. There is no stock trigger: reservation writes by
themselves do not change stock. Step 4.2 implements the Java transaction protocol
and its integration tests. The database checks also enforce
`0 <= stock <= initial_stock`, but the application must keep stock and reservation
rows consistent.

The product application does not depend on Flyway, and its startup never migrates.
PLAN records the implementation status, and [CHANGELOG.md](CHANGELOG.md) records
the completed lab verification.

## Ordering schema and catalog access (step 2.5)

[`database/shard/ordering/`](database/shard/ordering/) has its own V1, repeatable
grants and `flyway.toml`. It uses `ordering.flyway_schema_history` and runs as
`ordering_owner` through `ordering_migrator`, with the catalog stream's TLS,
timeouts and topology placeholders. No Java module or application startup migrates.

| Role | Login | Privileges |
|---|---|---|
| `ordering_owner` | no | Owns ordering schema, tables and history |
| `ordering_migrator` | yes | Non-inheriting owner membership, used only by Flyway |
| `ordering_writer` | no | Reads/inserts business records; updates status, reconciliation and publication metadata; deletes transport rows; reads/writes CDC offsets |
| `ordering_cdc_reader` | no | Ordinary SQL `SELECT` on `ordering.order_outbox` only |
| `order_app` | yes | `ordering_writer`, `catalog_reader`, `catalog_reserver` |
| `ordering_cdc` | yes | Replication attribute plus `ordering_cdc_reader`; highly privileged WAL access |

The additional Secrets are `ordering-migrator`, `order-app` and `ordering-cdc`.
`cluster-create.sh` creates each once; `up.sh` preserves it on reruns. Both wait for every role's
current generation to be applied before checking the schemas. Ordinary SQL grants
give runtime logins no DDL, temporary-table, history or owner privileges. Product
has no ordering privileges; CDC has no SQL `SELECT` on catalog, other ordering
tables or offsets. Order can update only the `stock` column of catalog products
and cannot update sellers.

The `ordering-cdc` Secret is highly privileged: PostgreSQL's `REPLICATION`
attribute allows logical decoding beyond those SQL grants. The installed
`test_decoding` plugin can stream committed changes throughout `shardshop`
without consulting the outbox publication or table privileges. The dedicated
publication limits the configured `pgoutput` stream; it is not an access-control
boundary for this login. Restrict the Secret to the trusted CDC runtime and
operators, and treat its compromise as potential exposure of every shared shard's
database changes. See [PostgreSQL logical replication security](https://www.postgresql.org/docs/18/logical-replication-security.html).

Buyers have immutable home regions and the same exact version-2 placement check
as sellers. Allocations retain a unique `(buyer_id, run_name, request_ordinal)`
mapping; ordinals are nonnegative. Orders reference both their local buyer and
the allocation for that buyer. Items store immutable seller/product IDs, quantity,
name, unit price and currency snapshots, without cross-shard catalog foreign keys.
The step 4.1 V2 migration adds buyer first name/surname, contact and address
fields, durable creation keys, and immutable unit-cost snapshots for confirmed
profit accounting; the existing V1 migration is unchanged. Orders and sagas start
in `PENDING_STOCK`. Sagas retain cancellation/release state,
a zero-to-five reconciliation counter and the next eligible time (initially
creation plus 60 seconds).

`order_result_ids` permanently binds each order-issued command/result message pair
to its saga. Outbox and inbox foreign keys reject unreserved or wrong-saga result
IDs; deleting transport records leaves these bindings and the saga intact.
Runtime grants keep buyer, allocation, order/item payload and message identities
immutable. Outbox inserts cannot supply `created_at` or `published_at`: the
database assigns creation time and rows start unpublished. Only outbox publication
metadata can be updated. Step 4.1 implements atomic acceptance writes. Later Java
milestones add state transitions, envelope validation, stock reserve/release, and
cleanup only after the retention and CDC checkpoint gates.

`orphan_results` and `conflicting_results` retain envelopes, correlation IDs,
fingerprints, outcomes, reasons and audit metadata. Their composite key is message
ID plus the SHA-256 fingerprint of the complete canonical received payload, so a
different payload with the same message ID stays visible. Neither store has
foreign keys that could prevent quarantine after data loss. The conflict store
also retains expected and received identity/outcome snapshots. Unresolved-order
indexes support the creation guard. Runtime may update delivery counts/times but
cannot delete incidents or resolve them; resolution is an operator action.
Column-level `INSERT` grants omit `resolved_at` and `resolution` on both stores,
so an orphan result cannot be inserted already resolved to bypass the order ID
reuse guard.

Each shard has CNPG Publication `<shard>-order-outbox`, SQL name
`ordering_order_outbox`, publishing only inserts into `ordering.order_outbox`.
`up.sh` leaves it pending until the table exists, then requires its current
generation to be applied before publishing routing. CDC uses its own login; publication-status and offset
writes use `order_app`. The precreated `ordering.debezium_offset_storage` follows
the [Debezium JDBC offset-store column contract](https://debezium.io/documentation/reference/3.7/configuration/storage.html#_offset_table_defaults).
The future connector must set `offset.storage.jdbc.table.name` to that qualified
name and disable publication auto-creation; connector/library qualification is
still part of the CDC milestone.

Apply all streams and inspect ordering. Prepare `$snapshot` with the
[kind snapshot runbook](#kind-verification-snapshot-step-315) after migration
before invoking the topology suite:

```bash
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/up.sh # Flyway validates again and seeding repeats idempotently
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-topology
kubectl --context kind-shardshop -n shardshop get publications
kubectl --context kind-shardshop -n shardshop logs \
    -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=ordering,shardshop.javacraft/migration-target=shard-a \
    --all-containers=true --tail=100
```

## Ledger schema and replay records (step 2.6)

[`database/ledger/ledger/`](database/ledger/ledger/) contains `V1__ledger.sql`,
the step 4.5 migration `V2__permanent_command_envelopes.sql`, the complete
repeatable grants, and `flyway.toml`. Its history is
`ledger.flyway_schema_history`. Flyway logs in as `ledger_migrator` and starts
each connection as `ledger_schema_owner`, using the same TLS, retries, and session
timeouts as the shard streams. The ledger has no shard-placement constraints,
sequences, SQL functions, or triggers.

| Role | Login | Privileges |
|---|---|---|
| `ledger_owner` | yes, CNPG bootstrap | Owns database `ledger`; no application uses this role |
| `ledger_schema_owner` | no | Owns schema `ledger`, its tables and history |
| `ledger_migrator` | yes | Non-inheriting membership in `ledger_schema_owner`; only Flyway uses it |
| `ledger_writer` | no | Schema `USAGE`, table `SELECT`, and `INSERT` with the column restrictions below; only the listed updates/deletes |
| `ledger_app` | yes | Inherits `ledger_writer`; no owner membership |

`cluster-create.sh` creates `ledger-migrator` and `ledger-app` Secrets once;
`up.sh` preserves them on reruns. Four ledger `DatabaseRole` resources accompany the existing
bootstrap role; the default three-shard inventory therefore renders 40
`DatabaseRole` resources and four `Database` resources. Runtime has no DDL,
temporary-table, Flyway-history, or `TRUNCATE` privileges.

| Table in `ledger` | Stored data and constraints |
|---|---|
| `ledger_operations` | Permanent decision per order, unique saga/command IDs, buyer, fingerprint, immutable snapshot, amount/currency, outcome, rejection reason and saved result data |
| `ledger_entries` | One permanent row per recorded order; composite foreign key requires its amount, currency and timestamp to match a `RECORDED` decision, preventing an entry for a rejected order |
| `ledger_result_ids` | Permanent binding of order-reserved command/result message IDs to an operation, immutable result envelope and original timestamps, the original command envelope (`command_envelope`, V2), plus a durable `publication_attempt` |
| `ledger_inbox` | Disposable command deduplication rows; foreign key requires the saved command/result pair |
| `ledger_outbox` | Pending/confirmed result delivery metadata; `message_id` references the reserved result ID and its current publication attempt |
| `conflicting_commands` | Immutable conflicting envelope and expected/received identity snapshots, reason, delivery metadata and operator-resolution fields; no foreign keys obstruct quarantine |

`ledger_writer` may update only `ledger_result_ids.publication_attempt`,
`ledger_outbox.publication_attempt`/`published_at`, and
`conflicting_commands.last_seen_at`/`delivery_count`. It may delete only inbox and
outbox rows. Runtime cannot update or delete decisions or entries, delete permanent
message bindings, alter saved envelopes, delete quarantine records, or resolve an
existing incident. The binding's publication counter is its sole mutable field.
Column-level inserts into the outbox omit `created_at` and `published_at`; new
and recreated rows use database time and start unpublished. Command-quarantine
inserts omit `resolved_at` and `resolution`, so incidents start unresolved.
Quarantine is keyed by message ID plus the SHA-256 fingerprint of the complete
canonical received payload, preserving different conflicts sharing an ID.

The publisher joins `ledger_outbox.message_id` to
`ledger_result_ids.result_message_id` to load the saved envelope. A deferred
foreign key requires the outbox attempt to match the permanent binding at commit.
[ARCHITECTURE](ARCHITECTURE.md#reconciliation-and-replayed-outcomes) defines how
Java replays a duplicate command and confirms each publication attempt.

The SQL constraints and grants supply storage boundaries. Step 4.5 implements
Java command validation, decision/entry atomicity, conflict detection and replay
transactions. Publisher confirmation and cleanup only after confirmed publication
and retention remain later steps. A DELETE grant does not enforce these cleanup
gates. The ledger application does not migrate at startup.
Decisions have no runtime `UPDATE` privilege, including for `FOR UPDATE`/`FOR KEY
SHARE` row locks. The consumer serializes with transaction advisory locks before
reading and comparing the saved decision.

Provision, migrate, and inspect the ledger from the repository root:

```bash
bash microservices/shardshop/scripts/up.sh
kubectl --context kind-shardshop -n shardshop get database ledger-db-ledger
kubectl --context kind-shardshop -n shardshop get databaseroles
kubectl --context kind-shardshop -n shardshop get jobs -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=ledger
kubectl --context kind-shardshop -n shardshop logs \
    -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=ledger,shardshop.javacraft/migration-target=ledger-db \
    --all-containers=true --tail=100
bash microservices/shardshop/scripts/up.sh # Flyway validates again and seeding repeats idempotently
```

The ledger Job must succeed before the script waits for shard publications and
publishes routing. [CHANGELOG.md](CHANGELOG.md) records the verification evidence,
and PLAN tracks the remaining work.
