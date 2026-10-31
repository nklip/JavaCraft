# ShardShop changelog

This changelog has one entry for each step of [PLAN.md](PLAN.md): what changed, the key result and the lesson.
[DECISIONS.md](DECISIONS.md) records the decisions and their reasons, and
[`shardshop-testing/evidence/`](shardshop-testing/evidence/) keeps the measurement records.
The Git tag `shardshop-docs-full-2026-10-31` keeps the full text of this file before this compression.

## Milestone 0: Supported baseline and project skeleton

<a id="step-01"></a>
### Step 0.1 — Version and artifact inventory

**Done 2026-09-28.**
The inventory records exact tool and image selections with their digests, and all seven image digest chains verify.
The kind 1.36.5 node image returned 404, thus the lock pins the user-approved 1.36.4 fallback.
Digest-pinned Canonical OpenJDK 25 images build the product and start all six applications as non-root.

<a id="step-02"></a>
### Step 0.2 — Maven skeleton

**Done 2026-09-28.**
The parent and workload aggregators hold six applications that build independently.
All six `clean verify` builds and JAR launches pass on OpenJDK 25 with 100% line coverage from an empty Maven cache.
The build passes on JDK 25 and 26 and rejects JDK 21.

<a id="step-03"></a>
### Step 0.3 — Dependency and test configuration

**Done 2026-09-28.**
Each module has Surefire and the opt-in `integration` profile, which runs `*IT` classes through Failsafe.
All 20 unit tests pass.
Each effective POM agrees with the 17 plugin pins, overrides and test-image digests of the lock, with no prereleases.

<a id="step-04"></a>
### Step 0.4 — Shared shard-routing module

**Done 2026-09-28.**
`shardshop-sharding` implements routing contract version 2, only for product and order.
The router tests pass with 100% line and branch coverage on JDK 25 and 26, with no warnings.
Step 2.4 later made the shard list configurable.

<a id="step-05"></a>
### Step 0.5 — Quarkus 3.40.1 migration

**Done 2026-10-04.**
All six applications run on Quarkus 3.40.1 in JVM mode, with Maven 3.9.16 and Java 25.
They pass `-Pintegration,audit clean verify` with 62 unit and startup tests, 10 packaged startup tests and 100% line coverage.
All six packages start as a non-root user on the pinned JRE.
Three plugin XML prereleases use the version-policy exception.

## Milestone 1: Local cluster and one replicated shard

<a id="step-11"></a>
### Step 1.1 — Local Kubernetes bootstrap

**Done 2026-09-29.**
`up.sh` creates one control plane and three workers (v1.36.4) on a 12 GB Docker Desktop VM, and it stops on a smaller VM.
The node image has 155 advisories (10 critical, 42 high), which the project accepts for this loopback-only, disposable lab.
The `shardshop` namespace enforces `restricted` Pod Security.
A rerun and a `down.sh` and `up.sh` cycle keep all volume data, Clusters, PVCs and primaries.

<a id="step-12"></a>
### Step 1.2 — CloudNativePG installation

**Done 2026-09-29.**
CNPG 1.30.1 from chart 0.29.1 runs the operator by digest.
The chart matches its index SHA-256 and its cosign signature.
An uninstall does not remove the 11 CRDs, and a rerun keeps the CRDs, the operator and the `shard-a` identity.

<a id="step-13"></a>
### Step 1.3 — First quorum shard cluster

**Done 2026-09-29.**
`shard-a` runs PostgreSQL 18.6 with one primary and two quorum standbys (`ANY 1`) on three workers, each with its own 2 GiB PVC.
Logical slot synchronization is on, with a 512 MB slot-WAL budget.
Commits continued with one standby fenced and waited with both standbys fenced.

## Milestone 2: Storage, ownership, and migrations

<a id="step-21"></a>
### Step 2.1 — Remaining shard clusters

**Done 2026-09-29.**
Nine instances form three independent quorum clusters, with one instance of each shard on each worker and nine 2 GiB PVCs.
Data replicates only inside its shard.
With 512 MiB limits, the nine pods used 563 MiB, without OOM events or restarts.

<a id="step-22"></a>
### Step 2.2 — Separate ledger database

**Done 2026-09-29.**
`ledger-db` is a fourth, single-instance CNPG Cluster with its own 2 GiB PVC and `ledger-db-rw` Service, without synchronous standbys or logical decoding.
Restricted client Jobs connect with `verify-full` TLS.
A committed row survived a pod replacement, and the PVC, the system identifier and the credentials did not change.

<a id="step-23"></a>
### Step 2.3 — Database change management and catalog schema

**Done 2026-09-30.**
Flyway 13.8.1 Jobs on a trimmed OpenJDK 25 image connect as `catalog_migrator` over `verify-full` TLS and act as `catalog_owner`.
The clusters disable superuser access.
All nine instances showed the same ownership, grants and constraints, with no triggers or functions.

<a id="step-24"></a>
### Step 2.4 — Single shard inventory and configured routing

**Done 2026-09-30.**
One ordered inventory of shard names and regions renders all shard resources and the routing snapshot.
The chart rejects empty, duplicate, malformed or `ledger-db` inventories, and a changed shard list is refused.
Migrations run one shard at a time, and the routing is published only after them.
Product and order stop at startup without a valid shard list.

<a id="step-25"></a>
### Step 2.5 — Ordering schema and catalog access

**Done 2026-10-01.**
The `ordering` V1 migration and its repeatable grants create 12 owner-owned tables on all shards, with no custom functions, triggers or sequences.
Restricted TLS client Jobs passed 597 assertions (371 expected rejections) for `order_app`, `product_app` and `ordering_cdc`.
Each `ordering_order_outbox` publication contains only outbox inserts.

<a id="step-26"></a>
### Step 2.6 — Ledger schema and permanent decisions

**Done 2026-10-01.**
Ledger V1 creates seven tables that the non-login `ledger_schema_owner` owns.
`ledger_app` passed 129 assertions, and 99 more after a review changed outbox and quarantine inserts to column grants.
A failed ledger migration blocks the routing publication.

## Milestone 3: Product contracts, IDs, and workloads

<a id="step-31"></a>
### Step 3.1 — HTTP and message contracts

**Done 2026-10-06.**
The product and order OpenAPI 3.1.1 documents and the order and ledger JSON Schema 2020-12 envelopes are self-contained, with inline examples.
Each owner module holds its document, and clients keep their own DTOs.
On 2026-10-09 a review removed `BUYER_NOT_FOUND` from the order PUT, because only a known buyer has allocations.
Step 3.12 moved the contract validation into Maven tests.

<a id="step-32"></a>
### Step 3.2 — Common utilities, ID validation and currency selection

**Done 2026-10-06.**
`shardshop-common` replaces six parser copies and the duplicate digest code.
The affected builds pass 186 unit and startup tests and 10 packaged routing tests with 100% line and branch coverage.
All six packaged applications contain the common JAR.

<a id="step-33"></a>
### Step 3.3 — Bounded Snowflake generation

**Done 2026-10-06.**
The three libraries moved under `shardshop-core`.
Generator classes and Snowflake are only in the idgen, product and order classpaths.
Maven Enforcer and compiler probes reject idgen, sharding and Snowflake in ledger, workloads and other consumers, also as optional or transitive dependencies.

<a id="step-34"></a>
### Step 3.4 — Generator allocation at JVM startup

**Done 2026-10-07.**
A launcher reserved a fresh generator ID before each JVM start.
The live kind drill gave distinct IDs to concurrent starts and to a container restart in the same pod, and it rejected a stale identity and a counter rollback.
The admission policy also matches the object name on collection deletes.
Step 3.11 replaced the launcher.

<a id="step-35"></a>
### Step 3.5 — Workload-owned datasets and service-issued IDs

**Done 2026-10-07.**
`shardshop-datasets` holds the ID-free `catalog-v1` and `buyers-v1` definitions: 150 sellers, 300 products and 3000 buyers.
Workload clients use only service-issued IDs.
Only workloads package the dataset JAR, and dependency probes reject it in other modules.

<a id="step-36"></a>
### Step 3.6 — Seller and product API on primaries

**Done 2026-10-07.**
Product serves the seller and product API on primaries.
Disposable PostgreSQL checks passed 74 migration and privilege cases and 163 OpenAPI-validated responses on four shards.
On the lab, Flyway applied catalog V2 on all primaries, and a second run applied nothing.

<a id="step-37"></a>
### Step 3.7 — Product seeder application

**Done 2026-10-07.**
The seeder creates or recovers the same service-issued IDs for all 150 sellers and 300 products, and completion requires verification reads.
A blocked run stopped after 59 sellers and 118 products, and two later complete runs kept the same IDs and digests.
One complete seeder JVM run took 3.9 seconds.

<a id="step-38"></a>
### Step 3.8 — Product reader application

**Done 2026-10-08.**
The reader sends bounded read load with Apache HttpClient 5.6.4 and reports request, latency and error metrics.
Its package contains no Vert.x or Netty.
On four disposable shards, it held 200.0 requests/s, and during a product pause the longest read stopped at the deadline after 5019 ms.

<a id="step-39"></a>
### Step 3.9 — Product deployment and seeding gate

**Done 2026-10-11.**

- Product, the seeder Job and the reader run on the lab. The reader owns its start condition: `CatalogWait` retries the catalog resolution for at most 5 minutes and stops at once on a response that differs from the dataset.
- Two complete runs recovered the key-to-ID digest `97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`. Both product pods received reads within 5 seconds, with only `OK` reads at 200/s.
- Lesson: a 1-CPU limit slowed the product JVM starts, thus product has 2-CPU limits.
- Lesson: a clock jump of the Docker Desktop VM stopped the kubelet pod synchronization. A kubelet restart on each node recovers the lab.

<a id="step-310"></a>
### Step 3.10 — Maven integration tests with Testcontainers

**Done 2026-10-12.**
The new test-only module `shardshop-testing` holds Cucumber 8.0.4 scenarios, as the `*-testing` modules of this repository do. Failsafe runs them only with `-Pintegration`.
27 scenarios replaced six Python check scripts.
Testcontainers 2.0.5 runs without its Ryuk reaper, and a JVM shutdown hook removes the containers.
Lesson: temporary mutations found two gaps. Thus a hook now checks the product logs after each scenario, and `ReaderConfigurationTest` checks the packaged reader defaults.

<a id="step-311"></a>
### Step 3.11 — Generator IDs in the application JVM

**Done 2026-10-13.**
Product and order reserve their generator ID in their own JVM at each start.
The idgen producer sends one guarded JSON patch to the registry ConfigMap through the Kubernetes API, with the service account token and CA.
The launcher, the kubectl image stage and its advisory records are deleted.
11 `GeneratorAllocation.feature` scenarios run against an unprivileged k3s container.
Lesson: an invalid configuration used one slot at each crash-loop restart. Thus the reservation starts after the routing and catalog checks.

<a id="step-312"></a>
### Step 3.12 — Contract checks in Maven and one image script

**Done 2026-10-13.**
Each module that owns an OpenAPI document or a message schema checks it in a Maven unit test with the platform-managed Vert.x 4.5.34 JSON Schema validator.
A test helper corrects four gaps of that validator.
`Contracts.feature` checks the cross-module examples and the 3,450 creation payloads, and `CatalogApi` validates 189 product responses against the OpenAPI document.
The Python contract scripts are deleted.

<a id="step-313"></a>
### Step 3.13 — Build current sources and qualify images through up.sh

**Done 2026-10-13.**

- `up.sh` builds the ShardShop reactor from current sources. All four image builds and advisory gates pass before the first cluster operation.
- Lesson: after a node start, kindnet kept an old `HOST_IP`, and one worker had no route and no DNS. Thus each node start needs a kindnet refresh and a DNS and standby check.
- Lesson: after the `:local` tags moved, older images failed with `CreateContainerError`. Thus `up.sh` tags each loaded image `sha256-<digest>` on each node.
- Lesson: two clean builds gave different JARs. The POM sets `project.build.outputTimestamp`, thus an unchanged rebuild gives the same image IDs.
- The Scout author filter is anchored (`^ShardShop image review$`), because the plain value also accepted other authors.

<a id="step-314"></a>
### Step 3.14 — Create, resume and reconcile the cluster through up.sh

**Done 2026-10-13.**
`up.sh` took ownership of inventory rendering, cluster reconciliation and allocator initialization, with one snapshot of all inputs for each invocation.
It creates an absent cluster, starts only stopped nodes, and rejects incompatible node, topology, storage or PostgreSQL-major changes before an apply.
Only a cluster that the same invocation created can initialize allocator state.
An unchanged run kept every Secret, PVC, Cluster and Database identity and the allocator high-water mark 61.
Lesson: a full run needs Docker Scout connectivity and sign-in.

<a id="step-315"></a>
### Step 3.15 — Replace live verification scripts with Maven scenarios

**Done 2026-10-13.**
The opt-in Maven `kind` Cucumber suite replaces `verify-topology.sh` and `verify-product-workload.py`.
The default tags run read-only deployment scenarios, and `kind-drill` runs the database and workload drills.
The drills share an owner-scoped lock and register recovery before a mutation, and a failed recovery keeps the lock.
Native JUnit tag filters make an unknown tag fail, because the Cucumber filter skipped all scenarios.
Review fixes made fence changes atomic JSON Patch tests of the Cluster UID and resource version.

<a id="step-316"></a>
### Step 3.16 — Migrate and deploy current image digests through up.sh

**Done 2026-10-14.**
Skaffold 2.25.0 builds the four Docker artifacts, and Helmfile 1.8.0 orders the CNPG, infrastructure, migration and application releases.
This replaced the first, custom orchestration of 2026-10-13.
Helm hooks run the migrations in this order: catalog per shard, `ordering` per shard, then ledger. Flyway validates the database history at each run.
A changed ordering checksum stopped Helm before the application release, and a catalog change A → B → A ran again.
Lesson: server-side adoption met an earlier field owner, thus ShardShop releases use the Helm three-way merge.
On 2026-10-13 the numbers of steps 3.15 and 3.16 were swapped to follow the dependency order.

<a id="step-317"></a>
### Step 3.17 — Complete up.sh with a deployment success gate

**Done 2026-10-14.**
A Helmfile `postsync` hook runs `kind-release | kind-deployment`. Thus `up.sh` succeeds only after the release images, configuration, pods, seeder, endpoints and fresh reader traffic pass.
Helm can report `deployed` when this gate fails, and Helmfile then returns nonzero.

- Lesson: the Flyway pin records the platform manifest, because the archive index digest includes the tag.
- Lesson: OpenVEX version 3 names products without a tag, thus a new content tag keeps its statements.
- Lesson: the observer accepts a CRI import index only if it has a single manifest that matches the qualified digest.

<a id="step-318"></a>
### Step 3.18 — Stop the entire cluster through down.sh

**Done 2026-10-15.**
`down.sh` takes the lifecycle lock of `up.sh` and stops all ShardShop nodes.
At the user's request, `cluster-destroy.sh` permanently removes the lab, its volumes and its kubeconfig entries.
After a stop and `up.sh`, all identities stayed the same, and the reader reported 6,000 all-OK requests in 30 seconds (200/s).
On 2026-10-18 both scripts became the lock and one native command (see [step 4.3](#step-43)).

<a id="step-319"></a>
### Step 3.19 — Replace the offline dataset generator with Java tooling

**Done 2026-10-15.**

- A development-only Java generator, which the explicit Maven `regenerate-buyers` execution runs, reproduces the three buyer TSVs byte for byte. The Python generator is deleted.
- At the user's request, `cluster-create.sh` creates kind and the database and credential foundation. Only a fresh cluster initializes the allocator identity.
- After a refactor on the same day, `cluster-create.sh` owns all infrastructure provisioning. `up.sh` builds, scans, loads, migrates, deploys and restarts the application pods at each run.
- Lesson: Skaffold prunes images when artifact caching is off, and it deleted a reused image. Thus `up.sh` enables caching with an empty cache file for each invocation.

<a id="step-320"></a>
### Step 3.20 — Retire old scripts and qualify the explicit lifecycle workflow

**Done 2026-10-15.**
`deploy.sh`, `migrate.sh`, `render.sh` and `shards.sh` are deleted.
The maintained entry points are `cluster-create.sh`, `cluster-destroy.sh`, `up.sh` and `down.sh`, with the sourced helper `scripts/lib/lifecycle.sh`.
A disposable lab passed creation, deployment, a source change, stop and resume, and destruction.
Lesson: cold image pulls kept the DNS probe pods past the 150-second wait. Now `timeout` bounds the DNS command to 120 seconds, and the pod deadline adds 15 minutes for a cold start. The fresh DNS gate then passed in 431.3 seconds.

## Milestone 4: Complete order-to-ledger saga

<a id="step-41"></a>
### Step 4.1 — Order validation and atomic acceptance

**Done 2026-10-16.**
Order serves buyer creation, durable order allocation and order PUT and GET.
Retries with the same creation key recover the same identity, also after a restart.
Acceptance uses two short locked transactions and holds no transaction or lock during catalog IO.
Validation fixes:

- Each database phase runs on its own virtual thread, and a burst waits at most one second for a pooled connection. Before, a phase above the thread count got an immediate 503.
- The phase engine and strict request decoding moved into `shardshop-common`. The applications keep their connection pools.

<a id="step-42"></a>
### Step 4.2 — Stock reservation step

**Done 2026-10-16.**
A background stock coordinator claims database leases that expire and reserves the items of each seller shard in product-ID order.
A reservation insert and its stock decrement share one transaction, thus a retry never decrements twice.
Permanent per-order guards stop late reserve calls after compensation.
Two coordinator JVMs raced 16 orders for four units: exactly four reached `PENDING_LEDGER`, and twelve cancelled with `OUT_OF_STOCK`.

<a id="step-43"></a>
### Step 4.3 — RabbitMQ topology and policies

**Done 2026-10-16.**
RabbitMQ 4.3.6 runs as one persistent StatefulSet with three durable direct exchanges and four single-member quorum queues. The lab broker gives persistence, not high availability.
Source queues retry a failed delivery five times at approximately 1/2/3/4/5 seconds and then park it with `delivery_limit`.
Queues accepted 1001 messages before the negative confirm, because quorum admission overshoots the 1000-message limit.
On 2026-10-18, at the user's request, `down.sh` became one `docker stop --time 10` call and `cluster-destroy.sh` one `kind delete cluster` call, each with the lifecycle lock. 21 of the 30 deleted tests checked only the removed logic.

<a id="step-44"></a>
### Step 4.4 — Order outbox CDC relay

**Done 2026-10-18.**
Order embeds Debezium 3.7.0.Final with one supervised reader per shard and a failover-capable slot.
Only a positive broker confirm without a return permits the publication-status commit, and only that commit permits the source checkpoint.
An absent or inconsistent slot, offset or WAL stops the reader for explicit reconciliation.
Lesson: an idle outbox never confirmed WAL, and its slot would pass the 512 MB `max_slot_wal_keep_size`. Thus each reader sends a heartbeat every 10 seconds.

<a id="step-45"></a>
### Step 4.5 — Ledger command consumer

**Done 2026-10-18.**
Ledger consumes `ledger.record-order` and commits immutable decisions before it acknowledges a delivery.
Duplicates replay the saved result, and conflicts go to durable quarantine.
Malformed messages are rejected without requeue, and database failures use the broker retry budget.
Ledger V2 keeps the original command envelope permanently.
Lesson: the default URL named `ledger-rw`, and each command parked after six failures. A unit test now compares the default with the ledger URL of the chart.

<a id="step-46"></a>
### Step 4.6 — Ledger result publisher

**Done 2026-10-18.**
Ledger publishes committed result outboxes to `order.saga-results` as persistent JSON with mandatory routing.
The mark checks the message ID and the sent publication attempt, thus an old confirm cannot clear a newer replay.
No database transaction spans broker IO.
Validation fix: order and ledger share `ConfirmedPublisher` in `shardshop-common`, because their two copies were already different.

<a id="step-47"></a>
### Step 4.7 — Order result consumer

**Done 2026-10-19.**
Order consumes `order.ledger-results` and applies only reserved results that match.
Confirmation creates per-item profit intents, and separate seller-shard transactions apply each credit once.
Absent orders and conflicts go to quarantine.
Lesson: the consumer locked the raw order ID, but creation locks a SHA-256 key, thus an orphan result could commit during creation. The consumer now uses `OrderService.orderLock`, and both consumers share `QueueConsumer`.

<a id="step-48"></a>
### Step 4.8 — Order producer application

**Done 2026-10-19.**
The HTTP-only order producer verifies the catalog, creates or recovers all 3,000 buyers, and then allocates and submits orders with three items, one from each seller region.
Deterministic ordinals keep allocation coordinates and payloads through retries and restarts.
A restart replays the earlier ordinals before new work, without a workload-owned progress store.
Lesson: the replay waited one order interval for each finished order. Now a finished order gets no wait, and 15 recovered orders take less than one second.

<a id="step-49"></a>
### Step 4.9 — Saga deployment and first end-to-end run

**Done 2026-10-20.**
Skaffold and Helmfile deploy order, ledger and the order producer, and `up.sh` qualifies seven images.
The `kind-saga` gate and the explicit `kind-saga-drill` check the saga on the lab.

- Lesson: the topology check counted the Debezium logical sender as a standby. It now excludes only slot-backed logical senders.
- Lesson: Helm client-side upgrades keep an added environment variable and `nodeSelector` of a drill that stopped without recovery. Thus the application release `presync` hook runs `kind-drills-idle` first.
- The `up.sh` gate creates its own buyer, seller and products, and it does not depend on producer orders.
- SmallRye Config expands `$`, thus the producer run name rejects `$`.

<a id="step-410"></a>
### Step 4.10 — Read-only regional product page

**Done 2026-10-20.**
Product serves the read-only `/products/` page with a US/EU/ASIA selector, stock, prices and exact signed earnings.
`GET /api/v1/products` uses numeric keyset pagination across all shards of a region and fails on a partial result. Catalog V5 adds the credit index.
Follow-ups added the page in the URL and First/Last navigation.
Lesson: browsers mixed new HTML with old scripts, thus on 2026-10-21 the page and its assets changed from `immutable` caching to `no-store`.

<a id="step-411"></a>
### Step 4.11 — Read-only regional order page

**Done 2026-10-21.**
Order serves the read-only `/orders/` page with region, product search, creation-date sort and First/Previous/Next/Last navigation.
`GET /api/v1/orders` reads every primary of the buyer region within one three-second budget and merges the pages by creation time and order ID. The `ordering` V6 migration adds the indexes.
A follow-up shows buyer and product names.
On the same day, the separate `webpages.sh` took over page access: it clears listeners on ports 18080 and 18081 and supervises the tunnels. `up.sh` returns after acceptance.

<a id="step-412"></a>
### Step 4.12 — Regional and country currency policy

**Done 2026-10-21.**
The shared currency policy restricts new prices by seller country: US/USD, EU/EUR or GBP, CN/CNY, VN/VND, KR/KRW and JP/JPY. JPY, KRW and VND use whole units.
Catalog-v2 adds 151 sellers and 201 compliant products, and old orders keep policy version 1.

- Validation fix: producer traffic starts one order with each product in each window of 201 ordinals. Before, the one VND product lost approximately 460 units each hour.
- A later change gives each buyer region its currency mix in cycles of 900 orders.
- Lesson: a Docker VM stall showed a lock inversion in `CatalogDeadline` and `OrderDeadline`. The fix holds the state monitor only to decide which side owns the response.

<a id="step-413"></a>
### Step 4.13 — Workload traffic-control webpages

**Done 2026-10-22.**
The order producer, the product reader and the product seeder each serve a control webpage and a control and status API under `/api/v1/<workload>/control`.
The new `shardshop-core/workload-control` library holds the adjustable pacer, which extends the step 3.8 pacer.
The new `shardshop-product-seeder-control` Deployment runs one interactive seeder run at a time.
Each `up.sh` restores the default rates.
Lesson: with one request in flight and ~7 ms product creations, the seeder reaches only ~140 requests/s.

<a id="step-414"></a>
### Step 4.14 — Concurrent order writes and a separate saga process

**Done 2026-10-23.**
One order image runs as two HTTP replicas or one saga replica (`shardshop.order.process=http|saga`). The saga replica owns stock, profit, CDC and result consumption.
The producer has a bounded submission window and an outstanding-order limit.
At target 30/s, the lab accepted 29.4547 new orders/s with write p95 93.732 ms.

- Lesson: at 30/s, unapplied profit credits grew from 387 to 4,223 in 130 s. Thus the profit worker uses batches of 32 and a 100 ms poll.
- Lesson: an interrupted Debezium cleanup left a replication stream active. Shutdown now closes and joins the engine without an interrupt.

<a id="step-415"></a>
### Step 4.15 — Architecture overview, module design documents and sustainable capacity

**Done 2026-10-23.**
The root architecture became a short overview, and the detailed contracts moved to 16 module `ARCHITECTURE.md` files.
Each README tells only how to start the lab or the application and which monitors exist. A `RUNBOOK.md` beside it holds the procedures.
The read capacity probe passed 1,000 reads/s (963.16/s) and failed 1,500 and 2,000 reads/s. This is a verified lower bound, not a maximum.
See the [read capacity evidence](shardshop-testing/evidence/2026-10-23-read-capacity.json).

## Milestone 5: Recovery, retention, and backpressure

<a id="step-51"></a>
### Step 5.1 — Durable saga reconciliation

**Done 2026-10-24.**
The order saga process replays ledger commands for stuck sagas. HTTP replicas run no reconciliation.
The waits before the five automatic attempts are 60, 120, 240, 300 and 300 seconds, and a restart does not give a sixth attempt.
Each replay keeps the logical identity and the saved snapshot.
Packaged scenarios recovered lost `RECORDED` and `REJECTED` results.

<a id="step-52"></a>
### Step 5.2 — Safe transport cleanup

**Done 2026-10-24.**
The order saga and ledger processes delete published transport rows after a 24-hour retention, in batches of at most 1,000 rows.
Order deletes outbox rows only after a durable Debezium checkpoint passes a WAL barrier after their inserts.
Unpublished rows, permanent decisions and replay identities stay.
The `ordering` V8 and ledger V3 migrations add the cleanup indexes.

<a id="step-53"></a>
### Step 5.3 — Manual reconciliation reset

**Done 2026-10-24.**
The checked-in `database/operations/reset-reconciliation.sql` resets one explicit `PENDING_LEDGER` order and saga pair through `psql`.
It takes the Java advisory lock and the row lock, and other states are no-ops.
The `ordering_operator` login can change only the two retry columns.
In the live operator run, a reset of a short-lived pair caused no replay, and a second reset printed `NOOP`.
Lesson: `kubectl port-forward` stopped after each closed `psql` connection, thus the runbook starts the tunnel in an `until` loop.

<a id="step-54"></a>
### Step 5.4 — Retry and capacity drills

**Done 2026-10-24.**
The explicit `kind-retry-drill` suite has a shard-outage case and four DLQ-pressure cases for both publishers.
The pinned broker parked a message on the sixth rejection after 15,213 and 15,246 ms.
With a limit of eight, each source accepted ten messages before the negative confirm.
Lesson: the dead-letter worker retries on a 180-second timer, and one tick can release only part of the messages. Thus full-DLQ recovery allows 450 seconds.
All five scenarios passed on the final code in 1,460 seconds.

<a id="step-55"></a>
### Step 5.5 — Crash recovery and observability

**Done 2026-10-24.**
A loopback-only JDK debugger stops packaged JVMs at real commit, confirm, acknowledgement and checkpoint boundaries before a forced kill. Production applications have no crash switch.
All 26 recovery scenarios passed.
Order and ledger expose Prometheus metrics through `/q/metrics`, with samples every ten seconds.

- Lesson: file times changed the manifest of a cold Flyway build, thus the Dockerfile sets epoch-zero times.
- Lesson: Agroal reports `Long.MIN_VALUE` as the peak of an empty pool, and the adapter maps it to zero.

## Milestone 6: Read profiles, failover, restore, and operation

<a id="step-60"></a>
### Step 6.0 — Application maintenance and retained-upgrade evidence

**Done 2026-10-25,** under the user-approved reduced local-maintenance scope.

- A plan review of 2026-10-25 added this step. It also made the JavaScript test runners fail without Node, replaced crash source lines with statement anchors, and added a primary-profile deployment check.
- Updates: Quarkus 3.40.1.1, Jackson 2.21.8, pgJDBC 42.7.14, a refreshed JRE and a reproducible Flyway image.
- Older-lab upgrade: on a pre-5.3 lab, `cluster-create.sh` and `up.sh` kept 39 resource and credential identities, four database identities and 6,408 row hashes.
- The user accepted the saved JRE, K3s and Flyway-driver findings until 2026-10-28. CNPG and managed PostgreSQL changes were outside the scope.
- The nine final gates passed in 108.2 s.
- Performance went to later steps: 10/s write p95 was 133.783 ms, 30/s reached backpressure at ~24.65/s, and the first 1,000/s read window gave 948.2/s.
- Lesson: one standby stopped its WAL replay while its pod stayed ready. Pod readiness does not prove replication health.
- Lesson: a rolling p95 needs at least 1,024 post-warmup samples, thus the collector uses monotonic deadlines and a sample gate.

<a id="step-61"></a>
### Step 6.1 — Strict replica-read drill

**Done 2026-10-26.**
The seeder retries verification `404` responses within its shared attempt budget.
The new `kind-replica-read-drill` switches product to strict replica reads and restores the primary profile before it releases the lock.
With both standbys fenced, product returned `503 READ_REPLICA_UNAVAILABLE` in 105 ms and 209 ms, without primary fallback. A blocked standby query returned `503` in 3,114 ms and 3,128 ms.
Lesson: a wrapper with `umask 077` gave the packaged directories mode 0744, and the runtime user could not read the libraries. Builds use `umask 022`.

<a id="step-62"></a>
### Step 6.2 — Synchronous failover drills

**Done 2026-10-26.**
The user approved the CNPG 1.30.1 pin for this drill, because it was still the latest release.
The `kind-failover-drill` proves:

- CDC pauses with either standby absent while quorum writes continue.
- Planned switchover and a real primary failure keep acknowledged sagas, unpublished commands and slot continuity.
- A survivor that lags stays read-only and unpromoted for 180 seconds.

The complete scenario passed in 743.7 seconds, and the eight final gates passed.
Lessons from the failed attempts:

- A former primary keeps a `synced=false` logical slot copy, which blocks slot synchronization. The guard retires only that inactive copy after it verifies the new primary.
- Fence patches compare only the Cluster UID and the fence value, because CNPG status updates invalidated whole-resource preconditions.
- Recovery writes and control restoration retry `503` and transient failures with the same request.
- The guard waits for both physical standby slots before a logical decode.

<a id="step-63"></a>
### Step 6.3 — Coordinated backup and isolated restore

**Done 2026-10-26.**
`kind-backup` blocks the writers, drains the saga and broker work, and then makes native custom-format dumps of all four databases with metadata and per-table hashes.
The private bundle stays outside kind and Maven `target`.
`kind-restore` restores into an owned PostgreSQL container and compares all tables. It proves that the packaged relay rejects restored offsets without a slot, and then it resets only the isolated target.
Snapshot and WAL replay kept the original command and result IDs: 11,537 orders, 11,535 ledger decisions and 11,534 ledger entries.
`kind-backup-allocator` keeps an independent allocator history, and an older dump never overwrites it.

- Lesson: Testcontainers needs the pinned image declared compatible with `postgres`.
- Lesson: the isolated ledger takes its allowlist from `CurrencyPolicy.SUPPORTED_CURRENCIES`.

<a id="step-64"></a>
### Step 6.4 — Restore audit and recovery

**Done 2026-10-26.**
Four Maven entry points export original records, audit orders against permanent ledger decisions, repair selected orders and run an older-dump recovery drill in fresh owned containers.
Review dumps carry `trafficAuthorized: false`. Production cutover is outside this workflow.
The drill restored an older dump without two acknowledged orders. The audit found all three incidents before late results, and HTTP reuse returned `409 ORDER_RECONCILIATION_REQUIRED`.
Verified recovery restored seven rows and replayed the original command once, without a new ledger entry. Two unresolved cases stay.

- Lesson: the audit opened three TLS connections for each lookup, thus it now reuses its connections.
- Lesson: `pg_restore --schema=ordering` omits `CREATE SCHEMA`, thus the restore uses the complete ordering archive.

<a id="step-65"></a>
### Step 6.5 — Order-write p95 below 100 ms

**Done 2026-10-28.**
StockWorker and ProfitWorker process buyer-home shards concurrently, with `shard-concurrency` default 3 (`1..32`).
Isolated A/B runs gave 40.60 → 57.99 orders/s for stock (+42.8%) and 182.29 → 299.16 intents/s for profit (+64.1%).
Two first kind runs stopped under heavy host paging. The third run recorded paging only as a diagnostic and passed 10/s: 1,201 writes in 120.031668 s (10.0057/s), write p95 38.466 ms, saga p95 1,532.789 ms.
Lesson: examine the host memory headroom before a load run.
Evidence: [stock](shardshop-testing/evidence/2026-10-28-stock-concurrency.json),
[profit](shardshop-testing/evidence/2026-10-28-profit-concurrency.json),
[kind load test](shardshop-testing/evidence/2026-10-28-parallel-workers-kind-pagingdiag.json).

<a id="step-66"></a>
### Step 6.6 — Sustained 30 new orders/s

**Done 2026-10-28.**
The same kind run passed 20/s (19.9492/s, write p95 62.206 ms) and 30/s: 2,643 writes in 90.039492 s (29.3538/s), write p95 85.702 ms, saga p95 2,228.357 ms.
Errors, cancellations and backpressure were zero, and the backlogs stayed below the 100-order bound and drained.
No matched full-lab serial and parallel comparison exists, thus no causal speedup is claimed.

<a id="step-67"></a>
### Step 6.7 — Qualify 1,000 product reads/s

**Done 2026-10-28.**
Opt-in reader schedule diagnostics and product JDBC phase timers were added. Both are off by default.
One disposable lab passed 1,000 reads/s on the primary profile with two product pods and 16 reader workers: 89,560 OK reads in 89.999985248 s (995.111/s), p95 2–3 ms.
The CPU had no throttle, and the pool had no waits.
Writes were paused, thus this is not a combined read and write result.
See the [read diagnostics evidence](shardshop-testing/evidence/2026-10-28-read-diagnostics-kind.json).

<a id="step-68"></a>
### Step 6.8 — HTTP/2 transport

**Done 2026-10-30:** research done, HTTP/1.1 kept.
A support review on 2026-10-29 checked the support dates of all lock entries due by 2026-11-01, without the security part.
The research sent h2c with prior knowledge from the workload clients through two standalone Envoy pods to product and order.
After a Docker restart, both transports met all write criteria at 10, 20 and 30 orders/s:

- Write p95 at 10, 20 and 30/s: `HTTP_1_1` 29.3, 25.6 and 26.7 ms. `H2C` 31.3, 32.8 and 32.0 ms. `H2C` with 32 streams 33.4, 53.0 and 52.0 ms.
- Highest passed read target: 8,000/s with `HTTP_1_1`, 6,000/s with `H2C` and 16 streams, 8,000/s with 32 streams. Read p95 at 8,000/s was 2 ms against 5 ms.

On 2026-10-30 the user kept HTTP/1.1, because HTTP/2 gave no throughput gain, a higher latency and more components. One Envoy pod received all connections and had no drain on SIGTERM.
The implementation is reverted. Its 46 files are in a local Git stash.
Lesson: a background job started with `&` from a non-interactive shell ignores SIGINT. Thus start `up.sh` as a normal background task.
See the [transport comparison evidence](shardshop-testing/evidence/2026-10-30-http2-transport-comparison.json).

<a id="step-69"></a>
### Step 6.9 — Complete demo and acceptance run

**Done 2026-10-31.**
The overdue security review came first. The user approved Docker Desktop 4.97.0, Flyway 13.12.0, RabbitMQ 4.3.7, rebuilt CNPG and PostgreSQL images, and routine tool updates.
The Canonical JRE and JDK stay, because Ubuntu 26.04 has no OpenJDK 25.0.5 package. The user renewed the local risk acceptance until 2026-11-30.
The integration suite passed in 48:52 min: 2,060 unit and provider tests, 30 and 49 packaged startup tests and 161 scenarios.
All 20 steps of the documented acceptance sequence passed on one disposable lab in 77 minutes, and all eleven acceptance scenarios passed.
The generator registry kept one UID, with high-water marks 0, 5, 26 and 31.
Then `cluster-create.sh` and `up.sh` gave the updates to the retained lab.
See the [acceptance run evidence](shardshop-testing/evidence/2026-10-31-acceptance-run.json).
