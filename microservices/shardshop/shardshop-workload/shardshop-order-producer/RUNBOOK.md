# Order producer runbook

[ShardShop](../../README.md) › [Workloads](../README.md) › [Order producer](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Order stream

Configure a required `run-name`; keep it and the dataset versions and rejection
percentage unchanged across restarts. Each run starts at ordinal `0`. The producer
selects a buyer deterministically, calls the durable order-allocation API, and uses
the returned order ID unchanged. Normal mode (`rejected-order-percent=-1`) follows
this currency mix by **buyer home region**:

| Buyer region | USD | EUR | GBP | CNY | KRW | JPY | VND |
|---|---:|---:|---:|---:|---:|---:|---:|
| US | 90% | 4% | 1% | 3⅓% | 5⁄9% | 5⁄9% | 5⁄9% |
| EU | 5% | 80% | 10% | 3⅓% | 5⁄9% | 5⁄9% | 5⁄9% |
| ASIA | 5% | 4% | 1% | 60% | 10% | 10% | 10% |

Each region has 90% home-currency orders and 5% from each other region. Foreign
EU purchases use EUR:GBP at 4:1; foreign ASIA purchases preserve CNY:KRW:JPY:VND
at 6:1:1:1. The producer interleaves buyer regions and spreads weighted currency
choices through each cycle. These shares are exact every 900 orders per buyer
region (2,700 orders across the full dataset); smaller samples approximate them.
Within each currency, all products take turns as the first item in a run-specific
order. The next one or two products of that currency complete the order.
Quantities are from one through three. Buyer choice within the scheduled region
and quantities hash `runName`, the decimal ordinal and a selection role separated
by NULs. Mutable stock and profits never affect selection.

This is a workload preference: customers can still buy from every seller region,
and orders retain the products' currency. An explicit 0–100 mode keeps the
USD/EUR rejection-test distribution from the issued order ID and selects up to
three products of that currency independently of buyer region. EUR succeeds under
normal ledger configuration; only an explicit scoped test allowlist causes its
business rejection.

Buyer POST, allocation POST, order PUT and status GET retry transport failures and
HTTP 503 with capped backoff and jitter. Other HTTP errors, invalid JSON and
inconsistent response identities fail the run. Retries preserve the same keys,
allocated IDs and exact item payload. HTTP 202 must carry a pending order status;
HTTP 200 from PUT must carry a terminal status. The producer polls pending orders
until `CONFIRMED` or `CANCELLED` through a separate bounded observer. Allocation
and PUT share a submission slot; the next paced start does not wait for the saga.
The observer polls pending orders fairly, independently of submission workers.
The non-terminal limit reserves room for submissions as well as accepted pending orders;
when full, it applies backpressure until terminal observations free room. The [producer control page](../../shardshop-core/workload-control/README.md)
changes the rate of new order starts, and pauses and resumes them, while the producer runs.
Pause drains current submissions and stops new starts while observation continues.
Each order has one allocation/submission/polling deadline, including all
attempts and waits. Exhaustion exits with code 1; shutdown interrupts outstanding
HTTP work. `--check-startup` validates configuration without HTTP requests, and an
invalid setting logs one error. A failed order phase names the stuck order, for
example `Order producer failed: Order completion deadline exceeded (ordinal 3,
buyer <buyerId>, order <orderId>, last status PENDING_LEDGER)`.

A restart replays earlier ordinals with the same coordinates and recovers their
saved orders before reaching new work. This needs no local progress database.
Replay uses the same pacing and bounded submission window as new work. The
`Idempotent-Replay` PUT response header identifies recovered orders, including pending
ones, without an extra status probe. Recovered orders are counted separately and
pending recoveries remain under observation. A new independent
run requires a new explicitly configured name. Do not change selection settings
under an existing name. Normal allocation names now include `catalog-v2:regional-v1`,
separating the new selections from earlier `catalog-v2:balanced` orders. After an
upgrade, the new stream starts at ordinal zero; existing orders and their currencies
are unchanged, so a page containing both streams need not show the new percentages.
The loop does not replenish stock; eventual out-of-stock cancellations are expected
in a sustained run.

## Currency selection

The order producer's `CurrencySelector` validates the selection setting. The default
`-1` uses the planner's buyer-region currency schedule. An explicit 0–100 test
percentage validates an order-service-issued ID, hashes its unchanged UTF-8 text with the shared `Sha256.unsignedDigest`, and selects EUR when
the digest modulo 100 is below the percentage, USD otherwise. Buyer location does
not affect this explicit test mode.
Tests pin the architecture's buckets 0, 9, 10 and 99, plus exact large-ID values.
The producer loop supplies its configured percentage and retains the
chosen payload across retries. Allocation run names include the catalog version and
versioned selection mode so changed payloads cannot collide with retained orders.
The routing library uses the same digest helper; the order producer has no shard-routing
dependency.

## Configuration

| `shardshop.producer` setting | Default | Meaning |
|---|---|---|
| `product-url`, `order-url`, `run-name` | Required | Service base URLs and durable run identity |
| `catalog-version`, `buyer-version` | `catalog-v2`, `buyers-v1` | Immutable workload definitions |
| `rejected-order-percent` | `-1` | Buyer-region currency mix above; explicit 0–100 selects the scoped USD/EUR test distribution |
| `connect-timeout`, `request-timeout` | `2s`, `5s` | Connection and complete HTTP-attempt limits |
| `startup-deadline` | `5m` | Complete budget for each of catalog and buyer setup; maximum 5m |
| `order-deadline` | `60s` | Allocation through terminal status; maximum 10m |
| `order-rate` | `0.5` | Initial starts per second, 0–30 with at most three decimals; lab-qualified operating cap |
| `submission-concurrency` | `4` | Allocation-plus-PUT slots, 1–12 |
| `observer-concurrency` | `2` | Independent status polling slots, 1–12 |
| `max-non-terminal-orders` | `100` | Backpressure limit including outstanding submissions; at least submission concurrency and at most 10,000 |
| `poll-interval` | `250ms` | Delay between pending status reads |
| `max-attempts` | `5` | At most 1–10 attempts per ordinary HTTP operation |
| `retry-backoff`, `max-retry-backoff` | `100ms`, `2s` | Exponential base/cap, with 50–100% jitter; cap at most 30s |

All durations must be positive. Connection timeout is at most 30s, request timeout
at most 1m, and poll interval at most 30s.

## Rate limit

The 30 starts/s operating cap follows the
[step 4.14 lab measurements](../../CHANGELOG.md#step-414): target 10 achieved 9.78 new
orders/s with a final rolling write p95 of 91.42 ms; target 30 achieved 29.45/s
with a write p95 of 93.73 ms and bounded order, outbox and profit backlogs. These
short retained-stock runs include stock cancellations; they are not a guarantee
for every host or an indefinitely stocked catalog. The default remains 0.5/s.
Four submission slots were sufficient for that rate. The concurrency limit of 12
matches one HTTP JVM's aggregate database pool budget: four connections per shard.
Additional HTTP requests can wait up to one second for a connection before `503`;
the pool does not reject every thirteenth concurrent request immediately.

## Stock use

Stock is finite: `catalog-v2` starts with 1,000 units per product, 201,000 in total,
and seeder reruns never replenish it. With the normal equal-region currency mix,
an order requests approximately 5.85 units on average (three items of mean quantity
two, except the one-product VND currency). Before shortages, 10 orders/s requests
about 58.5 units/s; 30 orders/s requests about 175.6 units/s. The single VND product
is the first expected bottleneck: its 100 orders per 2,700-order cycle consume
about 0.074 units per overall order. Fresh VND stock lasts approximately 22.5
minutes at 10/s, 7.5 minutes at 30/s, or 7.5 hours at the unchanged 0.5/s default.
The four KRW products last approximately 30 minutes at 10/s. These are workload
averages, not expiry guarantees; deterministic quantities and retained stock change
the actual boundary. Report confirmations and stock cancellations separately in
capacity measurements so exhausted stock does not masquerade as faster ledger work.

## Lab run

The kind values set `orderProducer.runName: kind-saga-v1`. Keep that name and
selection settings stable when resuming this retained run. Use a new run name only
for an intentional new workload. The values schema rejects `$` in the run name,
because SmallRye Config expands `${...}` and removes the escapes `$$` and `\$`. The
default producer setting `rejected-order-percent: -1` selects the buyer-region
currency mix, rotating the 201 catalog products within their currencies.
`ledgerApplication.configuration` permits `CNY,EUR,GBP,JPY,KRW,USD,VND`. The
explicit 0–100 producer test mode selects USD/EUR, and rejection requires a
separately scoped ledger policy. Normal EUR purchases succeed.

## Tests

The packaged `OrderProducer.feature` uses real product/order APIs and PostgreSQL.
It hides committed buyer, allocation and PUT responses behind a test proxy, then
checks identical retries, restart recovery, all buyer regions, home-currency preference,
all seven currencies and terminal polling. The test controls terminal database transitions to isolate
the producer's HTTP behavior; the [kind saga gate](../../shardshop-testing/RUNBOOK.md#saga-gate-and-drill) verifies the complete deployed saga. Run:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-producer'
```
