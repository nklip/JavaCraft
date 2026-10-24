# ShardShop Order

[ShardShop](../README.md) › **Order** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md) · [OpenAPI contract](src/main/resources/contracts/openapi.yaml)

`shardshop-order` creates buyers and coordinates orders on the regional shards.
The buyer's immutable home region determines where an order is stored; products
may come from sellers in any region.

## Start

The same image runs two processes selected by `shardshop.order.process`: `http`
(the default) serves APIs and pages; `saga` runs stock reservation, compensation,
seller-profit posting, CDC command publication, ledger-result consumption and
durable ledger reconciliation and transport cleanup. The
HTTP Deployment has two replicas with `RollingUpdate`; `shardshop-order-saga` has
one replica with `Recreate` to prevent overlapping CDC slot owners. Each JVM reserves
its own generator slot. HTTP replicas run no background saga loops.

The normal `up.sh` workflow applies ordering migrations through V8, including the
transport retention indexes, before deploying the image.
The [manual reconciliation reset](RUNBOOK.md#manual-reconciliation-reset) uses
the separate `ordering_operator` login. Existing labs need `cluster-create.sh`
before `up.sh` once to provision that credential and its roles.

In the lab, `up.sh` deploys both processes; see the [lab start](../README.md#start-the-lab).
Apply the catalog and ordering migrations before order serves requests; the
application never runs Flyway. `--check-startup` validates the routing, generator
and pool configuration and exits without a database connection. In the saga
process it also checks the CDC credentials.

## Monitoring

### Order page

Open <http://127.0.0.1:18081/orders/> after `webpages.sh` starts its tunnels.
Order serves a read-only webpage at `/orders/` from its application image.
Each order shows its ID, the buyer's full name, creation date/time in **UTC**,
current status, cancellation reason when present, total and currency, and
product names with quantities. Hover over a buyer or product name, focus it with
the keyboard, or click it to open details with separate **Copy name** and
**Copy ID** buttons. Product names come from the saved order snapshots.
The page includes orders outside the workload datasets.

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

Orders retain their products' currency regardless of buyer region or address.
JPY, KRW and VND display whole amounts when the saved fraction is `.00`; other
currencies display two decimal places. Fractional historical amounts remain visible
exactly. Retained orders and their snapshots are never relabeled or recalculated.

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

### Write metrics

Order exposes per-JVM `GET /internal/order/write-metrics`: `allocation` and
`put` each report `requests`, `errors`, `sampleSize`, `p50Millis`, `p95Millis` and
`maxMillis` over the last 4,096 completed requests, from body reading through
response writing. Query each HTTP pod to assess both replicas. This internal
endpoint uses the service's existing trust boundary. With the tunnels open, this
command reads the HTTP pod that the Service selects:

```bash
curl -s http://127.0.0.1:18081/internal/order/write-metrics
```

### Prometheus metrics

Both order processes expose Prometheus text metrics at `/q/metrics`. The HTTP
process reports its per-shard pool gauges. The saga process also refreshes a
cached database snapshot every ten seconds through each existing bounded primary
pool; scraping never runs SQL. Custom series carry only `shard`, `process` and,
for saga counts, the fixed `status` label.

The saga metrics `shardshop_order_outbox_pending` and
`shardshop_order_outbox_oldest_pending_seconds` report unpublished work and its
age. `shardshop_order_sagas{status=...}` reports counts for `PENDING_STOCK`,
`PENDING_LEDGER`, `CONFIRMED` and `CANCELLED`; `shardshop_order_sagas_reconciliation_exhausted`
counts pending ledger sagas at the five-attempt limit. The gauges
`shardshop_order_quarantine_orphan_rows` and
`shardshop_order_quarantine_conflicting_result_rows` count retained historical
rows; `shardshop_order_quarantine_orphan_unresolved` separately counts orphan
rows without `resolved_at`. Conflicting-result quarantines are retained incidents,
so their row count does not imply they remain unresolved.

CDC gauges `shardshop_order_cdc_slot_exists`, `shardshop_order_cdc_slot_active`,
`shardshop_order_cdc_checkpoint_exists`, `shardshop_order_cdc_retained_wal_bytes`
and `shardshop_order_cdc_checkpoint_lag_bytes` report slot/checkpoint state and
WAL pressure. `shardshop_order_cdc_recovery_required` reports explicit recovery;
`shardshop_order_cdc_retrying` separately reports ordinary reconnect backoff, and
`shardshop_order_cdc_enabled` shows whether this process owns a reader for the shard.
The checkpoint distance is a WAL pressure measure: heartbeat and other database
activity can move it even when no business command is waiting. Use oldest pending
outbox age to assess business progress. Missing slot/checkpoint distances are
exported as `NaN`. After a failed snapshot, the last values remain visible;
`shardshop_order_metrics_snapshot_success` becomes `0` and
`shardshop_order_metrics_snapshot_age_seconds` continues to grow. Treat old values
as stale until the success gauge returns to `1`.

Pool gauges `shardshop_order_db_pool_active`, `shardshop_order_db_pool_available`,
`shardshop_order_db_pool_awaiting`, `shardshop_order_db_pool_max` and
`shardshop_order_db_pool_max_used` report active, available and awaiting
connections, configured maximum size and maximum pool size observed since process
start. The observed maximum is zero before the pool creates its first connection.
These gauges are local to each JVM and shard.

### Logs

```bash
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-order
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-order-saga
```

Each JVM start logs `Reserved generator ID <n> for this JVM start.` at INFO. The
saga process logs the lines below. They can contain validated IDs and fixed rule
texts, but never a message body or a password.

**CDC relay.** `CDC reader for <shard> stopped; retrying in <delay> ms: <cause>`
follows an ordinary failure. `CDC recovery required for <shard>: <reason>` means
that the reader stopped for explicit recovery; the
[runbook](RUNBOOK.md#order-outbox-cdc-relay) lists the reasons and the recovery.

**Result consumer.** Its log tells an operator why a delivery did not complete:

- `Order result consumer stopped; retrying in <delay> ms: <cause>` follows a
  broker connection failure, a closed channel or a cancelled consumer.
- `Order result transaction failed; requested broker retry: <cause>` names the
  database error, without the row values.
- `Malformed ledger result rejected to its dead-letter queue: <rule>` names the
  rule that the result does not obey.
- `Ledger result <messageId> for order <orderId> committed to <ORPHANED|CONFLICT> quarantine: <reason>`
  names the reason that the quarantine table stores.

**Profit worker.** The profit worker logs one warning for each shard that fails, with the cause:
`Profit posting unavailable for <shard>; durable intents will retry: <cause>`, or
`Profit polling unavailable for <shard>; ...` when a poll of a home shard fails.
A posting line names the shard that failed: the seller shard for a credit, or the
home shard for a mark or a retry time. The next completed intent on that shard
logs `Profit posting resumed for <shard>`. A blocked intent logs
`Profit intent blocked for order <orderId>, item <position> on <home shard>: <reason>; investigate the durable intent`.
Order IDs appear without digit grouping, thus a search for an ID finds the line.

**Reconciliation worker.** `Reconciliation budget exhausted on <shard>: <count> pending ledger sagas require investigation`
means those sagas have spent their five automatic replay attempts. The warning
repeats when the count changes or after five minutes; `Reconciliation budget recovered on <shard>`
means none remain pending at the limit. `Saga reconciliation unavailable for <shard> [at saga <id>]; pending work will retry: <cause>`
reports a failed scan or replay transaction once per affected shard, followed by
`Saga reconciliation resumed for <shard>` after a complete clean pass. See the
[reconciliation runbook](RUNBOOK.md#durable-reconciliation) for inspection and
configuration.

**Transport cleanup.** `Transport cleanup unavailable for <shard>; will retry: <cause>`
reports a failed cleanup phase, followed by `Transport cleanup resumed for <shard>`
after recovery. The [cleanup runbook](RUNBOOK.md#transport-cleanup) explains the
retention window and why published outbox rows can still be waiting for CDC.
