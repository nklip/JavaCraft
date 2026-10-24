# Verification architecture

[ShardShop](../ARCHITECTURE.md) › **Verification** · [README](README.md) · [Runbook](RUNBOOK.md) · [Database guarantees](../database/ARCHITECTURE.md) · [Infrastructure lifecycle and delivery](../infra/ARCHITECTURE.md)

This document holds the eleven retained acceptance requirements of ShardShop and
the protocol for capacity measurement. `shardshop-testing` is test-only: it has no
main classes, it is never deployed, and it runs only in Maven builds.

- **Runs as:** Cucumber integration scenarios with `-Pintegration`, and opt-in
  kind suites that observe the deployed lab. The
  [infrastructure design](../infra/ARCHITECTURE.md#verification-gates-and-drills)
  describes the kind profiles and drills.
- **Status:** [PLAN](../PLAN.md) and dated [CHANGELOG](../CHANGELOG.md) evidence
  are authoritative for implementation and validation status.

## Acceptance scenarios

These are the retained eleven acceptance requirements, with stable scenario IDs.
They span completed and planned work. The [consistency and failure boundaries](../database/ARCHITECTURE.md#consistency-and-failure-boundaries)
explain the durability assumptions behind these scenarios.

| Scenario | Topic | Current coverage and remaining planned work |
|---:|---|---|
| [1](#scenario-1) | Seeding gate and product traffic | Covered by completed lifecycle steps |
| [2](#scenario-2) | Regional placement, routing, schemas and roles | Covered by completed routing steps |
| [3](#scenario-3) | Primary and replica reads | Primary/replica read behavior exists; the complete deployed strict-replica-read drill is planned in step 6.1 |
| [4](#scenario-4) | Confirmed order effects | Covered by completed saga steps |
| [5](#scenario-5) | Ledger rejection, compensation and replay | Covered by completed saga steps |
| [6](#scenario-6) | Crash recovery and CDC | Steps 4.4 and 5.5 qualify relay/crash recovery, independent HTTP writes and observability; promotion/failover cases remain step 6.2 |
| [7](#scenario-7) | Broker retries, dead letters and reconciliation | Step 4.3 verifies broker policies and bounded retry/parking behavior. Step 5.1 adds durable reconciliation and lost-result recovery; step 5.2 adds transport cleanup with CDC checkpoints and retained-decision replay. Step 5.3 adds the restricted manual reset and replay after budget exhaustion. Step 5.4 measures retry timing and verifies shard-outage reconciliation plus unbound/full DLQ pressure and recovery on both application publishers; see the [drill runbook](RUNBOOK.md#retry-and-dlq-capacity-drills) and [dated evidence](../CHANGELOG.md#step-54) |
| [8](#scenario-8) | Standby loss, promotion, restore and audit | Current kind database drills cover standby/quorum behavior. Complete strict-read, promotion, coordinated restore and audit requirements remain in steps 6.1–6.4 |
| [9](#scenario-9) | HTTP validation and transaction boundaries | Covered by completed validation steps |
| [10](#scenario-10) | ID generation and service-issued identity | ID generation/allocation and service-issued identity checks exist; step 6.6 requires the complete lifecycle acceptance run. Backup/restore preservation is planned in step 6.3 |
| [11](#scenario-11) | Concurrent stock reservation | Covered by completed reservation steps |

For scenarios 1, 2, 4, 5, 9 and 11, completed lifecycle, routing, saga, validation
and reservation steps provide coverage; future transport/profile changes must
preserve these requirements.

Acceptance scenarios for implementation:

1. <a id="scenario-1"></a>Verify the seeder Job succeeds before either load Deployment starts; failed or
   partially completed seeding must block startup. Keep workloads free of SQL
   credentials/tables. With up to 16 connections and the 30-second connection
   lifetime, observe positive request-counter deltas on both ready product pods
   within 120 seconds. Then replace one pod and repeat the check.
2. <a id="scenario-2"></a>Verify `shard-a=US`, `shard-b=EU`, and `shard-c=ASIA` in inventory, runtime
   routing and Kubernetes labels, with unchanged version-2 ID routes. Reject
   malformed regions, misaligned configuration, and reassignment of a published
   shard region; reject missing version hashes in either ConfigMap. Verify seller
   and buyer immutable regions: reject unsupported requested regions with HTTP
   400 and generate IDs whose routes match accepted home regions.
   Each seller's products and reservations stay on its home shard, and each
   buyer's orders on its home shard. Reject wrong-shard seller IDs or regions and
   foreign-region product creation at the database boundary. Exercise buyers in
   every region buying from each other region, with same-currency orders under
   the seller country policy, including a US buyer purchasing Japanese JPY products. Physical
   replication remains within each shard. Create schemas through `Database`
   resources and migrate the catalog and ordering streams without history
   collisions on all primaries. Assert the role matrix: `order_app` can select
   sellers and products, write stock reservations without `TRUNCATE`, and update
   only the `stock` column of products. No other catalog writes or DDL are allowed,
   except the profit-credit writes that step 4.7 adds.
   `product_app` cannot touch reservations or
   ordering tables; Flyway creates no custom functions or triggers, and no login
   role owns an object.
   Verify fresh V1 creates all regional constraints and comments, rejects
   misplaced sellers, and reaches identical migration state on standbys. A matching
   rerun must apply no migrations. After a partial migration, reject changed
   region/index/count parameters using the saved topology before first routing
   publication, including a run with no pending versioned migrations. Preserve
   the reservation after publication failure and reuse it on a matching rerun.
   Run the version-2 routing vectors in `shardshop-core/sharding`, including values above
   2^53 and signed-long boundary checks, and verify that both services route through
   it. Run the Maven `kind-topology` scenarios to compare the same golden fixtures
   with SQL hashing and the deployed seller and buyer placement constraints on
   every primary.
3. <a id="scenario-3"></a>Exercise heavy product reads, primary read-after-write behavior, and explicitly
   stale replica reads under controlled lag.
4. <a id="scenario-4"></a>Verify an accepted order reaches `CONFIRMED` with exactly one ledger row, one
   reservation per item, and stock reduced exactly once. Each item credits its
   margin once to its seller's balance in the order currency. Duplicate HTTP
   requests and duplicate messages create no additional records or credits.
5. <a id="scenario-5"></a>Use the order IDs that the allocation API returns, and EUR-only products
   from a valid EU seller under an explicitly scoped EUR-excluding ledger policy. Verify `202` becomes `CANCELLED`
   with no ledger entry and no seller profit, and that its reservations are
   released and stock restored. Change the ledger allowlist to include EUR, and
   clean transport inbox/outbox rows. Then a test harness publishes the original
   `RecordOrder` envelope again, which it saved before the cleanup. This is fault
   injection, not an operator tool. The permanent rejected decision must still
   win. Test conflicting payloads separately from identical retries.
6. <a id="scenario-6"></a>Inject crashes before/after database commit, broker confirmation, and consumer
   acknowledgement; verify replay completes the saga without duplicate effects.
   On each shard, verify a committed order/outbox insert reaches the ledger through
   WAL capture while a rollback emits nothing. Restart CDC before/after publication
   marking and offset flush; verify the same message identity and one ledger entry.
   Cover snapshot-to-stream handoff, ignored metadata updates/deletes, missing
   command bindings (mandatory return without offset advancement), and failover
   with synchronized logical slots. Slot/offset/WAL loss must stop capture for
   explicit recovery; ordinary reconnects must resume without losing a command.
   Step 5.5 uses test-only debugger barriers in disposable packaged JVMs followed
   by forced process death. SQL visibility and broker settlement checks establish
   each crash boundary before replay assertions run. The kind rollout uses bounded
   continuous HTTP traffic and checks unchanged saga ownership. Cached application
   metrics and native broker/database observations expose backlog, quarantine,
   checkpoint, queue and pool pressure; stale sampling is visible separately from
   a healthy zero. [Commands and limits](RUNBOOK.md#crash-recovery-and-observability).
7. <a id="scenario-7"></a>Measure same-queue rejection counts and redelivery timestamps on the pinned
   broker, assert its effective delayed-retry/delivery-limit behavior, and record
   total time to DLQ parking. Hold a shard unavailable beyond that window, for
   example with both standbys down so synchronous writes block, and verify DLQ
   plus reconciliation is the recovery path. In separate capacity drills, temporarily unbind a DLQ
   and then test a full DLQ: verify retained source messages reach its limits,
   negative publisher confirms leave outboxes unpublished, and broker-wide alarms
   remain clear. Keep the broker/source running; a single-broker shutdown cannot
   isolate the DLQ. Restore the binding or remove the parked messages, and verify
   progress.
   Drop a result and verify replay regenerates the immutable outcome. Delete each
   confirmed-published and CDC-checkpointed replay outbox row between reconciliation
   attempts, restart the coordinator, and use an injected clock to prove no sixth
   automatic attempt is enqueued. Recover through a manual reconciliation reset
   without duplicate ledger effects or changes to terminal decisions. Verify conflicting command/result
   quarantine commits precede acknowledgements and survive transport cleanup.
8. <a id="scenario-8"></a>Remove one standby: replica-profile reads continue through the other, writes
   continue, and CDC pauses until it returns. Remove both: replica-profile reads
   must return the documented `503` within the four-second server budget and
   observed before the reader's five-second deadline, without primary fallback,
   while unaffected shards continue. Restore the standbys and verify reads resume
   through `-ro`. Test a hung standby query as well as absent endpoints; bound the
   complete database phase to three seconds and assert an HTTP response rather
   than a client timeout. Test primary promotion separately, including any
   interval with no `-ro` endpoints.
   In the optional asynchronous-loss drill, verify that lost acknowledged orders leave no
   ledger records and that the audit finds none. After restoring a shard from an
   older backup, verify both late results and the operator audit detect orphan
   ledger records and block ID reuse; restoring the original order must allow
   reconciliation. Under the default synchronous profile, verify that writes
   continue with one standby unavailable and block when both are, that promotion
   preserves acknowledged saga commits, and that losing the primary together with
   one standby triggers no automatic promotion when the survivor cannot be
   confirmed current.
9. <a id="scenario-9"></a>Assert the HTTP validation table: malformed input, unknown buyers (400 for an
   order, 422 for an allocation), missing products, mixed or
   mismatched currencies, unavailable catalog shards, ID conflicts, and unknown
   commit outcomes. Validation failures create no saga/outbox rows; identical
   retries of an existing order still work with its product shard unavailable.
   Combine failures and reverse lookup completion order: mixed currencies beat
   order/product mismatch, and catalog unavailability beats a missing product.
   Hang catalog IO and prove no order-shard transaction, advisory lock, or borrowed
   order connection spans it. Race that lookup with order creation or quarantine
   and verify the final locked re-check takes precedence over its catalog result.
10. <a id="scenario-10"></a>Verify product/order library generation under concurrent callers, sequence
    exhaustion, clock failure, and epoch/timestamp boundaries using a controlled `TimeSource`.
    Concurrent JVM starts, rolling replacements, and container restarts must obtain
    distinct generator allocations. Test lost CAS responses, stale/missing registry,
    and allocation exhaustion without emitting duplicates. Verify registry retention
    during backup/restore. Rerun workload definitions and creation requests with
    the same keys/payloads; durable service state must return identical entity
    IDs and home regions. Verify conflicting key reuse, concurrent creation and
    unknown commit recovery, plus read-only lookup of persisted catalog IDs.
    Workloads
    obtain IDs only from APIs, have no generator dependency or allocator access,
    and preserve returned IDs on retries. Lost allocation responses, concurrent
    requests, and producer restarts return the same order ID; unissued IDs and
    allocations for another buyer are rejected. Ledger only uses order-reserved
    result IDs, including duplicate/reconciliation replay after outbox cleanup.
    Round-trip large IDs exactly through HTTP, messages, JDBC, and the routing
    vectors;
    reject numeric JSON IDs, noncanonical text including LF/CRLF/TAB, and
    out-of-range values before conversion or hashing, with HTTP 400 for invalid IDs.
11. <a id="scenario-11"></a>Race concurrent orders for the same product and verify stock never goes below
    zero and never oversells. An order that exceeds the remaining stock becomes
    `CANCELLED` with reason `OUT_OF_STOCK` and never reaches the ledger, and its
    partial reservations are released. Retried reservations and releases, and a
    catalog shard outage during reservation, change stock at most once per order
    and product. Inject failures between the Java reservation and stock writes to
    prove both roll back together. The Java API exposes no reservation quantity
    changes, reactivation or deletion, and database `TRUNCATE` is refused. After
    every supported reserve/release run, each product's `stock` equals its
    `initial_stock` minus its active reserved quantities.

## Test levels

| Level | Use it for |
|---|---|
| Unit and contract tests | Routing, validation, duplicate handling, stored outcome replay, and injected-clock reconciliation schedules |
| PostgreSQL and RabbitMQ integration tests | Transaction boundaries, privileges, CDC capture and checkpoint recovery, and delivery policies |
| Bounded Kubernetes drills | Seeding, traffic, and failover |

The [verification runbook](RUNBOOK.md) and the [build runbook](../RUNBOOK.md#dependency-and-test-validation)
contain the module-scoped build and drill commands.

## Capacity measurement

Report capacity as a qualified observation of a specific deployment. A configured
rate cap, an allocation-only HTTP burst and an accepted order are different
quantities. The root [performance summary](../ARCHITECTURE.md#4-performance)
contains current operating points; dated measurements belong in
[CHANGELOG](../CHANGELOG.md#step-415), and executable commands belong in the
[capacity runbook](RUNBOOK.md#capacity-measurement).

### Full-order writes

Reuse the final step 4.14 measurements for the unchanged deployment: two order
HTTP replicas, one saga process, four producer submission workers, two observers
and a 100-order outstanding bound. Warm up for 20 seconds, then sample counters
and aggregate database backlogs about every ten seconds. Observe a target for at
least 90 seconds; use counter deltas for **new** accepted, confirmed, cancelled
and terminal rates, excluding recovered historical orders. Record concurrent
product reads, finite stock and any temporary producers separately.

A passing operating point must:

- keep the final warmed write p95 below 100 ms;
- avoid write errors or a deadline failure;
- keep pending orders, unapplied profit intents and unpublished command outboxes
  bounded without persistent growth.

Track stock reservations and terminal/cancellation progress as well as
confirmation: stock exhaustion skips ledger work, and confirmation may precede
seller-profit posting. A full outstanding window/backpressure or rising saga
latency rejects a higher target even if some writes are still fast. The existing
90–130-second holds establish a bounded operating point, not an endurance result.

Producer latency uses the last 1,024 new orders; per-endpoint HTTP metrics use the
last 4,096 requests **per JVM** and can include earlier phases or replay. Record
which window is reported; do not present a final rolling p95 as a percentile of
an entire observation interval. The final step 4.14 backlog ranges and stock
cancellations remain in its historical evidence.

The standard producer rotates buyer regions equally, so dividing its accepted
rate by three gives a **derived buyer-home rate**, not measured per-shard SQL
load. Allocation and submission are two HTTP writes per new order. Reservation,
CDC bookkeeping, saga updates, ledger operations and seller-profit posting add
writes elsewhere; no disk IOPS or SQL-write capacity is inferred from order rate.

### Product reads

Use the deployed primary-read profile, two product replicas and the existing
reader with 16 workers and at most 16 HTTP connections. Pause the order producer
and bound the wait for in-flight submissions and non-terminal backlog to drain;
leave the interactive seeder idle. This isolates read capacity from new orders.
Record remaining background activity and the actual workload settings. A separate
mixed-load test is needed before combining the peak read and write operating
points.

For each target, allow at least 30 seconds of warmup and collect three complete,
non-overlapping 30-second reader reports. Because reports are not aligned with a
rate update, ignore reports ending less than 60 seconds after the update. Bound
each phase to 150 seconds. All three windows must:

- report only `OK` outcomes, with no timeout, HTTP error or invalid response;
- deliver at least 95% of the requested rate;
- keep client-observed read p95 strictly below 100 ms.

Start at 500 reads/s, then try 1,000, 2,000, 4,000, 8,000 and the configured
10,000/s upper bound. Stop at the first failing phase; when the previous passing
point is at least 500/s lower, test the midpoint once. If the first phase fails,
verify the default 200/s. Preserve all failed windows rather than discarding them.
This bounded search qualifies an operating point. When the generator undersupplies
the target without read errors or high latency, it does **not** establish a
product/database saturation limit. More independent load generation and host
headroom would be needed to establish that maximum.

Use the reader's interval counts, actual reported seconds and region counters;
its latency includes connection acquisition and retries, rounded up to milliseconds.
Uniform selection among the 201 catalog products implies unequal regional load:
50 US, 100 EU and 51 ASIA products. Compute each observed shard rate from that
region's requests divided by the same observation duration, not total/three.
Pool statistics are snapshots at report time, not peak occupancy.

### Recording and restoration

Record host/Docker resources, current pod inventory, rate/concurrency, warmup and
observation durations, errors, latency, region counts and limitations. Restore
saved rates and pause states even on failure, stop temporary tunnels/load generators,
and return the lab to its prior running or stopped state. No capacity measurement
changes runtime defaults or migrates or resets data.
