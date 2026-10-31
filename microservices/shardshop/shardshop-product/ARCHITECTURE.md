# Product architecture

[ShardShop](../ARCHITECTURE.md) › **Product** · [README](README.md) · [Runbook](RUNBOOK.md) · [OpenAPI contract](src/main/resources/contracts/openapi.yaml)

Product owns seller and product creation, regional catalog reads, currency-policy
enforcement and the read-only product page. This document also records catalog
storage and the retained-data currency cutover. Order owns the stock transaction
protocol and saga; the [order architecture](../shardshop-order/ARCHITECTURE.md)
describes how those writes use catalog storage.

- **Runs as:** two identical, stateless pods behind one Kubernetes Service. Pod-local
  state never determines correctness.
- **Owns:** sellers, products and their creation keys in schema `catalog`, on the
  home shard of each seller, and the zero-balance setup of seller profit.

## Product API

Product generates seller/product IDs when workloads send ID-free payloads. There
are no embedded datasets or fixture-ID whitelists. Creation requires a stable
`Idempotency-Key`; data calls route by returned seller ID. Product payloads expose
no independent region or shard selector.

| Endpoint | Purpose | Responses |
|---|---|---|
| `POST /api/v1/sellers` | Create a company seller with requested `companyName` and immutable `region` and `countryCode`; product chooses an ID routed to that region | `201` with the generated ID; `200` for an identical durable key/payload retry; `409` for conflicting key reuse; `400 INVALID_REQUEST` for an invalid region |
| `GET /api/v1/sellers/{sellerId}` | Read the seller | The seller, or `404` |
| `GET /api/v1/sellers/by-key/{creationKey}?region=US` | Recover the seller ID by its persisted creation key and region | The seller, or `404` |
| `POST /api/v1/sellers/{sellerId}/products` | Create a product with a generated ID and immutable name, description, price, unit cost, currency and initial stock on its seller's home shard | `201`; `200` for an identical key/payload retry; `409` for conflicting key reuse; `422 SELLER_NOT_FOUND` when the seller does not exist |
| `GET /api/v1/sellers/{sellerId}/products/{productId}` | Read the product with current stock | The product, or `404` |
| `GET /api/v1/sellers/{sellerId}/products/by-key/{creationKey}` | Recover that same stored product and generated ID without creating anything | The product, or `404` |
| `GET /api/v1/products?region=US&limit=25&after=<productId>` | List created products from every shard in the selected seller home region | One page, as described below |

- Seller responses contain current `profitsEarned` balances, initially zero in
  allowed currencies; retries preserve these service-owned amounts.
- Product creation retries compare the original initial stock, preserving any
  current stock changed by orders.
- The listing requires `region`; the page limit defaults to 25 and is bounded to
  1–100. The optional `after` is a canonical positive product ID. Results are
  ordered by numeric product ID, with a nullable `nextAfter` for continuation. Each
  item includes seller identity, product identity/name, initial/current stock,
  price and currency, and per-product `profitsEarned` from applied profit credits.
  A product with no credits reports zero in its own currency.

### Validation

- All endpoints validate the canonical positive decimal ID contract before IO;
  invalid path/body IDs return `400 INVALID_REQUEST`.
- Product JSON uses string IDs and storage uses `BIGINT`, including the seller and
  product references in order items.
- Stock changes only through the order saga's reservations; no HTTP endpoint
  changes it.
- Product rejects POST bodies over 32 KiB with the same JSON `400 INVALID_REQUEST`
  error. Product descriptions are nonblank and at most 2,000 Unicode scalar values.
- Unit cost uses the product's currency and may exceed its price.

## Product page

The read-only `/products/` page ships inside the product application and uses the
listing API. It lists arbitrary created products without depending on workload
datasets.

- **Navigation.** Its region selector offers US, EU and ASIA; pagination and
  refresh only perform reads. Centered pagination shows First, Previous, Page N,
  Next and Last. First returns to page 1; Last follows the remaining API cursors to
  the final page within the same bounded loading deadline. First shares Previous's
  disabled state, and Last shares Next's, including while loading or after errors.
- **Links.** The browser URL stores `region` and `page` so copied links, reloads
  and browser history restore the selected view. Opening a numbered page resolves
  preceding API cursors under one bounded loading deadline; navigation reuses known
  cursors. Refresh reloads the current page, and changing region starts at page 1.
- **Caching.** The HTML, stylesheet and script are served with
  `Cache-Control: no-store` to prevent a deployment from mixing new markup with an
  older cached asset. Versioned asset links also bypass the URLs cached before that
  policy was introduced.
- **Consistency.** Each regional request shares one bounded database phase budget
  across shard reads and obeys the existing read profile. A shard failure fails the
  complete page; there is no partial-success response. Pagination does not promise
  a snapshot across shards or separate requests.
- **Privileges.** Product receives column-level read access to the credit fields
  needed for earnings; it gains no credit mutation privileges.

## Creation keys and seller placement

Workloads send `POST /api/v1/sellers`,
`POST /api/v1/sellers/{sellerId}/products`, and `POST /api/v1/buyers` with an
`Idempotency-Key` and ID-free creation payload. Product/order generate and persist
the entity ID. Requested seller/buyer home regions are immutable after creation;
the service selects a new ID whose version-2 route matches the requested region.
Candidate generation is bounded; failure returns `503 ID_GENERATION_UNAVAILABLE`
without partial creation. Products inherit their seller's region and have no
independent region field.

A creation retry key is not an ID. Keys are scoped by entity collection and
requested home region for sellers/buyers, or by parent seller ID for products.
The service durably stores the key, normalized creation payload and generated ID
atomically. A retry, including after a lost response or process restart, returns
the existing ID with `200`; first creation returns `201`, and a different payload
under the same scope/key returns `409`. Retries reuse the original region/key.
Creation-key retention must match entity retention, and existing retries must
work without generating another ID. Catalog V2 adds catalog keys and lookup
indexes; ordering V2 adds buyer keys and enriched person fields. Existing V1
migrations remain immutable. A dataset version may select subsets, but never
changes a retained key's payload.

Product assigns each seller creation key a fixed home within the requested
region's ordered shard subset using unsigned SHA-256 of UTF-8
`seller\0region\0key` modulo that subset's size (`\0` is a NUL separator).
It generates at most 1024 candidates by default until the existing ID router
selects that exact shard. By-key lookup repeats this placement without generating
an ID. A transaction advisory lock on the signed low 64 digest bits serializes
the same region/key; products use `product\0sellerId\0key` on the parent shard.
This preserves uniqueness with multiple shards in one region. Hash collisions
only serialize unrelated requests; database constraints retain full key scopes.
Changing inventory order/membership/regions still requires an explicit data
migration, including these durable key homes.

## Seller profit

Seller profit is the sum of `(snapshotted unitPrice - unitCost) × quantity` for
confirmed sales, retained separately for each currency. New sellers start with
`0.00` in the policy currencies for their region/country. Historical balances
remain unchanged, including currencies no longer allowed for new prices. A loss is
negative; no exchange conversion or cross-currency netting occurs. Workloads
only read these balances. They never seed a balance or infer profit from stock.

Confirmation atomically inserts `ordering.profit_intents` from immutable item
price/cost snapshots on the buyer's shard. A separate worker inserts the permanent
`catalog.profit_credits` identity `(order_id, product_id)` and increments the
seller's currency balance in one seller-shard transaction. Only a new credit
changes the balance; a retry verifies the existing identity and amount. A separate
buyer-shard transaction marks the intent applied. No transaction or connection
spans shards, and `CONFIRMED` can precede eventual balance application.

- Pending, reserved, cancelled and rejected orders earn no profit.
- Unknown legacy costs prevent confirmation and require investigation instead of
  fabricated zero margin.
- Retries use durable scheduling and bounded phases; permanent credit conflicts,
  missing catalog references or balance overflow block the intent and alert for
  investigation.
- Column grants permit
  order to initialize zero currency balances and update their amounts, but not
  mutate or delete immutable credit/intent payloads. Product can still only
  initialize zero balances.

## Read profiles, TLS and deadlines

All writes, order validation, order status reads, and saga processing use `-rw`.
Product reads also use `-rw` by default. An optional load-test profile uses `-ro`
to demonstrate replica read scaling; it explicitly permits stale or temporarily
missing products. Standby replay can lag behind committed primary writes. See
[PostgreSQL standby replication](https://www.postgresql.org/docs/current/warm-standby.html).

Product's generated JDBC URLs use `sslmode=verify-full`, with each shard's CA
mounted at `/etc/shardshop/catalog/<shard>/ca.crt`. Bare URL overrides keep those
secure defaults; an explicit TLS mode in a deployment URL overrides the mode.
The per-connection `root-certificate` setting supports a different CA mount path.

The replica read profile is strict: it **does not fall back to `-rw`**. CNPG's
`-ro` Service selects standbys only; with three instances it has no endpoints only
when neither standby is ready, such as when one is down and the other is promoted.

| Budget | Value | Rule |
|---|---|---|
| Server request deadline | 4 s | Fail affected reads with `503 READ_REPLICA_UNAVAILABLE`, never `404` |
| Database phase | 3 s in total | Covers pool acquisition, connection setup, SQL execution, and any retry; every individual timeout must fit the remaining budget |
| Response margin | Final server second | Reserved for cancellation/error mapping and sending the response |
| Reader deadline | 5 s | Leaves a one-second response margin in the local lab |

Discard broken connections and reconnect to `-ro` when a standby becomes ready.
Record these failures separately from stale/missing products and continue requests
to unaffected shards. This keeps the measured primary and replica workloads
distinct. See [CNPG Service roles](https://cloudnative-pg.io/docs/1.30/service_management/).

The opt-in [`kind-replica-read-drill`](../shardshop-testing/RUNBOOK.md#strict-replica-read-drill),
also selected by `kind-drill`, checks immediate primary reads after acknowledged
writes, controlled replica lag (`404` until replay resumes), clean 200/s reader
traffic, reads/writes with one fenced standby, and per-product-pod `503` responses
in less than four seconds with both standbys fenced. A replayed `ACCESS EXCLUSIVE`
lock on `catalog.products` exercises hung queries separately. Outage reader reports
must preserve successful peer reads and record replica unavailability without
client deadline or transport faults.

The drill captures exact ConfigMap data and the full pod template, converts literal
environment/JVM overrides under the shared recovery guard, and rejects indirect
configuration and concurrent edits. Recovery restores replay, fencing and the
original profile, checks every ready product pod, removes owned fixtures, restores
workload controls and passes the primary deployment gate before unlocking.

## Regional currency policy

| Seller region | Seller country | New product currencies | Fractional digits |
|---|---|---|---|
| US | US | USD | 2 |
| EU | Existing EU27 country codes | EUR, GBP | 2 |
| ASIA | CN | CNY | 2 |
| ASIA | VN | VND | 0 |
| ASIA | KR | KRW | 0 |
| ASIA | JP | JPY | 0 |

- GBP is allowed throughout the application's EU region; this does not add
  countries.
- Unknown countries and region/country mismatches fail new seller creation. Product
  creation checks this service-owned policy, including arbitrary non-dataset
  entities. `shardshop-core/common` owns `CurrencyPolicy`; workload resources are
  not runtime policy.
- Seller country is immutable. New orders validate the seller policy and both
  catalog price and unit cost before acceptance. Buyer home region/address never
  filters currencies. All items and the requested order currency must match; there
  is no FX.

Minor units follow SIX's ISO 4217 List One ([official list](https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml)).
The exact fixed-two-decimal wire format and `NUMERIC(19,2)` storage remain compatible:
JPY/KRW/VND new amounts must end in `.00`. Reject fractional minor units instead of
rounding. Totals and signed profit derive exactly from saved prices/costs and integer
quantities. Both webpages omit `.00` for these currencies, but preserve fractional
historical amounts instead of hiding or rounding them.

### Retained-data cutover

Catalog V6 adds nullable `country_code`; it backfills only exact retained catalog-v1
key, company name and region matches against verified source provenance. It changes
no amount, currency, profit, stock, creation key or ID. Other seller countries stay
unresolved. `catalog.currency_policy_legacy` reports unresolved/noncompliant catalog
rows; the product API/page exposes `countryCode`, `newOrderEligible` and `legacyReason`.
These rows remain readable and identical creation retries remain recoverable.
New purchases of ineligible rows fail with `422 PRODUCT_CURRENCY_POLICY`.

Ordering V7 gives retained orders `currency_policy_version=1`. Newly accepted orders
explicitly save version 2. This value selects the RecordOrder schema version even
when stock processing resumes after a restart. Version 2 checks supported currencies
and minor-unit precision at the ledger boundary; version 1 preserves historical
snapshots and pending orders. Existing outboxes, transport identities, decisions,
result envelopes and balances are never rewritten. Duplicate orders return their
saved state before any catalog policy lookup; duplicate ledger decisions replay their
saved outcome before allowlist evaluation. Ledger results retain their v1 format.

Normal deployment permits CNY, EUR, GBP, JPY, KRW, USD and VND. Rejection/replay drills
scope a temporary allowlist to their own fixtures and restore the deployed policy.
The first rollout holds the order Deployment paused until the new ledger image is
ready, preventing v2 commands from reaching a v1-only consumer; the
[upgrade runbook](../infra/RUNBOOK.md#first-upgrade-from-a-v1-only-ledger) documents this sequence.
No retained lab reset or baseline migration edit is part of this cutover.

## Catalog storage

`catalog.sellers`, `catalog.products` and `catalog.stock_reservations` all live on
the seller's shard, so their foreign keys stay local. The
[database layer](../database/ARCHITECTURE.md#schema-versions) lists the migration
that added each column.

### Sellers and placement checks

The V1 `catalog.sellers` table holds a seller's positive `BIGINT` ID, name, and immutable
`region` (`US`, `EU`, or `ASIA`). `V1__catalog.sql` creates the region column with
its default from the validated `shardRegion` Flyway placeholder and named checks
that require the deployed region and an ID that hashes to this shard. The latter
uses a `CHECK` expression with PostgreSQL's built-in SHA-256, hexadecimal
encoding, exact `NUMERIC` conversion, and modulo operator, with the `shardIndex`
and `shardCount` placeholders. It preserves all 256 digest bits and matches Java's
unsigned routing calculation. Incorrect seller placement is rejected on insertion.
Schema migration V1 and the unchanged version-2 ID-routing algorithm are independent
version numbers. The Maven `kind-topology` scenarios check PostgreSQL's calculation
and the deployed seller and buyer placement constraints on every primary against
the same golden fixtures used by Java, using read-only queries without a failure drill.

The [topology reservation](../shardshop-core/sharding/README.md#topology-changes)
keeps the `shardRegion`, `shardIndex` and `shardCount` placeholder values stable
for the retained dataset. The implemented `ordering.buyers` table likewise persists
an immutable home region and enforces the deployed region and ID placement. Orders
and all their local records inherit the buyer's placement; they have no separate
region selector.

### Products and reservations

`catalog.products` references its seller with a local foreign key and carries the
name, price, currency, `initial_stock` and `stock`, with `0 <= stock <= initial_stock`.
`catalog.stock_reservations` records one row per order and product, keyed by both
IDs, with the reserved quantity and its release time. Products inherit the seller's
region through that relationship; they store no second region value. The foreign
key and seller placement checks prevent product creation on another region's
shard, even through the catalog writer's SQL privileges. Runtime grants allow no
updates to seller identity or region. Placement is enforced by the table
constraints and needs no function execution grants.

The order service writes `catalog.stock_reservations` and adjusts product stock
through Java-managed transactions on the seller's shard. `catalog_reserver` has
column-level `UPDATE (stock)` on products; `catalog_reader` supplies the reads
needed for conditional updates. There is no stock trigger.

### Stock reservation protocol

- **Reserve** inserts a row with `INSERT … ON CONFLICT (order_id, product_id) DO
  NOTHING`. Only when a row was inserted, issue `UPDATE catalog.products SET
  stock = stock - quantity WHERE product_id = … AND stock >= quantity` in the
  same transaction. A zero-row decrement means insufficient stock after the
  reservation's product foreign key has succeeded: roll back the reservation
  insert and report `OUT_OF_STOCK`. A duplicate insert does not decrement again.
  An unknown product fails the foreign key with SQLSTATE `23503`.
- **Release** updates an active reservation with `UPDATE … SET released_at = …
  WHERE … AND released_at IS NULL RETURNING product_id, quantity`. Restore the
  returned quantity to the product in that same transaction. A repeated release
  returns no row and restores nothing. Roll back both writes on failure.
- Reservation identity and quantity are immutable in the application. The Java
  reservation API does not reactivate or delete reservations; any future such
  operation must also adjust stock in the same transaction.
- The order service has no `TRUNCATE` and cannot update sellers or product
  columns other than `stock`. Product and reservation changes must use the
  transaction protocol above; grants alone do not enforce that relationship.

`CHECK (stock >= 0)` backs up the conditional decrement. The reservation
flow must keep `stock` equal to `initial_stock` minus active reserved quantities;
direct SQL can violate that relationship while satisfying the table's checks.
There is no restock operation yet. Writing a reservation directly in SQL does not
change stock; the Java transaction protocol performs both writes.

### Decision: atomic conditional decrement

Java issues the conditional stock update and the reservation write on the same
connection in one shard transaction. This is the pattern AWS documents for
DynamoDB (an update expression with a condition expression, and a request marker
in the same write), combined with Amazon's client-token idempotency.

- **Rejected: optimistic locking with a version column.** Two orders for the same
  product would conflict and retry even when stock covers both, and it would not
  avoid the row lock that every `UPDATE` takes.
- **Isolation.** Reservation writers run at `READ COMMITTED`, PostgreSQL's default,
  where a waiting update rechecks `stock >= quantity` against the newly committed
  row; at `REPEATABLE READ` or `SERIALIZABLE` the second writer would instead fail
  with `40001`.
- **Upgrade path.** If one product's row ever limits throughput, the upgrade is one
  row per sellable unit, claimed with `SELECT … FOR UPDATE SKIP LOCKED` from a
  bounded, replenished pool, as Shopify describes; the lab does not need it.

Sources:
[DynamoDB condition expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.ConditionExpressions.html),
[high-concurrency conditional writes](https://aws.amazon.com/blogs/database/handle-conditional-write-errors-in-high-concurrency-scenarios-with-amazon-dynamodb/),
[idempotent APIs](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/),
[Shopify inventory reservations](https://shopify.engineering/scaling-inventory-reservations).
