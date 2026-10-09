# ShardShop

[Architecture](ARCHITECTURE.md) · [Implementation plan](PLAN.md) ·
[Version policy](VERSIONS.md) · [Artifact lock](versions.lock.yaml) ·
[Changelog](CHANGELOG.md)

All six applications use **Quarkus 3.40.1** in JVM mode on Java 25.
Product now runs the seller/product HTTP API from step 3.6. The product seeder
from step 3.7 seeds and verifies the catalog through that API and then exits. The
product reader from step 3.8 reads the seeded products at a bounded rate until it
stops. The other entry points start CDI and exit. Step 3.5 provides workload-owned
dataset definitions and HTTP clients; the buyer provider remains step 4.1, and the
order workload loop remains step 4.7. No Docker or Kubernetes is needed for Maven
module tests. The separate product-provider, seeder and reader acceptance checks
use disposable PostgreSQL databases.
Product/order production
starts still require the routing snapshot and a live generator ID reserved by
the startup launcher; tests use disposable configuration.

| Maven module | Quarkus application class |
|---|---|
| `shardshop-product` | `dev.nklip.javacraft.shardshop.product.ProductApplication` |
| `shardshop-order` | `dev.nklip.javacraft.shardshop.order.OrderApplication` |
| `shardshop-ledger` | `dev.nklip.javacraft.shardshop.ledger.LedgerApplication` |
| `shardshop-workload/shardshop-product-seeder` | `dev.nklip.javacraft.shardshop.workload.seeder.ProductSeederApplication` |
| `shardshop-workload/shardshop-product-reader` | `dev.nklip.javacraft.shardshop.workload.reader.ProductReaderApplication` |
| `shardshop-workload/shardshop-order-producer` | `dev.nklip.javacraft.shardshop.workload.producer.OrderProducerApplication` |

The ShardShop parent has five children and ten Java modules: six applications
and four library JARs. `shardshop-core` aggregates `common`, `idgen` and `sharding`.
Common provides canonical ID parsing, SHA-256 and bounded JSON HTTP transport;
it contains no dataset definitions or service wire models. Only product/order
use idgen and sharding; their plain domain classes and Quarkus configuration
remain separate. Workloads and ledger have no Snowflake dependency, generator
launcher or allocator access. Only product/order create entity and message IDs.

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
Product's step 3.6 provider persists these keys in the catalog. Buyer creation-key
storage remains step 4.1; process-local caches cannot satisfy the restart contract.

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
creation fields, durable keys and profit storage through V2. Ordering fields and
unit-cost snapshots remain step 4.1; confirmed-item profit crediting remains step
4.6. Pending, reserved, cancelled or rejected orders earn no profit.

The seeder retains returned seller IDs while creating products. Reader and order
producer resolve catalog IDs through ordinary read-only resource lookups:
`GET /api/v1/sellers/by-key/{creationKey}?region=US` and
`GET /api/v1/sellers/{sellerId}/products/by-key/{creationKey}`. The services look
up persisted entities and do not hold or generate workload datasets. Reader GETs
use the recovered seller/product IDs. A seeding gate still prevents load against
missing resources. Buyer creation retries similarly recover the same buyer ID.
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
outside workloads. The fixture check validates all 150 seller, 300 product and 3000
buyer creation payloads,
regional coverage, name/email correspondence and retained source hashes. After
setting up the [contract-validation environment](#http-and-message-contracts-step-31), run:

```bash
/tmp/shardshop-contract-validation/bin/python -B microservices/shardshop/shardshop-workload/shardshop-datasets/scripts/verify-datasets.py
```

Source notices and licenses ship in the dataset JAR under
`META-INF/shardshop-datasets`. Name pools use US Census public-domain data,
CC BY 4.0 Onomaverse data for EU, and MIT-licensed Faker lists for ASIA; see the
[source terms and ranking limitations](shardshop-workload/shardshop-datasets/SOURCES.md).

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
step 4.6; stock mutation remains step 4.9. No SQL functions or triggers are added.

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
plaintext test. The isolated provider harness supplies that override.
Repeat credentials/overrides for every shard. Unknown connection names fail
startup. `max-pool-size` is bounded to 1..32 per shard/endpoint, with no initial
connections; the default is four. Writes always use primaries. The optional
`replica` read profile uses only replica endpoints and returns
`READ_REPLICA_UNAVAILABLE` on failure, without primary fallback. Every read issues
SQL; there is no product cache. The complete database phase has a three-second
deadline, including pool acquisition, connection setup, locks and commit. HTTP
requests have a four-second deadline: before response serialization it returns
503; if output has already started, it closes the connection. Pool exhaustion
rejects within the budget; uncertain writes are recovered through their durable key, without an internal
write retry. `--check-startup` validates configuration and exits for packaged
startup checks; ordinary product startup waits for shutdown and serves HTTP.

Build and run the isolated acceptance checks (the Python environment is prepared
in [contract validation](#http-and-message-contracts-step-31)):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am -Paudit clean verify
docker run -d --name shardshop-product-check -p 127.0.0.1:55436:5432 \
  -e POSTGRES_PASSWORD=shardshop-test-only \
  docker.io/library/postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722
python3 -B microservices/shardshop/scripts/verify-catalog-migration.py shardshop-product-check
/tmp/shardshop-contract-validation/bin/python -B \
  microservices/shardshop/scripts/verify-product-provider.py shardshop-product-check
docker rm -f shardshop-product-check
/tmp/shardshop-contract-validation/bin/python -B \
  microservices/shardshop/scripts/verify-product-transport.py
```

The scripts create and remove uniquely named test databases and roles. The
provider check starts its own packaged JVMs and validates actual HTTP responses
against OpenAPI across four shards, including two US shards. It exercises
concurrent retries, changed payloads, strict input validation, current stock and
profits, discarded responses, process restarts, bounded pools/locks, and primary
and replica outages. It never connects to a deployed ShardShop database.
The transport check creates and removes its own pinned PostgreSQL container and
temporary certificates. It checks `verify-full` TLS, rejects untrusted certificates
and hostname mismatches, and reconnects after a database restart while retaining
the same product JVM and stored IDs. HTTPS and CNPG primary promotion remain
deployment/recovery milestones.

The disposable checks do not apply migrations to the retained kind lab. Complete
the lab qualification separately through its normal Flyway Jobs:

```bash
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/migrate.sh
bash microservices/shardshop/scripts/migrate.sh # validates history; no pending migrations
bash microservices/shardshop/scripts/verify-catalog-lab.sh
bash microservices/shardshop/scripts/verify-topology.sh --routing-only
bash microservices/shardshop/scripts/verify-generator-allocation.sh
```

`verify-catalog-lab.sh` makes read-only checks on every shard instance for V2,
object ownership, backfill invariants and effective product/order grants. The
allocation drill consumes retained generator slots. The
[changelog](CHANGELOG.md#step-36) records whether the lab qualification has
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
The Job deadline of 300 seconds (step 3.9) limits the complete run. Environment
variables such as `SHARDSHOP_SEEDER_PRODUCT_URL` also set these properties.
`--check-startup` validates the configuration and exits without HTTP requests.
The Kubernetes Job and the seeding gate of the startup script are step 3.9.

Build the product and seeder packages, and then run the isolated acceptance check
(the Python environment is prepared in
[contract validation](#http-and-message-contracts-step-31)):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-workload/shardshop-product-seeder -am -Paudit clean verify
docker run -d --name shardshop-seeder-check -p 127.0.0.1:55437:5432 \
  -e POSTGRES_PASSWORD=shardshop-test-only \
  docker.io/library/postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722
/tmp/shardshop-contract-validation/bin/python -B \
  microservices/shardshop/scripts/verify-product-seeder.py shardshop-seeder-check
docker rm -f shardshop-seeder-check
```

The check uses the provider harness of step 3.6: four disposable shard databases
(two US) and its own product JVMs. It does not connect to the kind lab. It holds
the advisory lock of `catalog-v1.EU.seller.10`, so the first seeder run fails with
`503` after 59 sellers and 118 products. After a product restart with a new
generator ID, a complete run must keep those rows unchanged. Two complete runs
must store identical rows and log the same digest. The script calculates the
expected payloads from the dataset TSVs and the digest from the stored IDs. It
also checks seller ID routes, creation-key homes, product colocation and zero
USD/EUR profits. A last run against product with unavailable replica reads must
fail and leave the rows unchanged.

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
Equal digests show that the reader found the IDs that the seeder verified. If a
lookup fails, the reader logs one error line and exits with code 1 before the load
starts. Step 6.1 makes the startup lookups repeat a `404` for a limited time,
because replica-profile reads can lag.

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
Product reads, interval 30.0 s: 6000 requests, 200.0/s, outcomes {OK=6000}, latency ms {p50=2, p95=4, p99=7, max=15}, regions {ASIA=1996, EU=2009, US=1995}, pool {leased=3, pending=0, available=13, max=16}
```

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
one error line and stops the reader with exit code 1. The Deployment, its resource
budget, per-pod product counters and the seeding gate are step 3.9.

Build the three packages, and then run the isolated acceptance check. The check
needs `lsof` and the Python environment of
[contract validation](#http-and-message-contracts-step-31). It takes approximately
90 seconds:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-workload/shardshop-product-seeder,shardshop-workload/shardshop-product-reader -am -Paudit clean verify
docker run -d --name shardshop-reader-check -p 127.0.0.1:55438:5432 \
  -e POSTGRES_PASSWORD=shardshop-test-only \
  docker.io/library/postgres@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722
/tmp/shardshop-contract-validation/bin/python -B \
  microservices/shardshop/scripts/verify-product-reader.py shardshop-reader-check
docker rm -f shardshop-reader-check
```

The check uses the provider harness of step 3.6 and seeds the catalog with the
packaged seeder. It does not connect to the kind lab. The reader runs with its
default policy and 5-second reports:

1. Against product without read replicas, the reader must exit with code 1 and one
   `REPLICA_UNAVAILABLE` lookup error, before the load starts.
2. Against the primary profile, the resolution digest must equal the seeder digest
   and the digest of the stored IDs. For 38 seconds, all reads must be `OK` at a
   maximum of 201 requests/s, with all three regions, and no read waits for a
   connection. `lsof` must show 16 TCP connections after the warm-up. After 38
   seconds, it must show 1–16 connections, and none from the warm-up.
3. While product is paused with SIGSTOP for 7 seconds, reads must end with
   `DEADLINE` after 5.0–5.1 seconds. After SIGCONT, the reads must become `OK`
   again.
4. While product stops, then starts without replicas, and then starts with
   primaries, the reader must record `TRANSPORT` and `REPLICA_UNAVAILABLE`
   outcomes, continue to run, and recover.
5. After SIGTERM, the total line must be the last report. The stored rows must not
   change.

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
The product provider and its contract tests are implemented in step 3.6; order and
ledger providers remain steps 4.1–4.5.

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
storage. Since step 3.6, the product provider does this. The order provider must
do it from step 4.1. Enable unknown-property rejection and duplicate-member
detection, require JSON string tokens for string fields, and validate integral
numeric values exactly before conversion. Do not rely on Jackson's default string
coercion or float-to-int truncation. Contract/provider tests must include raw JSON
with duplicate keys, numeric IDs, `2.0`, `2e0`, `2.5`, NUL and unpaired surrogates.

To validate contracts without starting infrastructure, use a temporary Python 3.11+
environment outside the repository. The requirements file pins validation tools
and their transitive dependencies; [VERSIONS.md](VERSIONS.md#contract-validation-tools)
records the qualification. No Maven/runtime dependency is added.

```bash
python3 -m venv /tmp/shardshop-contract-validation
/tmp/shardshop-contract-validation/bin/python -m pip install \
  -r microservices/shardshop/scripts/contract-validation-requirements.txt
/tmp/shardshop-contract-validation/bin/python -B microservices/shardshop/scripts/verify-contracts.py
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger -am clean verify
```

The verifier checks every inline HTTP request/response example (including referenced
responses), message examples, ID and status-URL limits, money, text and timestamp
boundaries, integer normalization, fingerprints, totals, correlation and illustrative
service-issued ID and home-region rules. It also rejects missing response examples. Packaged routing
startup checks run in the default Maven `verify` lifecycle.

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

Production startup requires `shardshop.id.generator-id`, also configurable through
`SHARDSHOP_ID_GENERATOR_ID`, in `1..1023`; there is no production default.
Generator 0 remains reserved for historical illustrative fixtures; no runtime
creation path or workload emits generator-0 IDs.
This setting accepts an already reserved ID; it does not reserve or persist one.
The step 3.4 launcher below reserves this setting before every product/order JVM
start. Direct manual IDs are only for disposable skeleton tests without retained
data; deployed processes must use the launcher.

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

## Generator allocation at JVM startup (step 3.4)

`GeneratorLauncher` in `shardshop-idgen` runs before each product/order application
JVM, including container restarts in the same pod. It invokes `kubectl` directly
without a shell, reads `shardshop-snowflake-generators`, and atomically tests its
UID, resource version and `data.highWaterMark` before advancing the counter.
Only an acknowledged reservation reaches the application's
`shardshop.id.generator-id` property. The launcher supervises that one child JVM;
every new launcher invocation reserves again.
The generator's CDI producer compares its effective configured ID with a separate
raw JVM reservation pin before constructing the generator. Higher-priority
environment/profile or external-file configuration cannot substitute another ID.

Allocation has a 30-second budget, requests of at most five seconds, and retry
backoff from 25 to 500 milliseconds. Failed or uncertain CAS attempts burn their
candidate: the next attempt advances beyond both the observed counter and the
uncertain candidate. A late acknowledgement cannot start the application.
Missing, malformed, inaccessible, replaced or exhausted state fails startup.
Launcher diagnostics identify exhaustion, stale identity, allocation deadlines,
context/pin configuration errors, an unavailable kubectl executable, and registry
read/access failures with their next checks. Only fixed local diagnostics are
printed; external exception text and command stderr remain suppressed.
Generator 0 remains reserved; at most 1,023 starts, including burned slots, fit
one retained lab dataset.

The infrastructure chart creates only product/order ServiceAccounts and grants
them `get`/`patch` on the single named allocator ConfigMap. Ledger, workloads and
the default account have no allocation permissions or launcher dependency.
A fail-closed admission policy rejects counter rollback, out-of-range values,
individual and collection deletion, and changes to the immutable `shardshop-generator-identity`
ConfigMap. That identity pins the registry's original UID. Neither state object
is rendered by Helm, so reapplying the chart cannot reset it.
The guard matches the admitted object's name, including collection deletes with
an empty request name. `generator-registry.sh check` probes both allocator objects
through raw collection requests with `dryRun=All`, as well as individual requests
with `--dry-run=server`. Namespace deletion cannot remove those two objects and
leaves the namespace terminating while the guard is active; other ConfigMaps
remain outside the guard.
The guard uses Kubernetes' [validating admission policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)
and the allocator uses [conditional API updates](https://kubernetes.io/docs/reference/using-api/api-concepts/#updates-to-existing-resources).

On first setup, `up.sh` applies infrastructure and then stops if allocator state
is missing. Initialize it explicitly only for a lab that has never issued retained
IDs, then rerun the startup check:

```bash
bash microservices/shardshop/scripts/up.sh
# First setup only, after up.sh reports missing allocator state:
bash microservices/shardshop/scripts/generator-registry.sh initialize
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/generator-registry.sh check
```

`initialize` preserves an existing valid pair and refuses partial state. A lost
creation response can leave partial state; neither startup nor initialization
repairs it by guessing. Establish its history before operator recovery. Normal
stop/start, pod replacement and database restore preserve the ConfigMaps. Keep
the latest allocator identity and high-water backup outside kind, separately
from database dumps. Never apply an older allocator snapshot over live state.
Restoring an entire older etcd/cluster snapshot also restores its admission
history; that is unsupported without independently establishing the latest mark.
If that mark is unavailable, keep ID-producing services stopped. Removing the
guard or resetting state is allowed only after retiring all prior emitters,
databases, broker data, backups and other replay inputs.

Build and verify the affected modules, then run the live restart/RBAC drill:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
bash microservices/shardshop/scripts/verify-generator-allocation.sh
```

The drill consumes real generator slots. It uses a temporary, non-root,
shell-free verification image from the locked kind and Canonical JRE images,
checks simultaneous product/order starts and a same-pod restart, and cleans up
its pods. It passes `--check-startup` through the launcher so product exits after
validation. Before allocating, it checks both packages against the routing
snapshot in network-isolated containers with HTTP disabled; failed preflights
consume no registry slots. Normal service launches omit `--check-startup`.
Final service images and Deployments still belong to steps 3.9/4.8.
Their entrypoint must invoke this launcher directly, use the owning service's
ServiceAccount, and provide `SHARDSHOP_GENERATOR_REGISTRY_UID` from the immutable
identity ConfigMap's `registryUid` key. An init container cannot replace this
entrypoint. In-cluster kubectl uses an explicit temporary kubeconfig that references
the projected service-account token file and cluster CA; it never discovers a
user kubeconfig from `KUBECONFIG` or the home directory. The token is not copied
into the file or command arguments. The temporary file is removed after allocation;
host launches require `SHARDSHOP_KUBE_CONTEXT=kind-shardshop`. No tool binaries
are downloaded or stored in the repository.

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
# After up.sh and migrate.sh have provisioned the lab:
kubectl --context kind-shardshop -n shardshop get configmap shardshop-routing \
    -o jsonpath='{.data.application\.properties}' > /tmp/shardshop-routing.properties
export SHARDSHOP_ROUTING_CONFIG=file:/tmp/shardshop-routing.properties
export SHARDSHOP_KUBE_CONTEXT=kind-shardshop
export SHARDSHOP_GENERATOR_REGISTRY_UID=$(kubectl --context kind-shardshop \
  -n shardshop get configmap shardshop-generator-identity -o jsonpath='{.data.registryUid}')
# Every successful reservation in this host example permanently consumes one real slot,
# even if the application subsequently fails or exits immediately.
"$JAVA_HOME/bin/java" \
  -cp 'microservices/shardshop/shardshop-product/target/quarkus-app/lib/main/*' \
  dev.nklip.javacraft.shardshop.idgen.allocation.GeneratorLauncher product \
  microservices/shardshop/shardshop-product/target/quarkus-app
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
deploy that entire directory. Product and order must start through
`GeneratorLauncher`, as in the host example above; ledger and workloads launch
`quarkus-run.jar` directly.

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
describes. Step 3.9 builds the final application images.

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
properties use the lock's immutable digests. The separate provider/migration
checks explicitly use a disposable PostgreSQL container; Maven tests do not
automatically start containers.

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
`kind-values.yaml`. Helm only renders these resources; `kubectl apply` owns their
lifecycle, preserving existing names and PVCs. There are no per-shard directories,
committed generated manifests, or new database Helm releases. The separate ledger
is excluded from the shard list. Names must start with a lowercase letter, contain
only lowercase letters, digits and hyphens, end in a letter/digit, and fit 44 characters,
leaving room for either stream's migration Job suffix.
Empty/duplicate or malformed names, `ledger-db`, and missing/invalid regions fail
before deployment. Regions use only uppercase `US`, `EU`, and `ASIA`; a custom
inventory may assign multiple shards to one region. The chart adds the
`shardshop.javacraft/region` label to each shard Cluster and its inherited metadata.
These are business regions in the local lab, not separate geographic locations.

Preview without Kubernetes access:

```bash
bash microservices/shardshop/scripts/render.sh infrastructure
bash microservices/shardshop/scripts/render.sh migration shard-a
bash microservices/shardshop/scripts/render.sh migration shard-a ordering
bash microservices/shardshop/scripts/render.sh migration ledger-db ledger
bash microservices/shardshop/scripts/render.sh routing
```

The migration render takes `SHARD [catalog|ordering]` and defaults to `catalog`.
Its Job, SQL ConfigMap, migrator Secret, and component names follow the selected
stream; both shard streams receive `shardRegion`, `shardIndex`, and `shardCount`.
`migration ledger-db ledger` selects the separate ledger stream without shard
placeholders or region labels. The chart rejects ledger streams on shards and
shard streams on `ledger-db`. `up.sh` provisions all three schemas and their roles;
`migrate.sh` runs catalog on every shard, then ordering on every shard, then ledger
once. It waits for the ordering outbox publications before activating routing.

The scripts use Python 3's standard JSON library and Helm, with no YAML package to
install. The [changelog](CHANGELOG.md#step-24) records the live inventory and
script checks.
`SHARDSHOP_INVENTORY=/path/inventory.yaml` selects another inventory;
`SHARDSHOP_ENV_VALUES=/path/values.yaml` replaces the kind overrides.

`up.sh` provisions the declared shards and ledger. `migrate.sh` waits for their
deployed clusters/databases, migrates sequentially, then publishes `shardshop-routing`.
That ConfigMap contains `application.properties` with aligned
`shardshop.routing.shards` and `shardshop.routing.regions` lists. Its `version` key
remains SHA-256 of the comma-joined UTF-8 names; `regionVersion` is SHA-256 of the
comma-joined UTF-8 `name=REGION` pairs in inventory order. Before running any
migrations, `migrate.sh` saves both hashes in `shardshop-migration-topology`.
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
of existing IDs. `up.sh` and `migrate.sh` refuse a list that differs from an already
published version; a changed published region assignment is also refused.
Schema V1 is independent of the unchanged version-2 ID-routing algorithm.

For a different topology, reset this disposable lab:

1. Stop the applications and workloads.
2. Retire all old backups, broker data and other replay inputs. The new lab gets a
   new generator registry, thus it can issue IDs that old data already uses
   ([generator identity](ARCHITECTURE.md#generator-identity-across-processes-and-restarts)).
3. Delete the lab with `kind delete cluster --name shardshop`. This destroys the
   lab's data.
4. Run `up.sh`. It stops because the allocator state is not there.
5. Run `generator-registry.sh initialize`, then `up.sh` again, as
   [step 3.4](#generator-allocation-at-jvm-startup-step-34) describes.
6. Run `migrate.sh`, export the new configuration, and reseed before you restart
   the workloads.

Online data redistribution and rolling topology changes are outside this
implementation; deleting the routing ConfigMap alone would bypass the guard
without moving any data.

## Local Kubernetes cluster, operator and databases (steps 1.1–2.1)

The lab runs on kind, and everything through the final acceptance run works on
this one machine; the AWS track with Terraform (PLAN milestone 7) is optional and
comes later. Take `docker`, `kind`, `kubectl`, `helm`, `openssl`, and `python3` from your `PATH`, like
Maven; this project never downloads or pins tool binaries. It was tested with
Docker Desktop 4.92.0, kind 0.33.0, kubectl 1.36, and Helm 4.3.0 (for example,
`brew install kind kubectl helm`). The node image's advisories were reviewed on
2026-09-28 and accepted for this loopback-only, disposable lab. Give Docker
Desktop's VM at least 12 GB of memory (Settings → Resources); `up.sh` stops early
if it has less. Migrations also require Docker's Buildx plugin: the migration
script builds the OpenJDK Flyway image and loads it into the kind nodes.

[`infra/kind.yaml`](infra/kind.yaml) defines one control plane and three workers,
one per instance of each three-instance shard, on the digest-pinned Kubernetes
1.36.4 node image, with the API server on 127.0.0.1. kind cannot add nodes, so a
cluster created with fewer workers must be deleted and recreated once.
Manifests follow the [portability rules](PLAN.md#portability-beyond-kind): a
shared chart in `infra/helm/shardshop/` with kind settings in `kind-values.yaml`.

Create or reuse the cluster from the repository root:

```bash
bash microservices/shardshop/scripts/up.sh
```

It creates the `shardshop` cluster if it does not exist and waits for all nodes to
be Ready. It then installs CloudNativePG 1.30.1 from its vendored chart with
`helm upgrade --install`, waiting up to 3 minutes for the operator, waits up to 180
seconds for every CRD to be Established, and renders/applies the shared chart. That creates
the `shardshop` namespace with the `restricted` Pod Security label, three
three-instance clusters (`shard-a`/US, `shard-b`/EU, `shard-c`/ASIA), and the single-instance
`ledger-db` cluster. It waits up to 300 seconds for every database cluster to be
Ready, then until each cluster's `-rw` Service accepts connections. It also
creates random password Secrets only if absent: `catalog-migrator`,
`ordering-migrator`, `product-app`, `order-app`, and `ordering-cdc` are shared by
the shards; `ledger-migrator` and `ledger-app` belong to the separate ledger.
It applies CNPG `DatabaseRole`s and `Database` resources for catalog, ordering,
and ledger, waits for roles at their current generation, then waits up to 180
seconds each for a Database's current generation and `applied` status. It never
creates tables or other schema contents; `migrate.sh` does (step 2.3 below).
A fresh run takes about five minutes, mostly image
pulls; a rerun takes seconds. kind adds the `kind-shardshop` context to
`~/.kube/config` and selects it; the script passes that context on every command
and never deletes a cluster, namespace, or volume. Check the result:

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
stored Ready statuses predate the restart, and takes about a minute. Only
`kind delete cluster --name shardshop` deletes the lab's data.

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

After catalog and ordering migrations, run the read-only routing check on every inventory
primary:

```bash
bash microservices/shardshop/scripts/verify-topology.sh --routing-only
```

It compares PostgreSQL SHA-256 arithmetic and the deployed seller and buyer
placement constraints against the shared golden fixtures used by Java. It creates
no SQL objects and does not run a failure drill.

Run the reversible topology drill in this disposable lab (defaults to the first
inventory entry; pass another inventory name to drill that shard):

```bash
bash microservices/shardshop/scripts/verify-topology.sh
```

Each invocation checks every inventory shard's writable primary, quorum standbys,
independent database lineage, `-rw`/`-ro` Service endpoints and distinct Bound
PVCs/backing volumes, and runs the same read-only routing checks. Counts come from
the inventory. It then drills the selected shard (the first entry by default): a
probe row must reach both standbys, writes on standbys must fail, and the probe
schema must remain absent from peer shards. This failure drill requires the lab's
three instances per shard; changing the number of shards needs no script edit.
It checks failover-slot synchronization and PVC persistence, replaces one standby
pod, and temporarily fences one then both standbys to observe quorum commits.
Reads and writes are disrupted during the
drill. It restores the standbys and removes its own SQL objects on exit; it never
deletes a PVC. A global `shardshop-topology-drill` ConfigMap prevents concurrent
runs across shards, because every run checks the whole topology. If the process
is killed without a chance to clean up, first ensure no drill is running, remove the
`cnpg.io/fencedInstances` annotation from the drilled shard, wait for the cluster to
be Ready, and inspect the `cluster` and `probe` entries in that ConfigMap before
removing its SQL schema/slot and the ConfigMap.

## Separate ledger database (step 2.2)

[`clusters.yaml`](infra/helm/shardshop/templates/clusters.yaml) adds a separate
CloudNativePG cluster with one PostgreSQL instance, separate from the nine shard
pods. It uses the same pinned PostgreSQL image and its own 2 GiB PVC. The kind
values place it on a worker using `standard` storage; `up.sh` creates it and
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

`kind delete cluster --name shardshop` removes the whole lab, including its
storage; kind storage is disposable and is not a backup.

## Database schemas and migrations (step 2.3)

No Maven module owns a schema: databases, roles and migrations belong to the
database layer in [ARCHITECTURE.md](ARCHITECTURE.md#database-change-management).
Keep all Flyway SQL simple and declarative: tables, constraints, indexes, grants,
and data changes only. Do not create SQL functions or triggers; use Java for
business logic and scripts for deployment checks.
The two scripts split the work:

- `up.sh` declares everything Kubernetes can: the clusters and their creation-time
  hardening, the schema roles, and each shard's `Database` resource,
  which creates schemas `catalog` and `ordering` with their respective owners.
  Its `Publication` resource selects only inserts into `ordering.order_outbox`;
  CNPG reconciles it after the migration creates the table. The shared
  [`databases.yaml`](infra/helm/shardshop/templates/databases.yaml) template
  renders the roles and `Database` for every inventory entry. The separate
  [`ledger-database.yaml`](infra/helm/shardshop/templates/ledger-database.yaml)
  declares `ledger-db-ledger`, schema `ledger`, and its four roles.
- `migrate.sh` fills the schemas by running each Flyway stream in
  [`database/`](database/) on every shard primary and then on ledger once. It builds
  and loads the migration image automatically; no Maven build, application JAR, or database
  superuser session is needed.

After `up.sh`, run:

```bash
bash microservices/shardshop/scripts/migrate.sh
```

After checking the published topology, `migrate.sh` invokes
[`build-migration-image.sh`](scripts/build-migration-image.sh) before creating SQL
ConfigMaps or migration Jobs. The helper uses Docker Buildx with pinned inputs and
`SOURCE_DATE_EPOCH=0`, loads the ARM64 image into kind, and checks its CRI manifest
digest on every node against the selected migration image pin. A build or digest
failure stops the migration. To prepare that image separately after `up.sh`, run:

```bash
bash microservices/shardshop/scripts/build-migration-image.sh
```

[`database/shard/catalog/`](database/shard/catalog/) is the catalog stream.
`V1__catalog.sql` creates all tables, including seller home regions, named region
and ID-placement checks, and regional comments. `R__catalog_grants.sql` holds the
complete privilege matrix; Flyway reapplies it whenever it changes. `flyway.toml` fixes the
schema, the history table `catalog.flyway_schema_history`, retries, and the 5-second connect, 30-second
socket, 15-second statement and 5-second lock timeouts. It also starts every
connection as `catalog_owner`, so the migrator login never owns an object.

The script runs the required `database/shard/` streams in the order `catalog`,
then `ordering`, followed by `database/ledger/ledger` once. For each shard stream it
loads the folder into the
`<stream>-migrations` ConfigMap, then runs one `<shard>-<stream>-migration` Job per
inventory entry from the shared
[`migration.yaml`](infra/helm/shardshop/templates/migration.yaml) template,
one shard at a time. Each Job uses the matching `<stream>-migrator` Secret and
stream component label. The template supplies the same `shardRegion`, `shardIndex`,
and `shardCount` Flyway placeholders for both streams from the inventory, and sets
`SHARD_NAME` and the shard's CA Secret. Kubernetes expands `FLYWAY_URL` using the
preceding environment variable. The ledger Job uses `ledger-migrations`,
`ledger-migrator`, `ledger-db-ca`, and the `ledger-db-rw` endpoint/database `ledger`,
without shard placement settings. Before each Job, the script waits for its
cluster and the Database's current-generation `applied` status. Each Job runs
Flyway OSS 13.8.1 on the pinned Canonical OpenJDK 25 runtime. Its
[`Dockerfile`](infra/images/flyway/Dockerfile) copies only Flyway's core and
PostgreSQL plugin libraries, the PostgreSQL JDBC driver and the licenses from the
pinned official distribution image;
its final base is `ubuntu/jre` on Ubuntu 26.04. The Job logs in as the stream's
migrator (`catalog_migrator` for catalog) over TLS with `verify-full` and the
cluster's CA. It has a 180-second deadline and no retry. The script prints its log
and stops at the first failure, leaving later Jobs unattempted and routing
unpublished; successful databases stay migrated because migrations are not a
transaction across databases.
Correct the failure and rerun. Retained versioned migrations are immutable; fix
forward with another version. Never repair checksums automatically, bypass
validation, or clean a catalog that holds data. Normal Flyway configuration keeps
`clean` disabled. The last run's Jobs and logs stay until the next run, which
replaces them. Run one `migrate.sh` at a time.

If a run stops partway through, rerun it with the same inventory.
[Shard inventory and routing](#shard-inventory-and-routing) describes the topology
reservation, the routing publication and the export for local launches. Inspect
the migration resources:

```bash
kubectl --context kind-shardshop -n shardshop get jobs,pods -l app.kubernetes.io/name=schema-migration
kubectl --context kind-shardshop -n shardshop logs job/shard-a-catalog-migration
kubectl --context kind-shardshop -n shardshop get databaseroles,databases
```

The migration image and Job both select UID/GID 10001. The Job uses a
read-only root filesystem, `HOME=/tmp`, no service-account token, and telemetry
disabled. That fixed ID is an exception to the
[portability rules](PLAN.md#portability-beyond-kind), which OpenShift's assigned
UIDs would need to replace.

### Catalog roles and tables

[ARCHITECTURE](ARCHITECTURE.md#roles) defines the catalog roles and their
memberships. `up.sh` generates catalog login passwords into the Secrets
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
will reserve with `INSERT … ON CONFLICT (order_id, product_id) DO NOTHING` and,
only for a new reservation, conditionally decrement stock in the same Java-managed
shard transaction. Insufficient stock rolls back the reservation. Release will
mark an active reservation released and restore its quantity in one transaction;
retries must change stock at most once. There are no ID sequences. Products
inherit the seller's region and store no independent region column. The local
seller foreign key and placement checks prevent creating products on a different
region's shard. Runtime roles cannot change a seller's region.

Step 3.6 adds company-name, product-description, unit-cost and creation-key
storage through `V2__catalog_creation.sql`, plus service-owned `seller_profits`
and immutable `profit_credits` identities. Legacy descriptions backfill from the
product name; legacy unit cost and initial USD/EUR balances are zero. Profit
posting and the order role's profit-write privileges remain step 4.6. Neither
`catalog_reserver` nor `order_app` can initialize profit currencies, change
balances, or access profit credits. The repeatable grants revoke obsolete table
and column permissions before reapplying the current privilege matrix, including
premature profit permissions from earlier development revisions.

The stock-column grant permits direct updates without allowing changes to product
names, prices or other columns. There is no stock trigger: reservation writes by
themselves do not change stock. Java reserve/release logic and its integration
tests remain planned in step 4.9. The database checks still enforce
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
`up.sh` creates each once and preserves it on reruns. It waits for every role's
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
The future step 4.1 migration adds buyer first name/surname, contact and address
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
metadata can be updated. Java remains
responsible for atomic writes, valid state transitions, envelope validation,
stock reserve/release, and cleanup only after the retention and CDC checkpoint
gates; those behaviors are later milestones.

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
`up.sh` may leave it pending until the table exists; `migrate.sh` waits for it
before publishing routing. CDC uses its own login; publication-status and offset
writes use `order_app`. The precreated `ordering.debezium_offset_storage` follows
the [Debezium JDBC offset-store column contract](https://debezium.io/documentation/reference/3.7/configuration/storage.html#_offset_table_defaults).
The future connector must set `offset.storage.jdbc.table.name` to that qualified
name and disable publication auto-creation; connector/library qualification is
still part of the CDC milestone.

Apply all streams and inspect ordering:

```bash
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/migrate.sh
bash microservices/shardshop/scripts/migrate.sh # unchanged histories: no pending migrations
bash microservices/shardshop/scripts/verify-topology.sh --routing-only
kubectl --context kind-shardshop -n shardshop get publications
kubectl --context kind-shardshop -n shardshop logs job/shard-a-ordering-migration
```

## Ledger schema and replay records (step 2.6)

[`database/ledger/ledger/`](database/ledger/ledger/) contains `V1__ledger.sql`,
the complete repeatable grants, and `flyway.toml`. Its history is
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

`up.sh` creates `ledger-migrator` and `ledger-app` Secrets once and preserves
them on reruns. Four ledger `DatabaseRole` resources accompany the existing
bootstrap role; the default three-shard inventory therefore renders 40
`DatabaseRole` resources and four `Database` resources. Runtime has no DDL,
temporary-table, Flyway-history, or `TRUNCATE` privileges.

| Table in `ledger` | Stored data and constraints |
|---|---|
| `ledger_operations` | Permanent decision per order, unique saga/command IDs, buyer, fingerprint, immutable snapshot, amount/currency, outcome, rejection reason and saved result data |
| `ledger_entries` | One permanent row per recorded order; composite foreign key requires its amount, currency and timestamp to match a `RECORDED` decision, preventing an entry for a rejected order |
| `ledger_result_ids` | Permanent binding of order-reserved command/result message IDs to an operation, immutable result envelope and original timestamps, plus a durable `publication_attempt` |
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

The SQL constraints and grants supply storage boundaries. Java command validation,
decision/entry atomicity, conflict detection, replay transactions, publisher
confirmation, and cleanup only after confirmed publication and retention remain
later steps. A DELETE grant does not enforce these cleanup gates. The ledger
application is still a skeleton and does not migrate at startup.
Decisions have no runtime `UPDATE` privilege, including for `FOR UPDATE`/`FOR KEY
SHARE` row locks. Step 4.4 must serialize with a transaction advisory lock or
insert-conflict handling followed by reading and comparing the saved decision.

Provision, migrate, and inspect the ledger from the repository root:

```bash
bash microservices/shardshop/scripts/render.sh migration ledger-db ledger
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/migrate.sh
kubectl --context kind-shardshop -n shardshop get database ledger-db-ledger
kubectl --context kind-shardshop -n shardshop get databaseroles
kubectl --context kind-shardshop -n shardshop logs job/ledger-db-ledger-migration
bash microservices/shardshop/scripts/migrate.sh # unchanged histories: no pending migrations
```

The ledger Job must succeed before the script waits for shard publications and
publishes routing. [CHANGELOG.md](CHANGELOG.md) records the verification evidence,
and PLAN tracks the remaining work.
