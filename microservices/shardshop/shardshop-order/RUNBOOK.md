# Order runbook

[ShardShop](../README.md) › [Order](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

**Working directory:** Run all shell commands from the repository root.

## Buyer and order provider

Order creates immutable buyers, allocates order IDs, accepts idempotent order
requests, and exposes their current saga status. Acceptance first persists a
pending order; the stock and ledger workflow determines its eventual outcome.
The [OpenAPI contract](src/main/resources/contracts/openapi.yaml) defines full
request and response schemas; the
[saga architecture](ARCHITECTURE.md#order-creation-and-ledger-saga) defines
the cross-service workflow.

New orders require products allowed by their seller's immutable country/region and
currency precision. An unresolved legacy seller or noncompliant price/cost returns
`422 PRODUCT_CURRENCY_POLICY`. The existing `MIXED_CURRENCIES` and `CURRENCY_MISMATCH`
errors still apply; buyer location does not restrict buying. A US buyer can purchase
a Japanese JPY product. Identical retries of accepted historical orders return saved
state before catalog validation, even if the product is now unavailable for new orders.
See [cutover semantics](../shardshop-product/ARCHITECTURE.md#retained-data-cutover).

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

## Stock reservation and compensation

After HTTP acceptance commits, the saga process polls each buyer shard for
`PENDING_STOCK` sagas and cancelled sagas with unfinished releases. It claims a
durable versioned lease in a short transaction, then reserves each seller shard's
items in ascending product-ID order. Reservation insertion and conditional stock
decrement share one `READ COMMITTED` transaction. Retries of an existing active
reservation keep its original quantity and do not decrement again.

Once every reservation succeeds, a single buyer-shard transaction changes the
order and saga to `PENDING_LEDGER`, reserves distinct transport/result IDs, and
inserts the complete `RecordOrder` envelope. This step needs no broker; the CDC
relay delivers the command later.

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

## Order outbox CDC relay

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
Order and ledger share this publisher (`ConfirmedPublisher` in
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
`shardshop.order.cdc.enabled=false` to isolate HTTP or stock behavior. The application release gives order
these passwords as `secretKeyRef` environment variables.
Crash injection and synchronized-standby failover drills are planned in steps 5.5 and 6.2.
Run the relay acceptance against disposable PostgreSQL and RabbitMQ containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am -Paudit clean install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@cdc-relay'
```

## Order result consumer and seller profit

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
It reconnects with capped exponential backoff and 25% jitter. The
[README](README.md#logs) lists its log lines.

Ordering migration V5 retains the first accepted envelope in
`order_sagas.ledger_result` and each accepted result envelope in
`order_result_ids.received_envelope`. Matching terminal deliveries are harmless,
including a new reserved reconciliation result ID. A changed same-ID payload or
timestamp remains detectable after inbox/outbox cleanup. The consumer applies
reconciliation results; their automatic command enqueuer is planned in step 5.1.

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

## Order page tests

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
