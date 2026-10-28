# ShardShop architecture

[README](README.md) · [Runbook](RUNBOOK.md) · [Implementation plan](PLAN.md) · [Version policy](VERSIONS.md) · [Dated evidence](CHANGELOG.md)

ShardShop is a local system-design lab. It runs a regional product catalog,
sharded orders and an asynchronous ledger on one kind cluster. This overview
describes the implemented architecture on **2026-10-26**. Remaining work in
milestones 6–7 covers HTTP/2, operation and cloud features; the
[detailed documents](#detailed-design-documents) identify the planned behavior.

- **Shape:** six Quarkus applications, three regional PostgreSQL shards, one
  ledger database and one RabbitMQ broker.
- **Consistency:** each write is a local transaction. The order saga connects the
  shards and the ledger through CDC, RabbitMQ and compensation. There is no
  distributed transaction.
- **Last qualified capacity on this lab:** 29.45 complete orders/s and at least 963 product
  reads/s, measured separately ([section 4](#4-performance)).

## 1. System architecture

```mermaid
flowchart LR
    subgraph Clients["Workload applications · HTTP only"]
        Seed["Interactive seeder · 1 pod"]
        Read["Product reader · 1 pod"]
        Write["Order producer · 1 pod"]
    end
    subgraph Services["Application services"]
        Product["Product API · 2 pods"]
        Order["Order HTTP API · 2 pods"]
        Saga["Order saga · 1 pod"]
        Ledger["Ledger · 1 pod"]
    end
    Shards[("Regional PostgreSQL · 9 pods<br/>US / EU / ASIA<br/>Each: primary + 2 standbys")]
    MQ["RabbitMQ · 1 pod"]
    LDB[("Ledger PostgreSQL · 1 pod")]
    Seed -->|"Create sellers and products"| Product
    Read -->|"Read products"| Product
    Write -->|"Allocate, submit, observe orders"| Order
    Product <-->|"Catalog"| Shards
    Order <-->|"Buyers, orders and saga state"| Shards
    Shards -->|"Committed outbox via CDC"| Saga
    Saga -->|"Stock, status and seller profit"| Shards
    Saga -->|"Order commands"| MQ
    MQ -->|"Commands"| Ledger
    Ledger <-->|"Permanent decisions and results"| LDB
    Ledger -->|"Results"| MQ
    MQ -->|"Results"| Saga
```

| Component | Responsibility |
|---|---|
| Product API | Creates sellers and products, serves catalog reads and the `/products/` page, and applies the seller currency policy. |
| Order HTTP API | Creates buyers, issues order IDs, accepts orders atomically and serves the `/orders/` page. |
| Order saga | Reserves stock, relays committed commands through CDC, applies ledger results, credits seller profit, replays overdue ledger commands within a durable retry budget and removes expired transport after CDC checkpointing. |
| Ledger | Keeps one permanent decision for each order, publishes its result and removes expired transport. |
| Workloads | Seed the catalog, read products and produce orders through the public APIs only. |
| PostgreSQL and RabbitMQ | Regional shards, the separate ledger store and the message transport. |

Kubernetes Services spread HTTP connections across the product and order API pods.
They are network resources and add no pods. Workloads have no database access. A
one-shot bootstrap seeder Job fills the catalog; reader and producer traffic waits
until the complete catalog resolves. The interactive seeder stays available.

### Main flows

- **Read:** the product API derives the shard from the seller ID and reads that
  regional primary. An optional strict replica profile reads standbys, accepts
  stale data and fails when no standby is available.
- **Write:** the order API issues an order ID, validates the buyer and products,
  then commits the order and its initial saga state on the buyer's shard. It
  answers `202 Accepted`; confirmation comes later.
- **Saga:** a separate order process reserves stock on the seller shards,
  publishes the committed command through CDC and RabbitMQ, and applies the ledger
  decision. Confirmation creates seller-profit work; rejection releases the stock.
  Retries use durable identities, thus stock, ledger and profit effects occur once.

The saga and ledger processes clean up expired inbox and published outbox rows
after a configurable retention window (24 hours by default). Permanent decisions,
replay identities and business state remain available for recovery. Order outbox
cleanup also waits for a durable CDC checkpoint. These workers add no pods.
An operator can reset an exhausted retry budget for a selected pending saga;
terminal decisions remain unchanged.

Application metrics report pending work, exhausted retries, quarantine, database
pool pressure and CDC recovery state. RabbitMQ and PostgreSQL expose their queue
and replication pressure through native monitoring. These endpoints add no pods;
the [monitoring guide](infra/README.md#monitoring) owns their operational details.

```mermaid
sequenceDiagram
    autonumber
    participant P as Order producer
    participant H as Order HTTP API
    participant B as Buyer shard
    participant S as Order saga
    participant C as Seller shards
    participant Q as RabbitMQ
    participant L as Ledger
    P->>H: Allocate an order ID, then PUT the order
    H->>B: Commit the order and a PENDING_STOCK saga
    H-->>P: 202 Accepted
    S->>C: Reserve stock for each item
    S->>B: PENDING_LEDGER and a RecordOrder outbox row
    B-->>S: CDC delivers the committed outbox insert
    S->>Q: Publish RecordOrder
    Q->>L: Deliver the command
    L->>L: Save the permanent decision and its result
    L->>Q: Publish LedgerRecorded or LedgerRejected
    Q->>S: Deliver the result
    S->>B: CONFIRMED, or CANCELLED
    S->>C: Credit seller profit, or release the stock
    P->>H: GET the status until it is terminal
```

## 2. Runtime topology

The kind lab runs **36 steady pods** in all namespaces. The `shardshop` namespace
contains 20 of them.

| Group | Pods | Contents |
|---|---:|---|
| Application services | 6 | Product API 2, order HTTP API 2, order saga 1, ledger 1 |
| Workloads | 3 | Product reader, order producer and interactive seeder |
| Regional PostgreSQL | 9 | Three shards, each with one primary and two standbys |
| Ledger PostgreSQL | 1 | One primary without a standby |
| RabbitMQ | 1 | One broker |
| **`shardshop` namespace** | **20** | Applications, databases and broker |
| CloudNativePG operator | 1 | Database operator |
| Kubernetes system | 14 | kind control plane, DNS and networking |
| Storage | 1 | Local-path provisioner |
| **All namespaces** | **36** | |

The counts exclude transient Jobs (bootstrap seeder, Flyway migrations, DNS probes
and drills), retained completed Job pods and rollout surge pods. The four kind
node containers (one control plane and three workers) are hosts for these pods,
not more pods. The counts describe this lab, not a production size. The broker and
the ledger database have one pod each, thus the lab gives them no high availability.

The [infrastructure design](infra/ARCHITECTURE.md#runtime-topology) owns the process
roles and the counting scope. Its [README](infra/README.md#pod-inventory) shows
how to count the pods of a running lab.

## 3. Sharding

| Region | Shard | Owns |
|---|---|---|
| US | `shard-a` | US sellers and catalog, US buyers and orders |
| EU | `shard-b` | EU sellers and catalog, EU buyers and orders |
| ASIA | `shard-c` | ASIA sellers and catalog, ASIA buyers and orders |

**Routing.** Product and order route directly with a shared library; there is no
router service. Both hash a canonical seller or buyer ID into the fixed, ordered
shard inventory:

```text
shard(id) = inventory[unsignedBigEndian(SHA-256(UTF-8(id))) mod inventory.size]
```

At creation, the service generates an ID that routes to the requested home region.
The home region never changes. The Snowflake generator identity is independent of
database placement. A change of shard membership or order needs a data migration;
the retained lab refuses it.

**Ownership.** Each record lives on the home shard of its seller or its buyer:

| Data | Home | Key in each API call |
|---|---|---|
| Sellers, products, stock reservations, seller profit | Seller's shard | `sellerId` |
| Buyers, orders, items, saga state, order outbox and inbox | Buyer's shard | `buyerId` |
| Ledger decisions and entries | Separate ledger database | Not sharded |

A product ID or an order ID alone is not a routing key. API requests carry the
seller ID or the buyer ID.

**Cross-shard orders.** Buyers can buy from sellers in all regions. The order stays
on the buyer's shard, and stock and profit work runs on each seller's shard. Each
local transaction is atomic; the saga and its compensation connect them.

```mermaid
flowchart LR
    subgraph Buyer["shard-b · EU · buyer home"]
        O[("Order, items, saga state, outbox")]
    end
    subgraph Seller["shard-a · US · seller home"]
        S[("Product, stock reservation, seller profit")]
    end
    L[("Ledger database · not sharded")]
    O -->|"Saga: reserve or release stock, credit profit"| S
    O -->|"CDC, RabbitMQ, ledger consumer"| L
```

**Replication.** Each shard is its own group of one primary and two standbys.
Writes and default reads go to the primary. The synchronous profile waits for a
durable acknowledgement from one standby: writes continue with one standby absent
and block while both are absent. Replica reads can be stale. Replication copies
data inside a shard; it does not distribute rows between regions.

See [routing](shardshop-core/sharding/ARCHITECTURE.md),
[ID generation](shardshop-core/idgen/ARCHITECTURE.md) and
[database consistency](database/ARCHITECTURE.md#consistency-and-failure-boundaries)
for the contracts, [qualified synchronous failover](CHANGELOG.md#step-62),
and the coordinated backup/isolated-restore baseline plus older-backup audit/repair.
Steps 6.3 and 6.4 passed on 2026-10-26; see [6.3 evidence](CHANGELOG.md#step-63)
and [6.4 evidence](CHANGELOG.md#step-64).

## 4. Performance

These figures are **local-lab evidence, not a hardware-independent maximum**. One
host ran the complete lab: 12 Docker CPUs, 12.65 GiB Docker memory and 16 GB
physical memory.

**Latest combined-worker load test (2026-10-28):** the fixed 10/20/30 sequence
passed with paging diagnostic only. Accepted rates were **10.006 / 19.949 /
29.354 orders/s**, with final rolling write p95 **38.466 / 62.206 / 85.702 ms**
and saga p95 **1.533 / 1.788 / 2.228 s**. Concurrent reads held near 200/s.
Duration, sample and delivery gates passed; there were no new errors,
cancellations or recoveries, no observed backpressure, no stock exhaustion,
and bounded backlogs drained. These are local operating points, not a causal
serial/parallel comparison or a new hardware-independent maximum. Controls
and retained state were restored; see [load-test evidence](CHANGELOG.md#combined-worker-concurrency-paging-diagnostic-load-test--2026-10-28).

**Earlier combined-worker diagnostics (2026-10-28):** with three stock lanes and
three profit lanes, the fresh lab hit the 100-order backpressure bound at target
10/s after 50.040870s: 212 new accepted writes (~4.24/s), backlog 40 → 99. The
20/30 phases were not attempted. Reads delivered approximately 110.81/s against
target 200/s; host swap-ins and swap-outs each exceeded 12 GiB in 42.366s. This
short run does not qualify throughput/latency or establish a causal change from
worker concurrency. Check host headroom before further tuning; see
[measurement and cleanup evidence](CHANGELOG.md#combined-worker-concurrency-kind-measurement--2026-10-28).
A subsequent retry after closing apps delivered **199.735 reads/s**, with no
new errors and stable pods. An extra conservative paging precondition stopped
that retry before writes (1,096.16 MiB swap traffic in the readiness minute,
with improving later intervals). It adds no write-capacity or application SLO
finding; see [retry evidence](CHANGELOG.md#combined-worker-concurrency-retry-after-closing-apps--2026-10-28).

**Earlier duration/sample-eligible write measurement (2026-10-25, step 6.0):** target 10 orders/s produced
**1,177 new writes in 120.058077 seconds (~9.8/s)**, with reads configured at
200/s. Final rolling write **p95 was 133.783 ms** (mean 38.337 ms), exceeding the
**<100 ms** requirement; saga p95 was 2.031 s. There were no new errors,
cancellations or historical recoveries, no backpressure, and the producer backlog
drained. The observation duration and post-warmup sample requirements passed.
The later 30/s attempt reached the 100-order outstanding limit with backpressure
at the first post-warmup sample and stopped early. It observed 987 new orders in
40.044426s (~24.65/s), with no new errors, cancellations or recoveries. This short
capture does not qualify sustained capacity or establish a new maximum.

The earlier October 25 1,000/s read check is **unqualified**. With writes paused and
backlogs drained, its first eligible 30s window delivered **948.2/s** (28,446
requests), all OK, p95 **10 ms**, below the unchanged >=950/s minimum. A second
window logged before pause delivered **955.8/s** (28,676 requests), all OK,
p95 **9 ms**. It does not waive the failed first window or missing third passing
window. This is not a server-capacity limit.

At the user's earlier explicit request, capacity qualification was deferred from 6.0:
[step 6.5](PLAN.md#milestone-6-read-profiles-failover-restore-and-operation) owns
write p95 <100 ms at 10/s; 6.6 owns sustained 30/s writes with reads at 200/s,
controlled stock, no errors/backpressure, bounded backlogs and write p95 <100 ms;
6.7 owns three eligible 1,000/s read windows each >=950/s, all OK and p95 <100 ms.
6.0 is **Done under the explicit reduced local-maintenance scope**: all nine
final kind-gate cases passed with no failures/errors/skips, and the original
stopped retained lab was restored. Steps **6.5 and 6.6 are Done**, qualified by
the October 28 load sequence above. **6.7 is also Done:** the October 28
primary read qualification delivered **994.565 / 991.000 / 999.769 reads/s**
in the first three eligible 30-second windows, all OK with p95 **3 / 3 / 2 ms**.
The aggregate was **995.111 reads/s** over 89.999985248s, with writes paused
and drained. Two product pods, 16 reader workers and at most 16 HTTP connections
were unchanged. Opt-in diagnostics found mean connection acquisition 0.0078 ms,
zero sampled pool waiters and zero additional product/reader CPU throttling;
host paging remained diagnostic. Controls and retained state were restored.
This qualifies a local operating point, not maximum capacity or simultaneous
1,000 reads/s and 30 orders/s. HTTP/2 remains planned in 6.8. See
[read diagnostic evidence](CHANGELOG.md#step-67--primary-read-qualification-and-diagnostics--2026-10-28).
The short earlier 30/s capture's rolling 154.613 ms p95 is diagnostic
only; the earlier valid 10/s latency was 133.783 ms, superseded by the
38.466 ms local result above. Write latency covers
allocation through PUT acceptance; saga completion is separate. See
[maintenance evidence](CHANGELOG.md#step-60) for failed checks and restoration.

The following are **historical step 4.14–4.15 measurements**, not qualification
of the current maintenance selections.

| Operation | Highest verified sustained rate | Latency | Per shard |
|---|---|---|---|
| Complete order | **29.45 new orders/s** at target 30/s: 28.11 confirmed/s and 1.56 cancelled/s, with product reads at 200/s at the same time | Final rolling p95: write 93.73 ms, saga 2.16 s | Approximately 9.82 new orders/s for each buyer shard, **derived** from the equal regional schedule |
| Product read | **963.16 reads/s** at target 1,000/s over 90 seconds, all `OK`, with the producer paused | Window p95 6–10 ms | **Measured:** US 239.74/s, EU 479.66/s, ASIA 243.76/s |

The order rate held for 90 seconds with bounded order, profit and outbox backlogs;
the earlier 10/s target held for 130 seconds. These short holds give an operating
point, not a long-duration production capacity. The read rates are unequal
between shards because the catalog has 50 US, 100 EU and 51 ASIA products.

Different kinds of figures answer different questions:

| Figure | Value | Meaning |
|---|---|---|
| Configured cap | Producer 30 new orders/s, reader 10,000 reads/s | Control limits, not measured capacity |
| Short burst | 829 allocation requests/s on one home shard, for less than one second | Allocation only, not the complete order workflow |
| Highest verified sustained rate | 29.45 orders/s and 963.16 reads/s, measured separately | Passes the latency, error and backlog criteria |
| Saturation attempt | Target 40 orders/s (the producer at 30/s and a temporary producer at 10/s) gave 22.68 new orders/s, and the saga latency increased to approximately 15 s | Backpressure filled the combined 200-order bound; host contention and finite stock limit this result |
| Undersupplied read targets | 1,434/s at target 1,500 and 1,858/s at target 2,000, without errors, but below 95% of the target in some windows | The reader and the host did not supply the target; the read maximum is not established |

- **Write requests.** Each order needs two write API requests: allocation and
  submission. Thus 29.45 orders/s is approximately 58.91 write requests/s, or
  19.64/s for each buyer shard. These are derived HTTP rates, not SQL writes or
  storage IOPS: stock, saga, ledger and profit work add database writes on buyer
  and seller shards.
- **Historical bottleneck.** These measurements used one serial stock worker in
  the saga process. The source now supports bounded concurrent buyer-shard scans;
  this does not requalify the complete-order pipeline. Some measured orders were
  cancelled because finite stock ran out, thus the confirmed rate matters.
- **Stock-stage comparison.** A separate disposable PostgreSQL comparison on
  2026-10-28 observed 40.60 → 57.99 orders/s with one versus three stock lanes
  (+42.8% throughput, 30.0% shorter drain). This excludes CDC, ledger, profit and
  replication; see the [evidence and limits](CHANGELOG.md#stock-worker-concurrency--2026-10-28).
- **Profit-stage comparison.** A separate prepared-intent comparison on 2026-10-28
  observed 182.29 → 299.16 intents/s with one versus three profit lanes (+64.1%
  throughput, 39.1% shorter drain). It uses independent sellers and the lab's
  100 ms/32 polling profile; see the
  [evidence and limits](CHANGELOG.md#profit-worker-concurrency--2026-10-28).
  Neither stage result requalifies full-saga capacity, and their gains cannot be added.
- **Lower bound.** 963 reads/s is the highest *passing* read target, a verified
  lower bound under the stated criteria. It is not the highest observed rate.
- **No sum.** Peak reads and peak writes were measured separately. Do not add
  them as one simultaneous capacity.

The [measurement criteria](shardshop-testing/ARCHITECTURE.md#capacity-measurement),
the [read evidence](CHANGELOG.md#step-415) and the [order evidence](CHANGELOG.md#step-414)
record the hardware, concurrency, traffic mix, latency, errors and limits. The
runtime defaults stay at 0.5 orders/s and 200 product reads/s.

## Detailed design documents

Each topic has one owning document. Runbooks keep the commands, and the
[changelog](CHANGELOG.md) keeps the dated evidence.

| Area | Document | Owns |
|---|---|---|
| Services | [Product](shardshop-product/ARCHITECTURE.md) | Catalog API, creation keys, currency policy, read profiles and stock storage |
| | [Order](shardshop-order/ARCHITECTURE.md) | Order acceptance, saga, stock reservation, CDC relay, reconciliation and restore requirements |
| | [Ledger](shardshop-ledger/ARCHITECTURE.md) | Permanent decisions, replay identities and result publication |
| Libraries | [Core](shardshop-core/ARCHITECTURE.md) | Module boundaries, dependency rules and wire contracts |
| | [Common](shardshop-core/common/ARCHITECTURE.md) | Shared validation, JSON, JDBC and RabbitMQ utilities |
| | [ID generation](shardshop-core/idgen/ARCHITECTURE.md) | Snowflake IDs and generator allocation |
| | [Shard routing](shardshop-core/sharding/ARCHITECTURE.md) | Routing rule, shard topology and data placement |
| | [Workload control](shardshop-core/workload-control/ARCHITECTURE.md) | Live rate, pause and status API of the workloads |
| Workloads | [Workloads](shardshop-workload/ARCHITECTURE.md) | Workload lifecycle and catalog gate |
| | [Datasets](shardshop-workload/shardshop-datasets/ARCHITECTURE.md) | ID-free seller, product and buyer definitions |
| | [Product seeder](shardshop-workload/shardshop-product-seeder/ARCHITECTURE.md) | Finite, idempotent catalog seeding |
| | [Product reader](shardshop-workload/shardshop-product-reader/ARCHITECTURE.md) | Read load and HTTP connection policy |
| | [Order producer](shardshop-workload/shardshop-order-producer/ARCHITECTURE.md) | Deterministic regional order stream |
| Platform | [Database](database/ARCHITECTURE.md) | Schemas, roles, migrations, replication and retention |
| | [Infrastructure](infra/ARCHITECTURE.md) | Lab lifecycle, deployment, pod topology and RabbitMQ delivery |
| Verification | [Testing](shardshop-testing/ARCHITECTURE.md) | Acceptance scenarios 1–11 and capacity measurement |
