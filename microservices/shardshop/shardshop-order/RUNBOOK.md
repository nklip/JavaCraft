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
Step 5.5 qualifies abrupt process crashes at publication/checkpoint and consumer
commit/acknowledgement boundaries in the [crash suite](../shardshop-testing/RUNBOOK.md#crash-recovery-and-observability).
Synchronized-standby failover remains step 6.2. A process restart is safe with
intact compatible durable state; it cannot repair a missing slot, offset or WAL.
For those incidents, preserve the state and investigate the explicit recovery
log and metrics before attempting any resnapshot. Automated restore/audit tooling
belongs to steps 6.3–6.4; step 5.5 does not add a destructive reset shortcut.
Run the relay acceptance against disposable PostgreSQL and RabbitMQ containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am -Paudit clean install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@cdc-relay'
```

Both order processes expose `/q/metrics`; the saga process refreshes its durable
outbox, saga, quarantine and CDC gauges every ten seconds through the bounded
application pool. A scrape uses the cached sample and performs no database query.
See the [metrics guide](README.md#prometheus-metrics) for names, age semantics,
the distinction between ordinary reconnects and explicit recovery, and why CDC
checkpoint distance is WAL pressure rather than business-event lag. Failed
refreshes leave the last sample visible with a failure gauge and increasing age.

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
reconciliation results from the [durable reconciliation worker](#durable-reconciliation).

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

## Durable reconciliation

The saga process enables ledger reconciliation by default. Every scan reads a
bounded page from each primary and rechecks each candidate under the order
advisory lock and saga row lock. An unpublished command stays with CDC and costs
no attempt. Otherwise the worker atomically reserves new transport/result IDs,
inserts a replay command and advances the persisted counter and due time. It uses
the saved order snapshot, so catalog changes cannot change a replay.

| Property | Default | Purpose |
|---|---|---|
| `shardshop.order.reconciliation.enabled` | `true` | Enable the scanner in the saga process; HTTP never starts it |
| `shardshop.order.reconciliation.poll-interval` | `30s` | Delay between scans, 100 ms–30 s |
| `shardshop.order.reconciliation.batch-size` | `100` | Maximum candidates per shard per scan, 1–1000 |

The waits before attempts are fixed at 60, 120, 240, 300 and 300 seconds. Changing
the polling interval does not shorten these waits. Each database transaction
keeps the three-second phase deadline. A cursor moves past temporarily failing or
unpublished candidates and wraps after the last page. The eligibility cutoff
stays fixed for a pass, so continuous arrivals cannot delay older retries
indefinitely. Restarts discard this scan position; PostgreSQL retains the retry
budget and due time. The stock worker keeps
its independent retry schedule for reservations and releases.

After five attempts, no further automatic command is created. The saga process
logs the number of exhausted pending sagas per shard, on first observation, when
the count changes, and at most once every five minutes while the count is
unchanged. A new process reports the persisted exhausted count again. Inspect the
existing primary with an authorized read connection:

```sql
SELECT saga_id, order_id, reconciliation_attempts, next_reconciliation_at
FROM ordering.order_sagas
WHERE status = 'PENDING_LEDGER' AND reconciliation_attempts = 5
ORDER BY next_reconciliation_at, saga_id;
```

Check the ledger decision, result queues and quarantine, and the
[CDC recovery state](#order-outbox-cdc-relay). Reconciliation does not reset a lost
slot or offset, delete transport rows, or reset an exhausted budget. Safe cleanup
is performed by the separate [transport cleanup worker](#transport-cleanup);
the [manual operator reset](#manual-reconciliation-reset) gives an exhausted saga
a new retry budget. Step 5.1 reused the existing schema and runtime grants.

Run the coordinator/worker checks and packaged PostgreSQL/RabbitMQ scenarios:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@saga-reconciliation'
```

Injected-clock unit tests check each exact due-time boundary and exhausted-budget
alerts without waiting 17 minutes. Packaged scenarios retain the production waits
and make individual sagas due with fixture SQL. They check concurrent scanners,
restarts, unpublished commands and removal of published transport only after a
durable CDC checkpoint has passed the insert. Lost recorded and rejected results
recover through permanent ledger decisions, including a policy change after the
original rejection.

## Manual reconciliation reset

Use this after investigating an exhausted `PENDING_LEDGER` saga and restoring the
failed dependency. The reset operates on one explicit order/saga pair on the
buyer's primary shard. It sets attempts to zero and next eligibility to database
time under the same advisory and row locks as the workers. It does not enqueue a
message itself. The running saga process resumes on its next scan; the configured
poll interval still applies. Resetting a pending saga again is safe, including
after transport cleanup. Terminal, missing and mismatched pairs return `NOOP`.
An unresolved quarantine incident or missing CDC slot/offset still needs its own
recovery; this operation does not clear either condition.

For an existing lab, first run `cluster-create.sh` to provision the new
`ordering-operator` Secret and reconcile its two roles per shard, then `up.sh` to
apply the changed ordering repeatable grants. Both preserve existing credentials
and data. The login has no owner membership, replication, payload access or
application credential mount. Its group may update only
`reconciliation_attempts` and `next_reconciliation_at`; terminal-state protection
comes from the checked-in operation, so use that operation instead of ad hoc SQL.

Use native PostgreSQL 18 `psql` from `PATH`, with `-X` to ignore local startup
commands. On macOS, the Homebrew `postgresql@18` formula is keg-only. Put
`$(brew --prefix postgresql@18)/bin` first in `PATH` in the terminal that runs
`psql`. Determine the buyer's shard from the retained routing inventory and
inspect the exact pair before resetting it. This example selects `shard-b`.

kubectl 1.36.3 can stop a port-forward after one forwarded `psql` connection
closes. It then prints `lost connection to pod` and exits with a nonzero status.
Thus, in one terminal, open the loopback-only primary tunnel in a loop. The loop
starts the tunnel again after each lost connection and stops after Ctrl+C:

```bash
until kubectl --context kind-shardshop -n shardshop port-forward \
  --address 127.0.0.1 service/shard-b-rw 15432:5432; do
  sleep 1
done
```

Send only one `psql` connection through the tunnel at a time. When the tunnel
stops, it also closes all other connections, which can interrupt a commit. Before
each `psql` command, wait for a new `Forwarding from` line. If `psql` reports
`Connection refused`, no SQL ran. Wait for that line and run the command again.

In another terminal, from the repository root, use the following protected
temporary password/CA files. Replace the two IDs with the selected pair. The
certificate hostname remains the primary Service name even though the TCP
connection uses the local tunnel. Do not enable shell tracing or print passwords.

```bash
(
  set -eu
  set -o pipefail
  umask 077
  reset_dir=$(mktemp -d)
  trap 'rm -rf "$reset_dir"' EXIT
  kubectl --context kind-shardshop -n shardshop get secret shard-b-ca \
    -o jsonpath='{.data.ca\.crt}' | base64 --decode >"$reset_dir/ca.crt"
  reset_password=$(kubectl --context kind-shardshop -n shardshop get secret ordering-operator \
    -o jsonpath='{.data.password}' | base64 --decode)
  printf 'shard-b-rw.shardshop.svc:15432:shardshop:ordering_operator:%s\n' \
    "$reset_password" >"$reset_dir/pgpass"
  unset reset_password
  PGPASSFILE="$reset_dir/pgpass" psql -X --no-password \
    "host=shard-b-rw.shardshop.svc hostaddr=127.0.0.1 port=15432 dbname=shardshop user=ordering_operator sslmode=verify-full sslrootcert=$reset_dir/ca.crt connect_timeout=5" \
    --set=order_id=123456789 --set=saga_id=123456790 \
    --file=microservices/shardshop/database/operations/reset-reconciliation.sql
)
```

To inspect first, use the same protected connection options with `psql` but omit
`--file` to open an interactive session, then run the query below. Quit that
session with `\q` before you run the reset. Run the reset only after confirming
the pair. The permitted inspection is:

```sql
SELECT s.order_id, s.saga_id, o.status AS order_status, s.status AS saga_status,
       s.reconciliation_attempts, s.next_reconciliation_at
FROM ordering.order_sagas s JOIN ordering.orders o USING (order_id)
WHERE s.order_id = 123456789 AND s.saga_id = 123456790;
```

The SQL file rejects absent, noncanonical, nonpositive or overflowing IDs. It uses
a two-second lock timeout, three-second statement timeout and ten-second total
transaction timeout. A timeout or SQL error exits nonzero and rolls back. Success
prints `RESET|order_id|saga_id|previous_attempts|new_eligibility_UTC` only after
commit; `NOOP|order_id|saga_id` means no matching pending pair changed. A lost
connection during commit has an uncertain outcome: inspect the pair before
retrying, since the worker may already have consumed the new budget. Each
deliberate reset grants a fresh budget; repeating it does not undo business effects.
Retain the output with the operator's incident record. Stop the tunnel loop with
Ctrl+C after use. If a new `Forwarding from` line appears, press Ctrl+C again. See the [native psql options](https://www.postgresql.org/docs/18/app-psql.html).

Verify the SQL boundary, permissions and recorded/rejected recovery scenarios:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@manual-reconciliation-reset or @manual-reconciliation-reset-sql'
```

## Transport cleanup

The saga process enables transport cleanup by default. Apply ordering migrations
through V8 using the normal `up.sh` workflow; V8 adds retention indexes. HTTP
replicas never start cleanup, and `--check-startup` validates settings without
running it. The existing application role and database pools perform the work.

| Property | Default | Bounds |
|---|---|---|
| `shardshop.order.cleanup.enabled` | `true` | Enable cleanup in the saga process |
| `shardshop.order.cleanup.retention` | `24h` | 1 second–365 days |
| `shardshop.order.cleanup.poll-interval` | `30s` | 100 ms–60 seconds |
| `shardshop.order.cleanup.batch-size` | `1000` | 1–1000 rows per table and shard |

An inbox must be older than the window measured from `received_at`; an outbox
must be older than the window measured from confirmed `published_at`. An old
creation time alone does not qualify a recently published command. Unpublished
commands, permanent identities and quarantine remain.

Outbox cleanup has two phases: capture a bounded set of message IDs and a WAL
position after reading them, then wait for the persisted CDC streaming checkpoint
to pass that position. It rechecks age and publication when deleting only those
IDs. A successful deletion captures the next bounded batch in the same poll.
An absent or unusable checkpoint retains the batch; restarting captures a new
batch and position. Each database phase has a three-second deadline. Inbox cleanup
continues while an outbox batch waits for CDC.

`Transport cleanup unavailable for <shard>; will retry: <cause>` reports a failed
phase once per affected shard. `Transport cleanup resumed for <shard>` reports
recovery. If old published rows remain, inspect the [CDC state](#order-outbox-cdc-relay)
and its logs; publication alone is insufficient. This query reports rows past
the age window, before the additional CDC check, using the default window:

```sql
SELECT count(*) AS old_published_commands
FROM ordering.order_outbox
WHERE published_at < statement_timestamp() - INTERVAL '24 hours';
```

The [retention contract](../database/ARCHITECTURE.md#retention) defines the retained
business records. Changing cleanup settings never grants a new reconciliation
budget or repairs a missing slot. Run the packaged cleanup and migration checks:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@transport-cleanup or @transport-cleanup-migration'
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
