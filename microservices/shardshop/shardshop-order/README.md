# ShardShop Order

[ShardShop](../README.md) › **Order** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md) · [OpenAPI contract](src/main/resources/contracts/openapi.yaml)

`shardshop-order` creates buyers and coordinates orders on the regional shards.
The buyer's immutable home region determines where an order is stored; products
may come from sellers in any region.

## Start

The same image runs two processes selected by `shardshop.order.process`: `http`
(the default) serves APIs and pages; `saga` runs stock reservation, compensation,
seller-profit posting, CDC command publication and ledger-result consumption. The
HTTP Deployment has two replicas with `RollingUpdate`; `shardshop-order-saga` has
one replica with `Recreate` to prevent overlapping CDC slot owners. Each JVM reserves
its own generator slot. HTTP replicas run no background saga loops.

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
