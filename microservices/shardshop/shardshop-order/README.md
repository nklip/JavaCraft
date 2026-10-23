# ShardShop Order

[Back to ShardShop](../README.md) ·
[Architecture](../ARCHITECTURE.md#5-order-creation-and-ledger-saga) ·
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) ·
[Product service](../shardshop-product/README.md) ·
[Implementation plan](../PLAN.md) · [Changelog](../CHANGELOG.md)

`shardshop-order` creates buyers and coordinates orders on the regional shards.
The buyer's immutable home region determines where an order is stored; products
may come from sellers in any region.

The same image runs two processes selected by `shardshop.order.process`: `http`
(the default) serves APIs and pages; `saga` runs stock reservation, compensation,
seller-profit posting, CDC command publication and ledger-result consumption. The
HTTP Deployment has two replicas with `RollingUpdate`; `shardshop-order-saga` has
one replica with `Recreate` to prevent overlapping CDC slot owners. Each JVM reserves
its own generator slot. HTTP replicas run no background saga loops.

| Task | Component |
|---|---|
| Create buyers and allocate buyer/order/message IDs | **Order** |
| Keep buyer creation keys and immutable order payloads for retries | **Order** |
| Accept orders and expose their current status | **Order** |
| Reserve stock, compensate rejected orders and post confirmed seller profit | **Order** |
| Relay order command outboxes and consume ledger results | **Order** |
| Serve the read-only regional order page and listing API | **Order** |
| Create sellers/products and serve catalog reads | Product |
| Record immutable financial decisions and publish their results | Ledger |
| Apply database migrations before order starts | Shared deployment pipeline |

**Working directory:** Run all shell commands from the repository root.

## Contents

1. [Regional order page](#1-regional-order-webpage)
2. [Buyer and order provider](#2-buyer-and-order-provider)
3. [Stock reservation and compensation](#3-stock-reservation-and-compensation-step-42)
4. [Order outbox CDC relay](#4-order-outbox-cdc-relay-step-44)
5. [Order result consumer and seller profit](#5-order-result-consumer-and-seller-profit-step-47)

## 1. Regional order webpage

<sub>[Back to contents](#contents)</sub>

Order serves a read-only webpage at `/orders/` from its application image.
Each order shows its ID, the buyer's full name, creation date/time in **UTC**,
current status, cancellation reason when present, total and currency, and
product names with quantities. Hover over a buyer or product name, focus it with
the keyboard, or click it to open details with separate **Copy name** and
**Copy ID** buttons. Product names come from the saved order snapshots.
The page includes orders outside the workload datasets.

### Open the webpage

**Prerequisite:** Create the lab with `cluster-create.sh`. See the
[cluster lifecycle](../README.md#local-kubernetes-cluster-operator-and-databases-steps-1121)
for Docker, Kubernetes and build prerequisites. Product/order startup also needs
the deployed routing snapshot and live generator allocation; `up.sh` performs
the application release and acceptance checks against the existing foundation.

1. Deploy the lab:

   ```bash
   bash microservices/shardshop/scripts/up.sh
   ```

2. Open access to the webpages:

   ```bash
   bash microservices/shardshop/scripts/webpages.sh
   ```

   Wait for `webpages.sh` to print all five URLs. It starts and supervises one
   localhost tunnel for each page:

   | Page | Local port | Service port |
   |---|---|---|
   | [Products](http://127.0.0.1:18080/products/) | 18080 | product 8080 |
   | [Orders](http://127.0.0.1:18081/orders/) | 18081 | order 8080 |
   | [Workload Order Producer](http://127.0.0.1:18082/workload-order-producer/) | 18082 | order-producer 8080 |
   | [Workload Product Reader](http://127.0.0.1:18083/workload-product-reader/) | 18083 | product-reader 8080 |
   | [Workload Product Seeder](http://127.0.0.1:18084/workload-product-seeder/) | 18084 | product-seeder-control 8080 |

   The last three pages control the workload traffic; see the
   [workload control guide](../shardshop-core/workload-control/README.md#1-workload-control-webpages).

3. Open <http://127.0.0.1:18081/orders/>.
4. Keep the terminal open while you use the webpages.

`webpages.sh` takes no arguments and requires only `kubectl` and `lsof`.
It stops existing listeners on ports 18080–18084, sends TERM first, waits
up to five seconds, then sends KILL to remaining listeners. All five ports must
be released before it opens the tunnels. Run the same command whenever you need
to reopen access; it does not rebuild or restart applications.
A forwarding failure stops the tunnels started by this invocation and leaves
the deployed lab running.

### Filters, sorting and navigation

Choose **US**, **EU** or **ASIA** in the region dropdown. Region always means the
**buyer's home region**, even when an order includes products from sellers in
other regions. The initial view lists all orders in that region. Search by an
exact product ID or part of a product name. Name matching ignores case and uses
the names saved with orders; characters such as `%` and `_` match literally.
Numeric-only input is treated as an ID and must be canonical and positive.
Names allow up to 200 Unicode characters after trimming surrounding whitespace.
An order appears once even when several items match, and keeps its complete
item list. Clear the filter to restore all regional orders.

The **Created · UTC** column header toggles creation-date sorting between
**DESC** (newest first, the default) and **ASC** (oldest first). Its arrow shows
the current direction. Sorting uses the order's stored `created_at`; equal
timestamps use order ID in the same direction to keep navigation deterministic.
Both directions work with or without an ID or name filter.

The page uses **First · Previous · Page N · Next · Last** pagination.
Changing region, product filter or sort direction starts at page 1. The URL
stores `region`, optional `productId` or `productName`, `sort` and `page`, for example:

```text
http://127.0.0.1:18081/orders/?region=EU&sort=ASC&page=2
http://127.0.0.1:18081/orders/?region=US&productId=123&sort=DESC&page=1
http://127.0.0.1:18081/orders/?region=US&productName=coffee&sort=DESC&page=1
```

A copied URL, reload or browser Back/Forward restores that view. **Refresh**
reloads the current page with the same filters and sort; it performs no writes.
Loading, empty and error states distinguish an in-flight read, no matching
orders, and a failed request. A required shard failure fails the complete page.
Navigation does not promise a database snapshot across requests: live order
statuses and new orders may change between reads.

**Tunnels:**

- **Scope:** All tunnels bind only to `127.0.0.1`.
- **Stop the tunnels:** Press Ctrl+C. All forwards started by this invocation
  stop; the cluster and workloads continue to run.
- **Stop the lab:** Run `bash microservices/shardshop/scripts/down.sh`.
- **Second terminal:** `webpages.sh` holds no deployment lock, so `down.sh` or
  another deployment can run separately.
- **Forwarding failure:** If a tunnel exits, `webpages.sh` reports its exit and
  stops the other tunnels. Exiting `webpages.sh` cleans up all owned processes.

### Regional listing API

The page reads `GET /api/v1/orders` with these query parameters:

| Parameter | Meaning |
|---|---|
| `region` | Required buyer home region: `US`, `EU` or `ASIA` |
| `productId` | Optional canonical positive decimal product ID; matches orders containing that product across seller regions |
| `productName` | Optional case-insensitive literal substring of a saved product name, 1–200 Unicode characters after trimming; cannot be combined with `productId` |
| `sort` | Creation-date direction: `ASC` or `DESC`; defaults to `DESC` |
| `limit` | Page size, default 25, allowed range 1–100 |
| `afterCreatedAt`, `afterOrderId` | Optional continuation pair; supply both from the preceding response |

Responses merge all required regional shard results into one global order by
creation timestamp and numeric order ID before pagination. Orders include
`buyerName`, and each item includes `productName`, alongside their string IDs.
The nullable `nextAfter` contains `{createdAt, orderId}` for the next request. Keep region,
product filter and sort unchanged when following a continuation pair. The
browser's numbered `page` is navigation state; it resolves API cursors rather
than passing a page number to the API. Invalid regions, IDs, names, directions
or limits, combined ID/name filters, and incomplete cursor pairs return
`400 INVALID_REQUEST`; a required shard failure returns an error instead of a
partial list.

Regional listing shares one three-second database phase deadline across its
shard reads. Browser navigation has one eight-second loading deadline, including
resolving preceding cursors for a copied page URL or jumping to Last. The HTML,
script and stylesheet use `Cache-Control: no-store` to load matching assets after
a deployment.

Run the focused Testcontainers API and browser scenarios from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-listing or @order-browser or @order-api or @product-browser'
```

The testing module also runs the JavaScript behavioral harness when Node is on
`PATH`. To run that harness directly:

```bash
node microservices/shardshop/shardshop-testing/src/test/resources/browser/order-page.test.cjs \
  microservices/shardshop/shardshop-order/src/main/resources/META-INF/resources/orders/orders.js
```

### Currency display and retained orders

Orders retain their products' currency regardless of buyer region or address.
JPY, KRW and VND display whole amounts when the saved fraction is `.00`; other
currencies display two decimal places. Fractional historical amounts remain visible
exactly. Retained orders and their snapshots are never relabeled or recalculated.

## 2. Buyer and order provider

<sub>[Back to contents](#contents)</sub>

Order creates immutable buyers, allocates order IDs, accepts idempotent order
requests, and exposes their current saga status. Acceptance first persists a
pending order; the stock and ledger workflow determines its eventual outcome.
The [OpenAPI contract](src/main/resources/contracts/openapi.yaml) defines full
request and response schemas; the
[saga architecture](../ARCHITECTURE.md#5-order-creation-and-ledger-saga) defines
the cross-service workflow.

New orders require products allowed by their seller's immutable country/region and
currency precision. An unresolved legacy seller or noncompliant price/cost returns
`422 PRODUCT_CURRENCY_POLICY`. The existing `MIXED_CURRENCIES` and `CURRENCY_MISMATCH`
errors still apply; buyer location does not restrict buying. A US buyer can purchase
a Japanese JPY product. Identical retries of accepted historical orders return saved
state before catalog validation, even if the product is now unavailable for new orders.
See [cutover semantics](../ARCHITECTURE.md#regional-currency-policy-and-retained-data-cutover-step-412).

Order is now a long-running HTTP/1.1 provider. `--check-startup` validates its
routing, generator and pool configuration and exits without opening a database
connection. Step 4.8 implements its producer; step 4.9 deploys both to Kubernetes. Apply the external catalog and ordering migrations before serving requests;
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
unavailable, missing product, mixed currencies, currency mismatch, then seller currency policy. A second
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
| `shardshop.order.stock.enabled` | `true` | Run the stock coordinator in the saga process; `false` isolates other saga components in tests |
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

## 3. Stock reservation and compensation (step 4.2)

<sub>[Back to contents](#contents)</sub>

After HTTP acceptance commits, the saga process polls each buyer shard for
`PENDING_STOCK` sagas and cancelled sagas with unfinished releases. It claims a
durable versioned lease in a short transaction, then reserves each seller shard's
items in ascending product-ID order. Reservation insertion and conditional stock
decrement share one `READ COMMITTED` transaction. Retries of an existing active
reservation keep its original quantity and do not decrement again.

Once every reservation succeeds, a single buyer-shard transaction changes the
order and saga to `PENDING_LEDGER`, reserves distinct transport/result IDs, and
inserts the complete `RecordOrder` envelope. No broker is required by this step;
steps 4.4–4.6 implement CDC delivery, ledger consumption and result publication.

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

## 4. Order outbox CDC relay (step 4.4)

<sub>[Back to contents](#contents)</sub>

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
Since step 4.6, order and ledger share this publisher (`ConfirmedPublisher` in
`shardshop-common`). A connection that the broker closed while it was idle is
replaced before the next command.

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

When CDC is enabled in the saga process, startup fails before the generator reservation unless every
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

CDC is enabled by default in the saga process. HTTP processes never start it. `--check-startup` starts no
workers, but it checks the CDC credentials. Tests can set
`shardshop.order.cdc.enabled=false` to isolate HTTP or stock behavior. The step 4.9 application release gives order
these passwords as `secretKeyRef` environment variables.
Crash injection and synchronized-standby failover drills remain steps 5.5 and 6.2.
Run the relay acceptance against disposable PostgreSQL and RabbitMQ containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am -Paudit clean install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@cdc-relay'
```

## 5. Order result consumer and seller profit (step 4.7)

<sub>[Back to contents](#contents)</sub>

Order consumes `order.ledger-results` with manual acknowledgements and prefetch
one. It validates the ledger-owned result contract and routes by `buyerId`. Then
it takes the transaction advisory lock that order creation takes for the same
order ID (`OrderService.orderLock`). Thus an orphan quarantine cannot pass a
creation in progress, and a creation cannot pass an orphan quarantine.
It checks the reserved result ID, command/saga/buyer identity, fingerprint, amount,
currency and saved outcome before changing the order and saga together. A recorded
result confirms the order; a rejection cancels it and makes its existing stock
release worker eligible. The acknowledgement follows the buyer-shard commit.

Missing orders on reachable primaries go to `ordering.orphan_results`; unresolved
orphans block creation with that order ID. Conflicting identities, payloads and
outcomes go to `ordering.conflicting_results`, preserving saved business state.
Repeated incidents increment their delivery count. Invalid envelopes and IDs are
rejected directly to the result DLQ. Database failures use `basic.reject` with
requeue, so the broker's existing delayed retry and delivery limit apply. An
unavailable primary is never treated as proof that an order is absent.

Order and ledger use the same consumer class,
`dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer` in `shardshop-common`.
It reconnects with capped exponential backoff and 25% jitter. Its log tells an
operator why a delivery did not complete. Log lines can contain validated IDs and
fixed rule texts, but never the result body or a password:

- `Order result consumer stopped; retrying in <delay> ms: <cause>` follows a
  broker connection failure, a closed channel or a cancelled consumer.
- `Order result transaction failed; requested broker retry: <cause>` names the
  database error, without the row values.
- `Malformed ledger result rejected to its dead-letter queue: <rule>` names the
  rule that the result does not obey.
- `Ledger result <messageId> for order <orderId> committed to <ORPHANED|CONFLICT> quarantine: <reason>`
  names the reason that the quarantine table stores.

Ordering migration V5 retains the first accepted envelope in
`order_sagas.ledger_result` and each accepted result envelope in
`order_result_ids.received_envelope`. Matching terminal deliveries are harmless,
including a new reserved reconciliation result ID. A changed same-ID payload or
timestamp remains detectable after inbox/outbox cleanup. This step consumes
reconciliation results; their automatic command enqueuer remains step 5.1.

Confirmation also inserts one durable `ordering.profit_intents` row per item in
the same transaction, using `(unit_price - unit_cost) * quantity` from immutable
order snapshots. Missing legacy costs or unrepresentable item margins are
quarantined for investigation. No partial confirmation or profit intent survives
a failed transaction. `CONFIRMED` can be visible before seller balances catch up.

The profit worker reads bounded batches from each buyer shard, releases the
connection, and posts each intent on its seller's shard. The seller transaction
inserts immutable `catalog.profit_credits` keyed by `(order_id, product_id)` and
increments the matching currency balance only for a new credit. A matching
existing credit is a no-op. Only after that commit does a separate home-shard
transaction mark the intent applied. Restarts, competing workers and unknown
commit responses can repeat the work without double crediting. Losses remain
negative and currencies are never combined.

Transient profit failures persist a retry time with one-to-thirty-second backoff
and jitter. A conflicting credit, missing catalog reference or balance overflow
sets `blocked_reason` and logs the blocked intent; the intent remains for
operator investigation. Runtime cannot edit credit/intent amounts or delete
them. Catalog V4 and the repeatable grants allow order to initialize a currency
at zero and update only its balance, while product's permissions stay unchanged.

The profit worker logs one warning for each shard that fails, with the cause:
`Profit posting unavailable for <shard>; durable intents will retry: <cause>`, or
`Profit polling unavailable for <shard>; ...` when a poll of a home shard fails.
A posting line names the shard that failed: the seller shard for a credit, or the
home shard for a mark or a retry time. The next completed intent on that shard
logs `Profit posting resumed for <shard>`. A blocked intent logs
`Profit intent blocked for order <orderId>, item <position> on <home shard>: <reason>; investigate the durable intent`.
Order IDs appear without digit grouping, thus a search for an ID finds the line.

The saga process enables both workers by default. `--check-startup` validates their settings
without consuming or posting. Configure them independently:

```properties
shardshop.order.results.enabled=true
shardshop.order.profit.enabled=true
shardshop.order.profit.poll-interval=250ms
shardshop.order.profit.batch-size=8
```

The lab chart overrides profit polling to `100ms` and the batch to `32` per
buyer shard. The earlier `250ms`/`8` settings accumulated profit intents at
30 orders/s even while orders reached `CONFIRMED`. Posting remains serial and
idempotent; these settings change only its bounded polling schedule.

The consumer shares the `shardshop.order.cdc.broker.*` connection settings and
credentials with CDC; it can run when CDC is disabled. Profit polling allows
100 ms–30 seconds and batches of 1–100 items. All database phases keep the existing
three-second deadline. Apply the catalog and ordering migrations before starting
the updated order application. Existing versioned migrations remain unchanged;
no new dependency, SQL function or trigger is added.

Build and run the disposable PostgreSQL/RabbitMQ acceptance from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-ledger -am -Pintegration,audit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-results or @order-api or @stock-reservation or @catalog-migration'
```
