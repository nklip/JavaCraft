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
| [3](#scenario-3) | Primary and replica reads | Primary/replica read behavior and the opt-in deployed strict-replica-read drill passed step 6.1 qualification |
| [4](#scenario-4) | Confirmed order effects | Covered by completed saga steps |
| [5](#scenario-5) | Ledger rejection, compensation and replay | Covered by completed saga steps |
| [6](#scenario-6) | Crash recovery and CDC | Steps 4.4 and 5.5 qualify relay/crash recovery, independent HTTP writes and observability; step 6.2 qualified synchronous promotion/failover on 2026-10-26 |
| [7](#scenario-7) | Broker retries, dead letters and reconciliation | Step 4.3 verifies broker policies and bounded retry/parking behavior. Step 5.1 adds durable reconciliation and lost-result recovery; step 5.2 adds transport cleanup with CDC checkpoints and retained-decision replay. Step 5.3 adds the restricted manual reset and replay after budget exhaustion. Step 5.4 measures retry timing and verifies shard-outage reconciliation plus unbound/full DLQ pressure and recovery on both application publishers; see the [drill runbook](RUNBOOK.md#retry-and-dlq-capacity-drills) and [dated evidence](../CHANGELOG.md#step-54) |
| [8](#scenario-8) | Standby loss, promotion, restore and audit | Kind database drills cover standby/quorum behavior; the strict-read drill passed step 6.1 qualification. Steps 6.2–6.4 qualified synchronous promotion, coordinated backup/isolated restore and older-backup audit/repair on 2026-10-26 |
| [9](#scenario-9) | HTTP validation and transaction boundaries | Covered by completed validation steps |
| [10](#scenario-10) | ID generation and service-issued identity | ID generation/allocation and service-issued identity checks exist; step 6.6 requires the complete lifecycle acceptance run. Backup/restore preservation passed step 6.3 qualification |
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

## Coordinated backup and isolated restore

**Step 6.3 is Done**, qualified on 2026-10-26. Coordinated backup, isolated
restore with explicit CDC recovery, final source checks, independent latest
allocator export and restoration audit passed. The initial source suite passed
seven of eight cases; its failed reader gate is preserved, and the subsequent
two-case workload rerun passed unchanged thresholds. See
[evidence](../CHANGELOG.md#step-63). Separate
opt-in `kind-backup` and `kind-restore` tags implement the coordinated baseline;
neither belongs to `kind-drill` or the default suite. The
[runbook](RUNBOOK.md#coordinated-backup-and-isolated-restore) defines explicit source,
external artifact, independent allocator-history and fresh target parameters.

The standalone opt-in `kind-backup-allocator` export refreshes independent latest
allocator history after ready source JVM restarts, under the host lifecycle lock
and deployment/saga readiness checks. Run it after final source gates before
stopping; any subsequent generator restart requires another refresh before
ID-producing restore use. Target qualification keeps every ID-producing path
disabled.

Backup holds both host lifecycle and Kubernetes drill locks. It saves/pauses
producer, reader and interactive-seeder controls, scales writers down, and lets
saga/ledger drain before stopping them. Every application-vhost queue, including
DLQs, must be empty: retained dead letters refuse this baseline rather than being
silently omitted. Four full logical dumps retain all three shards and ledger,
actual broker definitions, roles, database ACLs, routing/schema/identity metadata
and complete table fingerprints. Source replicas, controls, topology, identities
and invariants must recover before the immutable backup is sealed.

Restore validates inventory/schema/Snowflake compatibility, checksums and the
separate latest allocator lineage/high-water mark before creating a fresh owned
loopback-only TLS PostgreSQL container with four databases. It verifies complete
row fingerprints, ownership/ACLs, saga relationships and global consistency.
Restored CDC first must refuse saved offsets without logical slots. Explicit
target-only reset/resnapshot then replays original command/result identities and
proves subsequent streaming with a fresh broker and ledger worker, without any
ID-producing traffic. The source allocator is never rewound and the owned target
is removed after verification. Artifacts contain full seed data and broker
credential hashes; private permissions and exclusion from commits are required.
Older-backup repair/audit passed step 6.4; PITR and production cutover are outside
this baseline qualification.

## Older-backup audit and selected repair

**Step 6.4 is Done; 60 focused checks and live recovery qualification pass.** Four operator/drill tags export original records, audit, repair one selected order,
and run the causal older-dump drill. A fifth focused tag, `kind-recovery-schema`,
checks native repeated schema restore, namespace grants, publication membership
and all database fingerprints without applications or a broker. They are excluded from `kind-drill`. Verified 6.3
bundles and independent latest allocator history create only fresh owned isolated
targets; no source connection or source write is made. Audit/repair replace the
older ledger database with a same-or-later verified ledger bundle. Targets remain
frozen with application traffic absent except the explicit isolated replay probes.

The drill selects three acknowledged witnesses, constructs an older ordering
dump, restores the later state to establish their existence, then actually applies
the older dump losing two orders and introduces a third fingerprint mismatch.
An exported permanent-ledger audit must quarantine all three before late delivery
and report orphan reservations. Late acknowledgement and HTTP `409` preserve
blocking incidents; conflicting or absent original records refuse repair. One
verified original order/saga is recovered and replayed with unchanged IDs, while
whole-catalog fingerprints and ledger entry cardinality stay unchanged. Missing
history/costs, conflicting target state or unproved catalog effects remain
unresolved. Authentic pending outcomes apply only when existing catalog effects
provably agree.

Audit/repair retain reports, quarantine and repaired rows in full database review
dumps before target removal. Bundles carry `purpose: recovery-review-only` and
`trafficAuthorized: false`; they authorize no production cutover. The optional
physical asynchronous-loss drill is outside current qualification, and the default
synchronous profile is unchanged. See the
[operational workflow](RUNBOOK.md#older-backup-audit-and-selected-repair).

## Synchronous failover drill

The opt-in `kind-failover-drill`, also selected by `kind-drill`, implements the
promotion portions of scenarios 6 and 8. **Step 6.2 passed retained-lab
qualification on 2026-10-26**, including automatic recovery and eight final gates.
See the [evidence](../CHANGELOG.md#step-62). The [runbook](RUNBOOK.md#synchronous-failover-drill)
describes invocation and recovery. The operator contract is pinned to the existing
CNPG 1.30.1 image under the [October 26 decision](../VERSIONS.md#step-62-existing-version-decision--2026-10-26).

The shared guard saves, pauses and drains producer, reader and interactive-seeder
controls. Real HTTP orders establish a confirmed anchor and original pending
commands. Each standby's absence must pause CDC for ten seconds while quorum
writes still commit. Planned switchover and primary failure must preserve the
acknowledged orders, immutable snapshots and command/result identities, then
complete their single stock, ledger and profit effects. Synchronized logical
slots must cover the saved resume position; permanent reader history and offset
partition identity must survive, and durable streaming offsets must advance.

After a promotion, guarded reconciliation may retire only an obsolete logical
slot on a known former primary. It requires stable authoritative-primary roles,
the original database lineage, matching slot name/database/plugin and confirmed
LSNs bounded by the captured and authoritative positions. Unknown, active,
temporary, invalidated or already synchronized copies are not retirement targets.
The conditional SQL rechecks the standby role and observed slot properties before
retirement; native slot synchronization must recreate valid synchronized copies
before healthy-topology checks and switchback. An invocation-owned failover slot,
publication and probe schema exercise this branch, with exact cleanup before the
final business-slot switchback. If its recreated copy is missing, temporary or
lacks a confirmed LSN, only this owned probe is primed while the required source
LSN remains fixed. The business slot is never consumed by probe priming. This
mechanism passed the complete retained-lab drill; earlier failed/incomplete
evidence remains preserved in the dated [record](../CHANGELOG.md#step-62).

Owned-probe priming first requires both physical slots active/streaming and an
exact `synchronized_standby_slots` readiness barrier. Its verbose decode permits
at most three attempts for only the exact SQLSTATE `55000` warning about a missing
`active_pid` on an expected physical slot, with exit zero and numeric count.
Final success must be warning-free; other or mixed warnings and nonzero exits
are terminal. The existing convergence deadline is shared, reserving thirty
seconds per read/mutation and bounding decode explicitly to twenty seconds.
These checks never consume the business slot or weaken required slot properties.

Fence updates allow at most three conditional attempts within forty seconds.
Each reread verifies the original cluster UID and owned fence state. The patch
tests that UID and the exact observed fence value rather than the global resource
version. An absent fence key is protected by the observed annotations map; a
missing annotations map uses a protected metadata fallback. Unrelated annotations
are preserved. An observed
target state accepts a lost successful response; retry requires an observed
resource-version change. Unchanged failures, foreign fence edits and cluster
replacement stop the update. These bounds do not establish a concurrency root
cause for an unclassified command failure.

HTTP witness retries run synchronously on the guard thread with a separate
180-second recovery budget for allocation POST and for order PUT; this is not an
overall acceptance-time bound. Observing the committed outbox has an additional
60-second bound.
An allocation keeps its original run/ordinal and an order PUT keeps the same ID
and body. Only `503` and selected transient transport failures permit retry;
permanent errors fail immediately. Initial success requires allocation `201` and
PUT `202`; replay `200` is accepted only after an uncertain request. The complete
live drill and final gates passed on October 26.

Workload-control restoration opens a fresh tunnel per attempt and verifies the
exact saved rate and paused state by readback. Each workload allows at most three
attempts within one shared 180-second budget, retrying only `503` or classified
transient transport failures. Other responses and readback mismatches terminate
restoration. Automatic recovery passed in the final qualified run. Earlier
warning-rejected probe and incomplete recovery attempts remain historical evidence.

Failure injection holds deleted pods with invocation-owned finalizers until
PostgreSQL termination is observed. A separate failover-quorum check establishes
an acknowledged witness absent from one survivor, then removes the primary and
aligned standby. For the bounded 180–240 second observation, that survivor must
remain read-only, receive no writable-service endpoint and never be nominated.
Recovery removes only owned finalizers, restores fences, original primary and
workload controls, and verifies exact PVC/backing-volume identities, database
lineage, streaming roles and the final deployment/invariant gates. No data volumes
are deleted; ordinary HTTP fixture business history remains. Recovery failure
retains the shared lock. Coordinated backup/isolated restore passed step 6.3;
older-backup audit/recovery passed [6.4](../CHANGELOG.md#step-64).

## Strict replica-read drill

The opt-in `kind-replica-read-drill` scenario, also selected by `kind-drill`,
covers the deployed read portions of scenarios 3 and 8 and passed
[step 6.1 retained-lab qualification on 2026-10-26](../CHANGELOG.md#step-61).
[Invocation and recovery](RUNBOOK.md#strict-replica-read-drill) are documented in
the runbook. Promotion passed step 6.2 qualification through the separate failover drill above.

The shared guard registers exact restoration of product/routing ConfigMap data
and the full product Deployment template before switching every literal profile
source, including environment and JVM command/argument overrides. Primary checks
reject indirect/external sources; conditional patches reject concurrent edits.
Fresh 200/s reader observations establish clean traffic before and after the
fault phases. Controlled replay lag must yield a stale `404` for an acknowledged
write, followed by success after replay resumes. One fenced standby must preserve
reads and writes; both fenced standbys and a replayed `ACCESS EXCLUSIVE` query
block must yield per-product-pod `503` responses in less than four seconds.
Peer reads must continue, and fresh reader reports must distinguish replica
unavailability without deadline or transport failures.

Recovery restores database state and the exact profile, waits for primary
configuration on the Deployment and every ready product pod, removes invocation-owned
fixtures, restores workload controls and runs the primary deployment gate before
unlocking. Failed restoration retains the shared lock.

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

Capture producer counters after warmup and require at least 1,024 additional
`writeLatency.samples` before assessing its warmed p95. Predetermine a 120-second
observation at 10/s; the 90-second minimum remains sufficient at 30/s only if the
sample requirement is met. Measure elapsed time with a monotonic clock from
receipt of the baseline counters to the start of the final counter request.
An integral-second schedule or the end of later diagnostic collection does not
prove this duration. A short window or insufficient samples invalidates the
measurement; do not extend it until latency happens to pass.

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
