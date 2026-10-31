# shardshop-order-producer

[ShardShop](../../README.md) › [Workloads](../README.md) › **Order producer**

`shardshop-order-producer` creates its buyers, then generates a deterministic stream of regional orders and
submits them through the order API. It keeps a bounded number of submissions in flight and observes each
accepted order until the order is terminal.

- **Runs as:** the Deployment `shardshop-order-producer`, one pod, with the run name `kind-saga-v1` in the lab.
- **Uses:** the [datasets](../shardshop-datasets/README.md), the
  [order allocation provider](../../shardshop-order/ARCHITECTURE.md#service-issued-order-allocations) and
  [workload control](../../shardshop-core/workload-control/README.md) for its live rate.
- **Never:** generates IDs, keeps a progress store, selects orders from current stock or profit, or has database
  credentials, a Kubernetes token or a shard router.

## Design

The producer is a continuous HTTP/1.1 workload. See the
[shared startup lifecycle](../README.md#workload-lifecycle) for the catalog gate.

### Currency mix

The seeder creates country-aware sellers and products in US, EU and ASIA. The
[shared currency policy](../../shardshop-product/ARCHITECTURE.md#regional-currency-policy) governs product
prices, and the location of the buyer never limits currency. The normal ledger configuration accepts all seven
supported currencies.

The default `rejected-order-percent=-1` uses a buyer-region currency mix. Each region has a 900-order cycle
(2,700 orders across the full dataset) with these currency counts:

| Buyer region | USD | EUR | GBP | CNY | KRW | JPY | VND |
|---|---:|---:|---:|---:|---:|---:|---:|
| US | 810 | 36 | 9 | 30 | 5 | 5 | 5 |
| EU | 45 | 720 | 90 | 30 | 5 | 5 | 5 |
| ASIA | 45 | 36 | 9 | 540 | 90 | 90 | 90 |

US orders are 90% USD. EU orders are 80% EUR and 10% GBP. ASIA orders are 60% CNY and 10% each KRW, JPY and
VND. The remaining 10% of each region is split equally between the other two regions. Foreign EU purchases
split EUR:GBP at 4:1, and foreign ASIA purchases split CNY:KRW:JPY:VND at 6:1:1:1. The shares are exact every
900 orders per buyer region, and smaller samples approximate them.

These are workload preferences. They do not change the product-pricing policy or the cross-region purchase
eligibility: customers can still buy from every seller region, and orders keep the currency of their products.

An explicit `rejected-order-percent` from 0 through 100 keeps the USD/EUR test distribution from the allocated
order ID, and selects up to three products of that currency independently of the buyer region. A rejection test
must also apply its own temporary ledger allowlist. EUR succeeds under the normal ledger configuration.

### Deterministic regional order stream

The producer resolves the full catalog, creates or recovers all buyers, then starts at ordinal zero for its
required configured run name. Normal traffic interleaves US, EU and ASIA buyers.

- **Currency schedule.** Smooth weighted round-robin spreads the choices through each cycle. Add each weight to
  its current score, choose the highest (ties follow the order USD, EUR, GBP, CNY, KRW, JPY, VND), then subtract
  900 from the chosen score. Scores start at zero. A subset of buyer regions interleaves only its available regions.
- **Buyer.** The buyer within a region is chosen from a hash of the unchanged run name, the decimal ordinal and
  the `buyer` role, separated by NULs, over descriptors sorted by definition key.
- **Items.** Products within each currency are sorted by the unsigned SHA-256 of the run name, NUL, `first-item`,
  NUL and the definition key, with the key as the tie-breaker. The number of preceding orders in that currency
  selects the next first product, across all buyer regions and cycle boundaries. The following products in that
  order complete up to three distinct items, with deterministic quantities from one to three. Thus every product
  of a currency is visited before one repeats, and currencies with fewer products can exhaust their stock
  sooner. Current stock and profits are never selection inputs.
- **Stream identity.** Normal allocations use `catalog-v2:regional-v1`. This keeps the buyer, currency and item
  selections separate from the earlier `catalog-v2:balanced` stream, whose orders are unchanged. Explicit
  USD/EUR drill namespaces are unchanged.

### Concurrency and backpressure

| Setting | Default | Maximum |
|---|---:|---:|
| Submission slots | 4 | 12 |
| Observer slots | 2 | 12 |
| Non-terminal orders, including outstanding submissions | 100 | 10,000 |

Each submission owns allocation and PUT, then releases its slot without a wait for the saga. Fair bounded
polling observes accepted pending orders independently of the submission workers. A full non-terminal window
applies backpressure until terminal observations free room. The PUT header `Idempotent-Replay` classifies
recovered orders without a GET probe. Recovered pending orders stay under observation, and recovered counts
stay separate from new outcomes.

### Restart and replay

A restart reconstructs and replays earlier ordinals through the same allocation and PUT APIs before it
advances, with the same coordinates and immutable identities. This trades restart catch-up time for no
workload-owned progress store. Replay uses the same pacing and bounded submission window as new work, and Pause
stops new submissions while the independent observer continues.

### Deadlines and failures

One complete deadline bounds allocation, submission, retries and terminal polling for each ordinal (60 seconds
by default). Buyer POST, allocation POST, order PUT and status GET retry transport failures and HTTP 503 with
bounded attempts and capped, jittered backoff. Retries keep the same keys, allocated IDs and exact item
payload. Other HTTP errors, invalid JSON and inconsistent response identities fail immediately. HTTP 202 must
carry a pending order status, and HTTP 200 from PUT must carry a terminal status. A failed phase stops the
producer for a restart.

## Operation

### Start

In the lab, `up.sh` deploys the producer. The producer first resolves every catalog seller and product by
creation key and verifies the returned definitions. Missing entities, transport failures and HTTP 503 repeat
the complete lookup with one-to-fifteen-second exponential backoff and jitter, for at most five minutes.
Invalid JSON or mismatching definitions fail immediately. Only after the catalog is complete does the producer
create or recover all 3,000 buyers through the idempotent order API. Buyer creation has its own complete startup
deadline.

With product and order reachable at the configured URLs, launch the packaged producer from the repository root.
`--check-startup` validates the configuration without HTTP requests, and an invalid setting logs one error.

```bash
java -Dshardshop.producer.product-url=http://localhost:8080 \
  -Dshardshop.producer.order-url=http://localhost:8081 \
  -Dshardshop.producer.run-name=local-run-1 \
  -jar microservices/shardshop/shardshop-workload/shardshop-order-producer/target/quarkus-app/quarkus-run.jar
```

### Order stream

Configure a required `run-name`. Keep it, the dataset versions and the rejection percentage unchanged across
restarts. Each run starts at ordinal `0`. The producer selects a buyer deterministically, calls the durable
order-allocation API and uses the returned order ID unchanged. It polls pending orders until `CONFIRMED` or
`CANCELLED` through the separate bounded observer. The [control page](../../shardshop-core/workload-control/README.md)
changes the rate of new order starts, and pauses and resumes them, while the producer runs. Pause drains the
current submissions and stops new starts while the observation continues.

Exhaustion of the order deadline exits with code 1, and a shutdown interrupts outstanding HTTP work. A failed
order phase names the stuck order, for example
`Order producer failed: Order completion deadline exceeded (ordinal 3, buyer <buyerId>, order <orderId>, last status PENDING_LEDGER)`.

A restart recovers the saved orders before it reaches new work, and needs no local progress database. A new
independent run requires a new explicitly configured name. Do not change selection settings under an existing
name. The loop does not replenish stock, so eventual out-of-stock cancellations are expected in a sustained run.

The `CurrencySelector` of the producer validates the selection setting. The explicit 0-100 test percentage
validates an order-service-issued ID, hashes its unchanged UTF-8 text with the shared `Sha256.unsignedDigest`,
and selects EUR when the digest modulo 100 is below the percentage and USD otherwise. The buyer location does
not affect this mode. Tests pin the buckets 0, 9, 10 and 99 and exact large-ID values. The producer keeps the
chosen payload across retries. Allocation run names include the catalog version and the versioned selection
mode, so changed payloads cannot collide with retained orders. The producer has no shard-routing dependency.

### Monitoring

The [producer control page](../../shardshop-core/workload-control/README.md#monitoring) on port 18082 shows the
state, the rate of new orders, the backlog and the latency. The control status `producer` object has
`submissionConcurrency`, `observerConcurrency`, `maxNonTerminalOrders`, `nonTerminalBacklog`, `backpressured`,
`recoveredOrders`, `confirmedOrders` and `cancelledOrders`. `writeLatency` measures allocation through PUT
acceptance, and `sagaLatency` measures allocation through terminal observation. Both report the lifetime
`samples` and rolling `meanMillis` and `p95Millis` over the last 1,024 new orders. Replay is excluded.

The producer logs startup counts and periodic outcome counts without buyer profiles or HTTP bodies.

```bash
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-order-producer
```

The [order write metrics](../../shardshop-order/README.md#write-metrics) show the server side of the same writes.

### Configuration

| `shardshop.producer` setting | Default | Meaning |
|---|---|---|
| `product-url`, `order-url`, `run-name` | Required | Service base URLs and durable run identity |
| `catalog-version`, `buyer-version` | `catalog-v2`, `buyers-v1` | Immutable workload definitions |
| `rejected-order-percent` | `-1` | Buyer-region currency mix. An explicit 0-100 selects the scoped USD/EUR test distribution |
| `connect-timeout`, `request-timeout` | `2s`, `5s` | Connection and complete HTTP-attempt limits. At most 30s and 1m |
| `startup-deadline` | `5m` | Complete budget for each of catalog and buyer setup. Maximum 5m |
| `order-deadline` | `60s` | Allocation through terminal status. Maximum 10m |
| `order-rate` | `0.5` | Initial starts per second, 0-30 with at most three decimals |
| `submission-concurrency` | `4` | Allocation-plus-PUT slots, 1-12 |
| `observer-concurrency` | `2` | Independent status polling slots, 1-12 |
| `max-non-terminal-orders` | `100` | Backpressure limit including outstanding submissions. At least submission concurrency, at most 10,000 |
| `poll-interval` | `250ms` | Delay between pending status reads. At most 30s |
| `max-attempts` | `5` | 1-10 attempts for each ordinary HTTP operation |
| `retry-backoff`, `max-retry-backoff` | `100ms`, `2s` | Exponential base and cap, with 50-100% jitter. Cap at most 30s |

All durations must be positive.

### Rate limit

The operating cap of 30 starts/s follows the [lab measurements](../../CHANGELOG.md#step-414). Target 10 achieved
9.78 new orders/s with a final rolling write p95 of 91.42 ms. Target 30 achieved 29.45/s with a write p95 of
93.73 ms and bounded order, outbox and profit backlogs. These short retained-stock runs include stock
cancellations. They are not a guarantee for every host or an indefinitely stocked catalog. The default stays
0.5/s. Four submission slots were sufficient for that rate. The concurrency limit of 12 matches the aggregate
database pool budget of one HTTP JVM: four connections per shard. Additional HTTP requests can wait up to one
second for a connection before `503`. The pool does not reject every thirteenth concurrent request at once.

### Stock use

Stock is finite. `catalog-v2` starts with 1,000 units per product, 201,000 in total, and seeder reruns never
replenish it. With the normal equal-region currency mix, an order requests approximately 5.85 units on average
(three items of mean quantity two, except the one-product VND currency). Before shortages, 10 orders/s requests
approximately 58.5 units/s, and 30 orders/s requests approximately 175.6 units/s. The single VND product is the
first expected bottleneck: its 100 orders per 2,700-order cycle consume approximately 0.074 units per overall
order. Fresh VND stock lasts approximately 22.5 minutes at 10/s, 7.5 minutes at 30/s, or 7.5 hours at the
default 0.5/s. The four KRW products last approximately 30 minutes at 10/s. These are workload averages, not
expiry guarantees. Report confirmations and stock cancellations separately in capacity measurements, so
exhausted stock does not look like faster ledger work.

### Lab run

The kind values set `orderProducer.runName: kind-saga-v1`. Keep that name and the selection settings stable when
you resume this retained run. Use a new run name only for an intentional new workload. The values schema
rejects `$` in the run name, because SmallRye Config expands `${...}` and removes the escapes `$$` and `\$`.
`ledgerApplication.configuration` permits `CNY,EUR,GBP,JPY,KRW,USD,VND`. The default
`rejected-order-percent: -1` rotates the 201 catalog products within their currencies. A rejection test with
the explicit 0-100 mode needs a separately scoped ledger policy.

### Tests

The packaged `OrderProducer.feature` uses real product and order APIs and PostgreSQL. It hides committed buyer,
allocation and PUT responses behind a test proxy, then checks identical retries, restart recovery, all buyer
regions, home-currency preference, all seven currencies and terminal polling. The test controls the terminal
database transitions to isolate the HTTP behavior of the producer. The
[kind saga gate](../../shardshop-testing/RUNBOOK.md#saga-gate-and-drill) verifies the complete deployed saga.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-producer'
```
