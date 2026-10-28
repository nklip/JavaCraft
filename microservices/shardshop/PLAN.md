# ShardShop implementation plan

## 1. Implementation milestones

This is the implementation checklist for `microservices/shardshop`, updated on
2026-10-28. Every step starts as **Planned**; existing design documents and routing
vectors do not mean the corresponding application behavior is implemented.

### Current position

- **Completed:** milestones 0–5 and steps 6.0–6.6. The current deployment has
  two order HTTP pods and one separate `shardshop-order-saga` pod, using the same
  order image. The [overview](ARCHITECTURE.md) and module designs describe current
  behavior; dated completion notes below describe what passed at that time. The
  older-lab foundation upgrade for 5.3 now passes in the isolated 6.0 rehearsal,
  including retained identities/data, restricted reset and once-only replay.
- **Current:** steps **6.5 and 6.6 are Done**: the October 28 fixed load sequence
  meets the 10/s latency and sustained 30/s write acceptance criteria, with
  concurrent reads near 200/s. See the latest measurement below and
  [milestone closure](CHANGELOG.md#steps-65-and-66--acceptance-closure--2026-10-28).
- **Recovery qualification:** step **6.4, restore audit and recovery, is Done**.
  The full isolated older-dump drill passed on October 26: audit found previously
  acknowledged missing orders and a fingerprint mismatch before late delivery;
  quarantine blocked HTTP reuse; verified original recovery and original-command
  replay preserved ledger identities and catalog effects. Unrecoverable cases
  retain permanent ledger history and unresolved incidents. Sixty focused checks,
  repeated native schema restoration, full drill and independent cleanup/input
  audits pass. The retained source lab remains stopped and unchanged. See
  [6.4 evidence](CHANGELOG.md#step-64). Coordinated backup/CDC recovery remains
  qualified by [6.3](CHANGELOG.md#step-63), and synchronous failover by
  [6.2](CHANGELOG.md#step-62). Optional physical asynchronous loss and production
  cutover remain outside this qualified isolated recovery workflow.
  Step **6.0 remains Done under the user's reduced local-maintenance scope**.
  The user deferred capacity qualification to 6.5/6.6/6.7; write qualification
  now passes, while 1,000/s read qualification remains open in 6.7. Local advisory
  acceptance ends October 28. The user authorized
  6.2 qualification on pinned CNPG 1.30.1/chart 0.29.1 despite its saved findings
  after October 26 verification that 1.30.1 remains latest GA; see the
  [decision](VERSIONS.md#step-62-existing-version-decision--2026-10-26).
- **Remaining:** milestone 6 continues with **6.7**, qualifying 1,000/s reads.
  HTTP/2 follows in **6.8**, immediately before the final demo and acceptance run
  in **6.9**. AWS remains optional and starts only after local step 6.9.
  Other remaining steps are **Planned**.
- **Latest combined-worker load test (2026-10-28):** with paging diagnostic only,
  the fixed 10/20/30 sequence passed: **10.006 / 19.949 / 29.354 accepted orders/s**,
  with final rolling write p95 **38.466 / 62.206 / 85.702 ms** and concurrent reads
  near 200/s. All duration/sample/delivery gates passed; no new errors,
  cancellations, recoveries or observed backpressure; bounded backlogs drained
  and stock stayed positive. Controls and retained state were restored. This
  qualifies those local operating points, not a causal full-lab worker speedup
  or a hardware-independent maximum. The subsequent acceptance review closes
  6.5 and 6.6 using this evidence; see [load-test evidence](CHANGELOG.md#combined-worker-concurrency-paging-diagnostic-load-test--2026-10-28).
- **Earlier combined-worker diagnostics (2026-10-28):** the fresh lab passed its
  release gates, but the target 10/s phase hit the 100-order backpressure bound
  after 50.040870s: 212 new writes (~4.24/s), backlog 40 → 99. The 20/30 phases
  were not attempted. Reads delivered approximately 110.81/s against target
  200/s; host swap-ins/outs each exceeded 12 GiB over 42.366s. This incomplete,
  resource-contended observation establishes neither sustainable capacity nor
  a causal worker improvement/regression. Controls and retained state were
  restored; see [combined-worker evidence](CHANGELOG.md#combined-worker-concurrency-kind-measurement--2026-10-28).
  A retry after closing apps delivered **199.735 reads/s** without new errors,
  but the assistant's extra conservative paging guard stopped it before writes:
  1,096.16 MiB swap traffic in the readiness minute, improving toward its end.
  This is a readiness result, not an application SLO failure or new write-capacity
  result; see [retry evidence](CHANGELOG.md#combined-worker-concurrency-retry-after-closing-apps--2026-10-28).
- **Earlier duration/sample-eligible write measurement (2026-10-25):** the maintained deployment achieved
  **1,177 new writes in 120.058077 seconds (~9.8/s)** at target 10/s, with product
  reads configured at 200/s. Final rolling allocation-through-PUT **p95 was
  133.783 ms**, above the unchanged **<100 ms** requirement; rolling mean was
  38.337 ms and saga p95 was 2.031 s. There were no new errors, cancellations or
  historical recoveries, no backpressure, and the producer backlog drained.
  The duration and >=1,024 post-warmup sample gates passed. This is a valid failed
  latency check, not a qualified capacity point. A later 30/s attempt reached the
  100-order backpressure limit after warmup and stopped early: 987 new orders
  over 40.044426s (~24.65/s), an incomplete observation rather than a sustained
  capacity result. The later first eligible 1,000/s reader window delivered only
  948.2/s (28,446 requests/30s), all OK with p95 10 ms, below the unchanged
  950/s minimum; unqualified, not a server-capacity limit. Final gates later passed under the reduced scope. See [6.0 evidence](CHANGELOG.md#step-60).
- **Historical capacity baseline:** step 4.14 measured 29.45 new accepted orders/s, including
  28.11 confirmed/s; step 4.15 qualified 963.16 product reads/s. The peaks were
  measured separately. These are bounded lab observations, not proven universal
  maxima; use the [measurement protocol](shardshop-testing/ARCHITECTURE.md#capacity-measurement)
  and dated evidence when comparing later changes.
- **Version gate:** step 6.0 records the **2026-10-25** maintenance review and
  security updates. Unresolved advisory scopes keep the **2026-10-28** review
  deadline; integrity checks do not waive findings. RabbitMQ successor planning
  is recorded ahead of **2026-11-02**. CNPG's **2026-11-30** upgrade deadline
  remains a separate maintenance obligation outside 6.0. October 26 verification
  found 1.30.1 remains latest GA; the user cleared the maintenance gate only for
  6.2 qualification on that existing pin, without changing scan gates or deadlines.

Completed step IDs and evidence remain stable. Earlier dataset sizes, script names,
sequential producer behavior and sizing estimates are historical; later completed
steps supersede them. Use the current [runbooks](RUNBOOK.md) for executable commands.

### Implementation constraints

**Framework:** all six applications use **Quarkus 3.40.1.1** (3.40 LTS line) in JVM
mode on Java 25. Step 0.5 records the original framework qualification; the
[6.0 review](VERSIONS.md#step-60-maintenance-review--2026-10-25) records the current
patch and its qualification. [Release status](https://quarkus.io/releases/).

**Architecture constraints:** every milestone below must obey these rules of
[ARCHITECTURE.md](ARCHITECTURE.md):

- [ID ownership](shardshop-core/ARCHITECTURE.md#goal-and-module-boundaries): only product and
  order create IDs.
- [Workload datasets](shardshop-workload/shardshop-datasets/ARCHITECTURE.md#definitions-and-keys):
  ID-free definitions in `shardshop-workload`.
- [Seller profit](shardshop-product/ARCHITECTURE.md#seller-profit): confirmed
  margin for each currency.
- [Regional placement](database/ARCHITECTURE.md#sharding-and-replication):
  `shard-a=US`, `shard-b=EU` and `shard-c=ASIA`.
- [Flyway SQL](database/ARCHITECTURE.md#migrations): no SQL functions or triggers.

All numbered architecture scenarios below refer to the retained
[acceptance scenarios 1–11](shardshop-testing/ARCHITECTURE.md#acceptance-scenarios).
The overview links to each topic's owning module architecture.

**Retained baselines:** on 2026-10-01, a reset of the empty lab catalogs applied the
consolidated `V1__catalog.sql` with the regional seller constraints and comments.
Retained baselines are now immutable. Later schema changes use fix-forward migrations.

### Instructions for executing a step

1. Read the repository's `AGENTS.md`, this plan, the root and affected module
   README, `RUNBOOK.md` and `ARCHITECTURE.md`, and [VERSIONS.md](VERSIONS.md).
   Follow the [overview](ARCHITECTURE.md) links to each topic's owner. The
   architecture defines behavior; the version policy defines supported artifacts and the named library
   exception. The remaining sections of this file provide implementation context.
2. Check the listed prerequisite steps and their actual deliverables. Implement
   only the selected step. If a prerequisite is missing, record the specific
   blocker rather than silently expanding the task to another milestone.
3. Change that row's status to **In Progress** when work starts. Keep changes
   scoped to ShardShop, except its explicitly planned registration in
   `microservices/pom.xml`. Preserve the six applications, the database layer's schema ownership,
   Snowflake contracts, and absence of shard selectors in public APIs.
4. Add the behavior and edge/error tests required by the repository instructions.
   Run the affected module's checks; never build the repository root reactor.
   Prose-only changes require link/reference and content checks, not a Maven build
   or lab deployment. Capacity measurements may orchestrate the existing control
   APIs and collect metrics with temporary tooling; they do not replace Java tests
   or become lifecycle entry points. Test Java behavior only with Maven tests. Where IO matters, use integration
   tests in the `integration` profile with Testcontainers and pinned images, for
   example a real PostgreSQL or RabbitMQ. Do not add a bash or Python check for
   Java behavior. Use bounded polling for Kubernetes checks. Infrastructure steps
   are verified by live runs against `kind-shardshop`, recorded as evidence. Step
   3.15 provides the separate opt-in `kind` Maven profile for infrastructure
   safety, deployment observations and explicitly selected recovery drills. Keep only
   the lifecycle and webpage-access entry points described in instruction 9. Set the `kind-shardshop` context
   explicitly in every command ([§5](#5-local-infrastructure-details)).

   Reuse the retained lab for nondisruptive checks. If fresh-cluster verification
   requires a disposable lab, use a unique owned cluster name and private
   kubeconfig, and register cleanup before creation. Capture bounded diagnostics
   and delete that owned test cluster on success, failure or interruption; verify
   that its containers and kubeconfig context are gone before finishing. Do not
   leave a failed test cluster running for diagnosis. Plan capacity first: do not
   run two full four-node database labs concurrently in the default Docker VM.
   Preserve the retained lab and its prior running state. This cleanup belongs to
   the disposable test's owner; normal `up.sh` and `down.sh` must never delete
   retained or unrelated clusters.
5. Mark the row **Done** only after its acceptance condition and relevant checks
   pass. Then add the date and one result sentence to its acceptance cell. Record
   the evidence and the exact commands in the step's entry of
   [CHANGELOG.md](CHANGELOG.md). Use
   **Blocked** with a concrete reason when completion is prevented; resume as
   **In Progress** when the blocker is resolved. Partial work is not **Done**.
6. Take Maven, Node.js, `kind`, `kubectl`, `helm`, `helmfile`, `skaffold`, and Docker from the user's `PATH`.
   Never download, vendor, wrap, or version-pin tool binaries in the repository:
   no project-local tool folders such as `.local/bin` and no Maven Wrapper. Record
   tested versions in [VERSIONS.md](VERSIONS.md) instead. Third-party charts and
   manifests are artifacts, not tools: vendor them only with a verified checksum
   in the lock. Keep the four lifecycle scripts as local bootstrap orchestration around
   Maven, Skaffold builds, Helmfile release dependencies/hooks and native Kubernetes
   or kind/Docker operations. Put reusable lab assertions in Java tests;
   steps 3.13–3.20 retired the earlier helper and verification entry points. Record
   dated results and exact commands in [CHANGELOG.md](CHANGELOG.md). Keep generated
   scan reports and execution logs outside version control. The compact interval
   data retained by step 4.15 is measurement evidence; it does not make generated
   audit-report directories or temporary probe scripts repository artifacts.
7. Milestones 0–6 run and are verified entirely on this machine, on the local
   kind lab or in disposable local containers. Milestone 7 (AWS with Terraform)
   is an optional advanced track that
   starts after step 6.9; never make a local step depend on it or on a cloud
   service.
8. Keep each installed component inside its support window at all times. This
   gate applies throughout implementation. Before you start a step, read the review
   deadlines in [VERSIONS.md](VERSIONS.md#4-maintenance-and-acceptance-gates) and the lock.
   Complete a due review before the step; a review date is not itself an EOL date
   or evidence that an upgrade exists. If a support/upgrade deadline occurs before
   the step can end, qualify and apply the upgrade first. An unavailable compatible
   GA successor blocks work beyond that deadline; a roadmap is not a release. In
   VERSIONS.md, record these items:
   - the successor and its compatibility checks
   - the upgrade and recovery procedure
   - the results of the checks that exist at that time
9. Give each behavior to the tool that owns it. A script is the last choice.
   - Bash: invocation snapshots/locks, Maven and Docker Scout invocation, native
     kind/Docker lifecycle and verified image delivery, missing credential creation
     and fresh allocator bootstrap. Namespace, credentials and allocator state stay
     outside Helm release ownership.
   - Skaffold: current image artifacts, Docker build recipes and tags. Use its
     build-only mode; a native kind adapter delivers images when no Skaffold deployer
     is configured. Do not pretend a standalone build performs deployment/loading.
   - Helmfile: real Helm release dependencies, ordered sync stages, timeouts and
     Maven phase hooks. Infrastructure sync precedes fresh allocator initialization;
     migration/application sync follows readiness and verified image delivery.
   - Helm: declarative release resources, Flyway/seeder hooks and configuration;
     render DNS/allocator bootstrap manifests and share templates across environments.
   - Kubernetes and CNPG: reconciliation, restarts, readiness, scaling, Secrets,
     admission rules, database roles, databases and publications.
   - Flyway Jobs: validate authoritative database history on every sync and apply
     pending schema/repeatable changes, with schema ownership in `database/`. Do not
     cache completed Jobs or rebuild Helm/Kubernetes identity matching in Java.
   - Maven: builds, dependency checks and opt-in Java/Cucumber kind suites that
     verify invariants, readiness and lifecycle safety at the required phase gates.
   - Java applications: application and workload behavior, with platform-managed
     libraries before custom code.

   The lifecycle starts with the user-requested `scripts/cluster-create.sh`,
   which creates Kubernetes and the database/allocator foundation without
   applications, and resumes/reconciles existing infrastructure under retained-state
   guards without resetting allocator history. Then `scripts/up.sh` builds, migrates, deploys and
   verifies applications on that existing cluster. Every invocation restarts
   application pods, including unchanged builds. `scripts/down.sh` stops every node while
   retaining data. `scripts/cluster-destroy.sh` removes the lab, attached storage
   and kubeconfig entries. All four share the lifecycle lock. Steps 3.13–3.20 retired
   the earlier lifecycle and verification entry points. Steps 4.11 and 4.13 add
   the separate `scripts/webpages.sh` access command for five localhost tunnels;
   it holds no lifecycle lock and performs no deployment. Do not add further
   entry points or replacement Python wrappers. The requested
   `scripts/lib/lifecycle.sh` is a sourced implementation helper shared by the four
   entry points for locks/traps. Creation and startup also use it for
   snapshots/rendering, verifiers/readiness and node resumption. It is not another
   entry point. Startup never provisions namespaces, credentials or infrastructure
   releases. Shutdown disables node restart policies with `docker update --restart=no`
   and calls `docker stop`; destruction calls `kind delete cluster`, under the lock.
   Keep the scripts focused on bootstrap: use native bounded waits where they prove the
   requirement, and bounded Maven assertions for cross-resource or SQL checks.
   Do not embed Python, custom JSON/YAML parsing or reusable assertions in Bash,
   download tool binaries, or replace shell code with a Java reconciler. Java kind
   suites observe safety and readiness; they do not become lifecycle controllers.
   Required guards run before the mutation, migration or routing publication they
   protect, using the same inventory and configuration snapshot as the lifecycle.
   Before another migration release, reject active, pending or terminating Flyway
   Jobs; Helm revision names avoid deleting an interrupted attempt. Keep explicit
   ownership adoption of legacy apply-managed resources separate from normal sync,
   and never steal another release. Do not automatically roll back or uninstall a
   failed release: Helm cannot undo committed SQL. After a killed Helm client,
   inspect status/history and recover any pending release bookkeeping explicitly
   only after processes and hook Jobs are no longer active; the idle guard does not
   clear a pending Helm operation.
   Later milestones extend those workflows and suites without requiring local
   GitOps or cloud services. Creation first caches repository and microservices parent POM
   metadata through non-recursive `install:install-file` calls, without building
   their reactors. It builds, verifies and installs only the safety observer
   and its shared common test-helper dependencies through
   `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true clean install -DinstallAtEnd=true`.
   Its direct Failsafe gates use the same property to avoid application reactor-order
   dependencies and previously installed application POMs; default builds retain
   those dependencies. Creation does not build applications. Startup uses `up.sh` with
   `mvn -B -ntp -f microservices/shardshop/pom.xml clean install -DinstallAtEnd=true`:
   `install` includes `verify` and puts the verified reactor artifacts in Maven's
   local cache only after the reactor succeeds, so later Failsafe phase gates can
   resolve them without rebuilding packages. This remains a ShardShop-only build;
   it does not install tool binaries or publish artifacts to a remote repository.
   Workload applications own their lifecycle: their Java code decides when they
   feed or read data. `up.sh` observes their readiness and completion without
   implementing their seeding or load loops. Product and order get
   their generator IDs from Java code in their own JVM, without an external binary,
   a separate service or a separate pod. Images use the OpenJDK runtime of the
   selected Ubuntu JRE image, never a Temurin runtime.

Execute steps in dependency order. Use the table order, except for step 0.5,
which follows step 2.4. “Depends on” lists direct prerequisites and includes
their prerequisites transitively. Scenario numbers refer to the
[architecture's acceptance scenarios](shardshop-testing/ARCHITECTURE.md#acceptance-scenarios).
Milestone numbers 0-7 remain stable for references from other documents.

On 2026-10-16, unfinished steps were renumbered to match execution order:
stock reservation moved from 4.9 to 4.2, the former 4.2–4.8 became 4.3–4.9,
and HTTP/2 moved from 6.7 to 6.5. Completed step IDs are unchanged. Historical
changelog entries and immutable migration comments retain their original numbers.

On 2026-10-28, the user moved HTTP/2 and the final demo after performance
qualification: former 6.7/6.8/6.9 are now 6.5/6.6/6.7, former 6.5 (HTTP/2)
is now 6.8, and former 6.6 (demo) is now 6.9. HTTP/2 remains immediately
before the demo because its integration and qualification effort is substantial
and its end-to-end performance gains are unconfirmed. Acceptance criteria and
completed step IDs are unchanged; historical changelog entries retain the IDs
used when they were written.

### Milestone 0: Supported baseline and project skeleton

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 0.1 Version and artifact inventory | None | Done | Exact compatible tool/image selections, origins, support dates and review deadlines are recorded, and the Snowflake exception is explicit. **2026-09-28:** all seven image digest chains verify. [Evidence](CHANGELOG.md#step-01) |
| 0.2 Maven skeleton | 0.1 | Done | The parent and workload aggregators contain six independently buildable applications, with a Java 25 minimum. **2026-09-28:** all six builds and JAR launches pass on OpenJDK 25. [Evidence](CHANGELOG.md#step-02) |
| 0.3 Dependency and test configuration | 0.2 | Done | Effective POMs, resolved dependencies/plugins and the SBOM agree with the inventory, and each module has Surefire and the opt-in `integration` profile. **2026-09-28:** each effective POM matches the lock's 17 plugin pins, overrides and test-image digests. [Evidence](CHANGELOG.md#step-03) |
| 0.4 Shared shard-routing module | 0.3 | Done | `shardshop-sharding` implements routing contract version 2, only for product and order. Golden vectors cover every shard, a value above 2^53, the signed-long maximum and digests with the high bit set. **2026-09-28:** the router tests pass with 100% line and branch coverage. [Evidence](CHANGELOG.md#step-04) |
| 0.5 Quarkus 3.40.1 migration | 0.4, 2.4 | Done | All six applications run on Quarkus 3.40.1 in JVM mode. **2026-10-04:** all six pass `-Pintegration,audit clean verify` and start as a non-root user on the pinned JRE. [Evidence](CHANGELOG.md#step-05) |

### Milestone 1: Local cluster and one replicated shard

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 1.1 Local Kubernetes bootstrap | 0.1 | Done | One control plane and three workers run on Docker Desktop with at least 12 GB of VM memory, and the node image's advisories are reviewed. Every command targets the `kind-shardshop` context explicitly, startup keeps existing storage, and manifests follow the §5 portability rules. **2026-09-29:** `up.sh` creates four Ready v1.36.4 nodes, and a rerun keeps all volume data. [Evidence](CHANGELOG.md#step-11) |
| 1.2 CloudNativePG installation | 1.1 | Done | The CNPG operator runs a release inside its support window from its verified Helm chart, deployed by digest. It matches the Kubernetes support matrix, and a rerun does not replace databases. **2026-09-29:** CNPG 1.30.1 runs from chart 0.29.1, and a rerun keeps the CRDs, the operator and the `shard-a` identity. [Evidence](CHANGELOG.md#step-12) |
| 1.3 First quorum shard cluster | 1.2 | Done | `shard-a` has three instances with separate PVCs, one on each worker, with quorum commit and failover slots synchronized to both standbys. Writes continue with one standby absent and block with both absent. **2026-09-29:** `verify-topology.sh shard-a` passes. [Evidence](CHANGELOG.md#step-13) |

### Milestone 2: Storage, ownership, and migrations

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 2.1 Remaining shard clusters | 1.3 | Done | Nine shared PostgreSQL pods form three independent three-instance quorum clusters, and data replicates only inside its shard. **2026-09-29:** the drill of each shard passes. [Evidence](CHANGELOG.md#step-21) |
| 2.2 Separate ledger database | 1.3 | Done | The ledger has its own persistent single-instance database and Service, outside the nine shard pods, and a restart keeps its data. **2026-09-29:** a committed row survives a pod replacement. [Evidence](CHANGELOG.md#step-22) |
| 2.3 Database change management and catalog schema | 2.1 | Done | The [database layer](database/ARCHITECTURE.md#database-change-management) owns the catalog roles, schema and Flyway stream, and no migration needs a superuser session. **2026-09-30:** all nine instances have identical catalog ownership, grants and constraints, without triggers or functions. [Evidence](CHANGELOG.md#step-23) |
| 2.4 Single shard inventory and configured routing | 2.3, 0.4 | Done | One ordered inventory of shard names and immutable regions renders all shard resources and the routing snapshot, and the topology guards fail closed. **2026-09-30:** the shared chart renders the earlier resources without change, and the 14 Cluster/PVC identities stay the same. [Evidence](CHANGELOG.md#step-24) |
| 2.5 Ordering schema and catalog access | 2.3, 2.4 | Done | The ordering stream creates the buyer, allocation, order, saga, transport and quarantine tables on every shard, with the outbox publication, the CDC offsets table and the [role matrix](database/ARCHITECTURE.md#roles). **2026-10-01:** restricted TLS client Jobs pass 597 grant assertions. [Evidence](CHANGELOG.md#step-25) |
| 2.6 Ledger schema and permanent decisions | 2.2, 2.3 | Done | The ledger stream creates entries, permanent decisions, result bindings with publication attempts, inbox, outbox and command quarantine, with separate migration and runtime roles. **2026-10-01:** restricted TLS `ledger_app` Jobs pass 228 grant assertions. [Evidence](CHANGELOG.md#step-26) |

### Milestone 3: Product contracts, IDs, and workloads

Steps 3.13–3.20 establish explicit creation, deployment, retained stop and
destruction commands. `cluster-create.sh` prepares the Kubernetes/database
foundation. A subsequent normal `up.sh` invocation builds the current ShardShop
sources, resumes an existing cluster when needed, checks the infrastructure
prepared or reconciled by `cluster-create.sh`, migrates the databases, deploys the built image
digests, and returns success only after deployment verification passes. `down.sh`
stops the whole cluster without deleting storage. Every `up.sh` now restarts
application pods as requested alongside step 3.19. Completed rows below retain
their historical commands; the new steps replace those commands, not their
acceptance requirements. At this milestone, deploy product, the seeder and the
reader; step 4.9 adds order, ledger and the order producer when they are functional.

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 3.1 HTTP and message contracts | 0.5 | Done | Each owning module holds its self-contained OpenAPI document or message schema, and clients keep their own DTOs. **2026-10-06:** `verify-contracts.py` validates their structure, examples and boundaries. [Evidence](CHANGELOG.md#step-31) |
| 3.2 Common utilities, ID validation and currency selection | 3.1 | Done | `shardshop-common` owns the one `IdParser` and the unsigned SHA-256 calculation for all six applications and routing. **2026-10-06:** the affected builds pass 186 unit/startup tests with 100% line and branch coverage. [Evidence](CHANGELOG.md#step-32) |
| 3.3 Bounded Snowflake generation | 0.5 | Done | `shardshop-idgen` gives each product/order process one eager, bounded Snowflake generator, as [ARCHITECTURE](shardshop-core/idgen/ARCHITECTURE.md#identifier-generation-and-representation) defines. **2026-10-06:** 215 unit/startup tests, 40 packaged startup checks and the scenario 10 library checks pass. [Evidence](CHANGELOG.md#step-33) |
| 3.4 Generator allocation at JVM startup | 1.1, 3.3 | Done | The product/order launcher reserves a fresh generator ID before every JVM start, as [ARCHITECTURE](shardshop-core/idgen/ARCHITECTURE.md#generator-identity-across-processes-and-restarts) defines. **2026-10-07:** the live kind drill reserves distinct IDs for concurrent starts and for a container restart. [Evidence](CHANGELOG.md#step-34) |
| 3.5 Workload-owned datasets and service-issued IDs | 3.1, 3.2 | Done | `shardshop-datasets` holds the ID-free `catalog-v1` and `buyers-v1` definitions, and the workload clients use only service-issued IDs. **2026-10-07:** 559 unit/client/startup tests pass with 100% line and branch coverage. [Evidence](CHANGELOG.md#step-35) |
| 3.6 Seller and product API on primaries | 0.4, 2.3, 3.2, 3.4, 3.5 | Done | Product serves the seller/product API of [product API design](shardshop-product/ARCHITECTURE.md#product-api) on primaries, with the fix-forward catalog migration V2. **2026-10-07:** 163 OpenAPI-validated provider responses pass, and the lab applied V2 on all primaries. [Evidence](CHANGELOG.md#step-36) |
| 3.7 Product seeder application | 3.5, 3.6 | Done | Repeated seeding recovers the same service-issued IDs for all 150 sellers and 300 products, and completion requires successful verification reads. **2026-10-07:** `verify-product-seeder.py` passes on four disposable shards. [Evidence](CHANGELOG.md#step-37) |
| 3.8 Product reader application | 3.5, 3.6 | Done | The reader sends bounded read load and reports request, latency and error metrics, as the [HTTP connection policy](shardshop-workload/shardshop-product-reader/ARCHITECTURE.md#http-connection-policy) defines. **2026-10-08:** `verify-product-reader.py` passes twice on four disposable shards. [Evidence](CHANGELOG.md#step-38) |
| 3.9 Product deployment and seeding gate | 3.7, 3.8 | Done | Product, the seeder Job and the reader run on the lab. The reader owns its [lifecycle](shardshop-workload/ARCHITECTURE.md#workload-lifecycle) in Java: it sends load only after it resolves the complete `catalog-v1` dataset through product, and it stops with an error after a bounded wait. `deploy.sh` only renders and applies the manifests; it does not wait for the Job or scale the reader. Maven tests show that the reader sends no load while the dataset is incomplete. Repeated runs pass scenario 1 for the reader and scenario 3 for primary reads. Application images share a Dockerfile and OS inventory/advisory qualification. Step 3.16 moves build ownership to Skaffold; step 4.9 extends that configuration. Product counts the requests of each pod with the Micrometer Prometheus extension ([§4](#quarkus-framework-migration)). Do not tune the reader's connection rotation, because step 6.8 replaces the HTTP/1.1 client. **2026-10-11:** `CatalogWaitTest` shows the wait of the reader, and `verify-product-workload.py` passes on the lab with a `deploy.sh` that only renders and applies. [Evidence](CHANGELOG.md#step-39) |
| 3.10 Maven integration tests with Testcontainers | 3.6, 3.7, 3.8, 3.9 | Done | Qualify Testcontainers and its pinned images in VERSIONS.md and the lock. Maven integration tests in the `integration` profile run product, the seeder and the reader against real PostgreSQL containers, and they apply the catalog migrations with the Flyway Java API. They cover the cases of `verify-product-provider.py`, `verify-product-transport.py`, `verify-product-seeder.py`, `verify-product-reader.py` and `verify-catalog-migration.py` that the architecture needs. Then delete these five scripts and `verify-catalog-lab.sh`, because Flyway validates the applied migrations on the lab. Their CHANGELOG evidence stays. **2026-10-12:** the 27 Cucumber scenarios of `shardshop-testing` replace the six scripts and pass in the full `-Pintegration,audit` build. [Evidence](CHANGELOG.md#step-310) |
| 3.11 Generator IDs in the application JVM | 3.4, 3.9, 3.10 | Done | Product, and order from step 4.1, reserve a fresh generator ID with Java code in their own JVM at every start, also after a container restart. The Kubernetes API calls use Apache HttpClient 5, which the workloads already use. The registry, compare-and-set, retention and RBAC rules of [ARCHITECTURE](shardshop-core/idgen/ARCHITECTURE.md#generator-identity-across-processes-and-restarts) stay, and that section is updated. No kubectl binary, launcher process, init container, sidecar, separate pod or service takes part. The application images contain no kubectl: remove the kubectl stage, its VEX document, its image pin and its advisory records. Maven integration tests with Testcontainers cover concurrent reservations, restarts and registry failures against a real Kubernetes API, and they replace `verify-generator-allocation.sh`. The step 3.9 lab check shows a new ID after a pod replacement. **2026-10-13:** the 11 k3s scenarios of `GeneratorAllocation.feature` replace `verify-generator-allocation.sh`, and on the lab each replacement product pod reserves a new ID in its own JVM. [Evidence](CHANGELOG.md#step-311) |
| 3.12 Contract checks in Maven and one image script | 3.10 | Done | Each module that owns an OpenAPI document or a message schema checks it, its examples and its boundaries in a Maven unit test, as `verify-contracts.py` does. Then delete `verify-contracts.py` and `contract-validation-requirements.txt`. `build-application-images.sh` also builds, scans and loads the Flyway migration image on the OpenJDK runtime, and `build-migration-image.sh` is deleted. **2026-10-13:** 51 contract unit tests, `DatasetSourcesTest` and 4 `Contracts.feature` scenarios replace the Python checks, and on the lab `migrate.sh` ran on the reviewed migration image that `build-application-images.sh flyway` scanned and loaded. [Evidence](CHANGELOG.md#step-312) |
| 3.13 Build current sources and qualify images through up.sh | 3.12 | Done | Run the ShardShop-only `mvn -B -ntp -f microservices/shardshop/pom.xml clean install -DinstallAtEnd=true` from the script-resolved root; never build the repository root. Use successful current packages for Skaffold artifacts, preserving the pinned runtime, OS inventory, advisory/VEX gate and reviewed Flyway digest. Skaffold owns Docker recipes/artifact tags; Bash invokes Maven/Scout and verifies native kind delivery. Build or scan failure prevents the new application release. Prove source changes reach built images and failed builds cannot deploy old outputs. The implementation ownership changed in step 3.16; this evidence qualifies the earlier build stage. **2026-10-13:** `up.sh` passes the scoped build, scans and loads all four images on the lab; isolated source-change and failed-build checks prove current packages are used, and an unchanged rerun keeps the image IDs. [Evidence](CHANGELOG.md#step-313) |
| 3.14 Create, resume and reconcile the cluster through up.sh | 3.13 | Done | Use one invocation snapshot of kind/inventory/chart/values. Bash creates a missing cluster, starts stopped nodes, refreshes kindnet/DNS, creates missing credentials and bootstraps allocator identity only on a demonstrably fresh cluster. Helmfile syncs the operator and infrastructure releases; Kubernetes/CNPG reconcile them. Maven observes current-generation readiness, SQL connectivity, streaming quorum, topology and retained allocator safety. Preserve namespace/credential/allocator ownership outside Helm, plus PVCs/data/state. Reject changed membership/order/regions and storage, placement, bootstrap, replica or incompatible node changes. The implementation ownership changed in step 3.16; this evidence qualifies the earlier cluster lifecycle. **2026-10-13:** fresh setup, retained-node resume, supported resource/configuration changes and unsafe-change rejection pass with preserved data and allocator identity; the final focused whole-cluster resume reused previously qualified images after Scout became unavailable. [Evidence](CHANGELOG.md#step-314) |
| 3.15 Replace live verification scripts with Maven scenarios | 3.10, 3.11, 3.14 | Done | The opt-in `kind` Cucumber suite retains the step 3.14 invariant, admission, DNS and infrastructure readiness gates using the lifecycle invocation snapshot. Java suites observe safety/readiness and do not reconcile releases or compare generic Job specifications. `kind-migrations-idle` blocks overlapping Flyway work; publication checks wait for exact current generations. The default `kind-deployment` selection checks deployed routing, independent replication, worker placement/PVC identity, workload credential restrictions, matching catalog digests and 150 sellers/300 products, selecting the current seeder Job from reader Deployment metadata with legacy run-label fallback, traffic through both product pods within 120 seconds, and a fresh all-OK reader interval at 180 requests/s or more. Explicit `kind-drill` selection checks repeated seeding with identical saved IDs, fresh generator allocation after product pod replacement, standby replacement, quorum availability/blocking and synchronized WAL slots. Bounded calls, structured reports, an owner-scoped shared lock and recovery hooks restore temporary changes after failure or ordinary JVM termination without rewinding allocator state. Normal builds and the Testcontainers `integration` profile remain separate. **2026-10-13:** 877 affected-module unit/startup tests and 28 packaged routing checks pass; all three default live scenarios pass before and after both explicit drills. All ten PVC bindings and allocator identity are preserved; the product replacement advances `highWaterMark` from 65 to 66. The two old verification scripts are retired after equivalent live coverage. [Evidence](CHANGELOG.md#step-315--live-verification-completion) |
| 3.16 Migrate and deploy current image digests through up.sh | 3.15 | Done | Use Skaffold for packaged image builds and Helmfile for ordered operator, infrastructure, migration and application releases under the tool-ownership rules above. Explicitly adopt matching legacy resources once; preserve unmanaged namespace, credentials and allocator state. Verify all qualified images on every node before SQL. Gate migrations on retained topology, allocator safety and readiness; run weighted catalog-all-shards → ordering-all-shards → ledger hooks, then require current-generation publications before application/routing sync. Flyway validates database history on every invocation; Helm owns revision-named attempts, waits and cleanup. Reject overlapping migration Jobs, document interrupted Helm recovery, and retain committed SQL on failure. Rerun idempotent seeding while preserving unchanged application pod templates, generator allocations and catalog IDs. Product configuration checksums cover the complete rendered file. Prove unchanged convergence, configuration rollouts, failure blocking and safe retry. **2026-10-14:** two full native startup runs pass 1,034 unit/startup tests and 55 routing checks each; unchanged pod/data preservation, Flyway failure/recovery and A → B → A, configuration rollout/restoration and all three live deployment scenarios pass. [Evidence](CHANGELOG.md#step-316-native-tool-ownership). Earlier implementation evidence remains [historical](CHANGELOG.md#step-316). |
| 3.17 Complete up.sh with a deployment success gate | 3.15, 3.16 | Done | Add final deployment acceptance to the existing Skaffold build and Helmfile release workflow. Helm already waits for release resources and the current revision's seeder hook; use a declarative Helmfile phase hook to invoke Maven checks of desired generations/images, service readiness and reader catalog resolution. Invoke the step 3.15 deployment suite to verify routing, credential restrictions, traffic on both product pods within 120 seconds and a fresh all-OK reader interval at the existing accepted rate. Do not include pod deletion, standby fencing or repeated-release drills in normal startup. Preserve Java-owned catalog/load gating and leave the reader running. Keep every phase bounded, propagate failures and report bounded diagnostics without secrets; never report complete acceptance for a partially updated release. Record empty-lab startup, unchanged pod convergence with fresh no-op migration/idempotent seeder attempts, changed build/configuration, retained-data resume and failed migration/seeding/readiness. **2026-10-14:** native Helmfile acceptance passed fresh/repeated, changed-build/configuration and retained-data qualifications; migration, seeder, readiness and insufficient-traffic failures propagated, with two product replicas and one reader restored ([evidence](CHANGELOG.md#step-317)). |
| 3.18 Stop the entire cluster through down.sh | 3.14 | Done | `down.sh` stops every kind node of the ShardShop cluster, including control plane and workers, and verifies all of those containers are stopped, bounded by Docker's ten-second stop grace period. A missing or already stopped cluster succeeds without changes. Preserve volumes, Secrets, databases and generator history; do not delete the cluster or stop Docker Desktop or unrelated containers. Serialize `up.sh` and `down.sh` so concurrent lifecycle calls cannot race, and release the lock on failure or interruption. Verify shutdown while workloads run, repeated shutdown, missing-cluster shutdown and a subsequent `up.sh` that recovers the same data and allocator identity. **2026-10-15:** 30 lifecycle tests and live stop/restart pass; all ten PVC bindings, catalog IDs and allocator identity are retained. The requested `cluster-destroy.sh` also passes disposable-cluster deletion and repeat checks. [Evidence](CHANGELOG.md#step-318) **2026-10-18:** `down.sh` disables node restart policies with `docker update --restart=no` before `docker stop`; `cluster-destroy.sh` calls `kind delete cluster`, under the lifecycle lock. Live stop, disposable-cluster deletion, a `cluster-create.sh` resume and a following `up.sh` pass with the same data and allocator identity ([evidence](CHANGELOG.md#lifecycle-script-reduction)). |
| 3.19 Replace the offline dataset generator with Java tooling | 3.12 | Done | Replace `shardshop-workload/shardshop-datasets/scripts/generate-buyers.py` with a development-only Java generator invoked through Maven in `shardshop-datasets`; do not package it into services or add another script. Reproduce the three checked-in `buyers-v1` TSVs byte for byte from the retained source pools, offline and without generating entity IDs. Generate into `target` for verification so ordinary builds and `up.sh` never rewrite source fixtures. Document the explicit dataset regeneration command and update `SOURCES.md`, `source-manifest.json` and provenance tests. Delete the Python generator only after reproducibility and the module's existing dataset checks pass. **2026-10-15:** all 38 dataset tests and explicit offline Java generation pass; all three buyer TSVs reproduce byte for byte, source fixtures remain unchanged, and the generator is excluded from production JARs. [Evidence](CHANGELOG.md#step-319) |
| 3.20 Retire old scripts and qualify the explicit lifecycle workflow | 3.17, 3.18, 3.19 | Done | Delete the remaining `deploy.sh`, `migrate.sh`, `render.sh` and `shards.sh` after their replacements pass; step 3.15 already retired the two live verification scripts. Together with step 3.19, only `scripts/cluster-create.sh`, `scripts/up.sh`, `scripts/down.sh` and `scripts/cluster-destroy.sh` remain as maintained entry points anywhere under ShardShop, with `scripts/lib/lifecycle.sh` as their creation/startup implementation helper; do not hide the removed entry points in another directory or a task-runner wrapper. Update README, architecture, chart comments, artifact-lock metadata and active command references; preserve historical CHANGELOG evidence. Document creation, application deployment/restart, retained shutdown and destruction, separate Maven test/drill commands and explicit data-recovery procedures. Run the relevant module builds, Testcontainers scenarios, default `kind` deployment suite and an explicit `-Pkind -Dshardshop.kind.tags=kind-drill` qualification run, then prove clean bootstrap, changed-version deployment, unchanged-build application restart, full stop and retained-data restart using only the explicit lifecycle commands. Record exact commands and results in CHANGELOG. **2026-10-15:** Four lifecycle entry points remain (the later webpage-access command is specified by steps 4.11 and 4.13); full builds, 42 Testcontainers scenarios, default kind and both explicit drills pass. Clean creation, changed/unchanged deployment, stop/resume and teardown pass; cold-image DNS startup has a separate bounded allowance. The original lab is restored with its data and identities preserved. [Evidence](CHANGELOG.md#step-320) |

### Milestone 4: Complete order-to-ledger saga

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 4.1 Order validation and atomic acceptance | 2.5, 3.6, 3.11, 3.12 | Done | Order serves buyer creation, order allocation and order PUT/GET, as [order saga design](shardshop-order/ARCHITECTURE.md#order-creation-and-ledger-saga) and the order OpenAPI define. Fix-forward ordering migrations add the buyer person fields, creation keys and unit-cost snapshots, and V1 stays unchanged. **2026-10-16:** scenario 9 passes across 165 order unit/provider tests, 32 packaged startup checks and nine PostgreSQL scenarios, including blocked-catalog races, advisory-lock waits, concurrent requests without retries and atomic rollback. [Evidence](CHANGELOG.md#step-41) |
| 4.2 Stock reservation step | 2.5, 4.1 | Done | Order reserves and releases stock as the [stock reservation rules](shardshop-order/ARCHITECTURE.md#stock-reservation) define. **2026-10-16:** scenario 11 passes through seven PostgreSQL stock scenarios, including competing coordinators, partial compensation, catalog outages, reserve/release rollback and durable lease takeover. 208 order unit/provider tests and 35 packaged startup checks pass; V3 migrations preserve V1/V2. [Evidence](CHANGELOG.md#step-42) |
| 4.3 RabbitMQ topology and policies | 0.5, 1.1, 3.1 | Done | The persistent broker and its queues, policies, feature flags and limits match [broker design](infra/ARCHITECTURE.md#rabbitmq-and-delivery-reliability). Select a release inside its support window when the step starts, and record it in VERSIONS.md and the lock. The broker runs with an arbitrary user ID ([portability rule 5](#portability-beyond-kind)). **2026-10-16:** 192 testing-module tests, six broker scenarios and two retained kind provisioning runs pass; exact policies, retry/parking, backpressure, persistence and arbitrary-UID startup are verified. [Evidence](CHANGELOG.md#step-43) |
| 4.4 Order outbox CDC relay | 4.2, 4.3 | Done | Pin and qualify Debezium in VERSIONS.md. The relay obeys the [CDC rules](shardshop-order/ARCHITECTURE.md#cdc-from-shard-writes-to-the-ledger), and published `RecordOrder` envelopes match the order-owned message schema. The relay checks of scenario 6 pass. Steps 5.5 and 6.2 add its crash and failover cases. **2026-10-18:** Debezium 3.7.0.Final is qualified; fifteen relay scenarios and one accepted-order scenario pass, including snapshot handoff, idle restarts and WAL release, reconnects, ownership, and fail-closed slot, offset, history and WAL loss; 236 order unit/provider tests and 38 startup checks pass. [Evidence](CHANGELOG.md#step-44) |
| 4.5 Ledger command consumer | 2.6, 3.2, 4.3 | Done | The ledger consumer records decisions, replays duplicates and quarantines conflicts, as the [ledger design](shardshop-ledger/ARCHITECTURE.md#ledger-decisions-replay-identities-and-transport) defines, with the advisory-lock serialization. Commands with invalid IDs are rejected without requeue. **2026-10-18:** 12 consumer scenarios, 2 V2 migration scenarios and 39 ledger unit tests pass. They include replay after publication and cleanup, conflict quarantine, the five identity locks and the parking of commands with invalid IDs. [Evidence](CHANGELOG.md#step-45) |
| 4.6 Ledger result publisher | 4.5 | Done | The result relay publishes stored outcomes with the reserved IDs and the publication-attempt checks of [order saga design](shardshop-order/ARCHITECTURE.md#reconciliation-and-replayed-outcomes). Result envelopes match the ledger message schema. **2026-10-18:** six publisher scenarios, 12 consumer regressions, two migration scenarios, 44 ledger tests, 167 testing-module tests and 271 common tests pass; order and ledger share `ConfirmedPublisher` in common. They verify saved envelopes, mandatory routing, restarts, idle reconnects and stale-confirm protection across replay attempts. [Evidence](CHANGELOG.md#step-46) |
| 4.7 Order result consumer | 4.4, 4.6 | Done | Order applies results, releases stock after a cancellation, and quarantines absent or conflicting results, as [order saga design](shardshop-order/ARCHITECTURE.md#order-creation-and-ledger-saga) defines. Confirmation credits seller profit exactly once for each item, as the [profit rules](shardshop-product/ARCHITECTURE.md#seller-profit) define. Results with invalid IDs are rejected without requeue. **2026-10-19:** ten result/profit scenarios pass in the full suite of 110 scenarios, alongside 260 order unit/provider tests, 41 packaged startup checks, 42 ledger tests, 279 common tests and 167 testing-module tests. They cover durable replay identities, quarantine under the creation lock, cancellation release, signed cross-shard profit, unknown historical costs, rollback, outages and competing workers after an interrupted home mark. Ledger and order share `QueueConsumer` in common. [Evidence](CHANGELOG.md#step-47) |
| 4.8 Order producer application | 3.5, 4.1 | Done | The HTTP-only order producer creates the buyers, then allocates and submits orders, as the [producer design](shardshop-workload/shardshop-order-producer/ARCHITECTURE.md) and [datasets](shardshop-workload/shardshop-datasets/ARCHITECTURE.md) define. This includes cross-region, mixed-region and EUR-rejection orders. The producer keeps the returned IDs over retries and restarts, and it polls the order status within bounded deadlines. **2026-10-19:** 116 producer tests and two packaged producer scenarios pass, with the seeder/reader regressions, the six-application unit tests and 71 packaged startup checks. Acceptance covers all 3,000 buyers, catalog gating, hidden committed responses, deterministic restart replay without the order interval for finished orders, three-region USD/EUR orders, advancement only after terminal status, failure lines that name the stuck order, and one error for an invalid setting. [Evidence](CHANGELOG.md#step-48) |
| 4.9 Saga deployment and first end-to-end run | 3.20, 4.7, 4.8 | Done | Order, ledger and order-producer artifacts/resources use the existing Skaffold and Helmfile pipeline. The producer gates load on its seeded catalog and resumes the stable run. The `up.sh` saga gate creates its own buyer, seller, products and orders, and does not examine producer orders. Maven verifies terminal ledger/stock/profit effects, permanent rejection replay, duplicate delivery, two orders for the same stock and CDC resumption on another worker. At this step the lab had one combined order replica with one stock worker thread; step 4.14 supersedes that process layout with two HTTP replicas and one saga replica. Stock work remains serial, while step 4.2 tests competing coordinators. Physical-replication observers distinguish the new logical CDC senders. **2026-10-20:** `up.sh` passes its five final acceptance scenarios; the combined infrastructure and recovery suite passes all six scenarios, including scenarios 4, 5 and 11. No lifecycle entry point was added. The validation fixes (gate-owned orders, `kind-drills-idle`) also pass live application-release acceptance during step 4.10, after aligning the gate's HTTP client with the workloads' HTTP/1.1 transport. [Evidence](CHANGELOG.md#step-49), [later acceptance](CHANGELOG.md#step-410) |
| 4.10 Read-only regional product page | 4.9 | Done | Serve a read-only page from `shardshop-product` with a region dropdown for `US`, `EU` and `ASIA`. Show created products from the selected seller home region, including products outside the seeded datasets, with product name and ID, seller identity, initial stock, current stock, price per item with currency, and cumulative earnings per product. Earnings follow the [confirmed-profit rules](shardshop-product/ARCHITECTURE.md#seller-profit): sum applied profit credits from confirmed sales, preserve negative amounts and separate currencies, and count each order/product once. Extend the product-owned read API and OpenAPI contract with region filtering, bounded pagination and per-product earnings. Changing region or refreshing the page reloads the displayed values without changing data; provide loading, empty and error states. Verify region isolation across all shards in that region, pagination, stock reservation and cancellation updates, and eventual confirmed earnings without duplicate credits. Deploy through the existing application release, verify the page in a browser on the lab, and document access in README. **2026-10-20:** 269 product tests and 15 focused migration/browser/order/producer scenarios pass; `up.sh` passes all five final acceptance scenarios. Live browser checks cover all regions, pagination, refresh and displayed stock/earnings. [Evidence](CHANGELOG.md#step-410) |
| 4.11 Read-only regional order page | 4.10 | Done | Serve a read-only `/orders/` page from `shardshop-order`, following the product page's presentation and navigation patterns. A region dropdown selects `US`, `EU` or `ASIA`; region means the buyer's home region, and results include orders from every shard in that region, including orders outside the workload datasets. Show order ID, buyer full name, creation date/time with an explicit timezone, current status and cancellation reason when present, total with currency, and ordered product names and quantities. Buyer/product names expose hover, focus and click details with separate name/ID copy actions. Align region, product search and Refresh controls. By default list all orders in the selected region; an optional product filter accepts an exact canonical ID or a case-insensitive literal part of a saved product name, including purchases from sellers in other regions, with each order appearing once and retaining all items. Clearing the filter restores all orders. Support creation-date sorting (`ordering.orders.created_at`) through the clickable Created column header in both `ASC` and `DESC` for the full and product-filtered lists; default to `DESC` and use order ID in the same direction as a deterministic tie-breaker. Extend the order-owned read API and OpenAPI contract with combined region/product filtering, date sorting and bounded pagination; merge shard results into the requested global order before pagination. Preserve region, product filter, sort direction and page in the browser URL for refresh, copied links and history; changing a filter or sort starts at page 1. Provide loading, empty and error states, and fail the complete page on a required shard failure. Verify region isolation, cross-region product matching, exclusion of unrelated orders, both sort directions with and without a product filter, equal timestamps, pagination, invalid inputs and refresh without data changes. Deploy through the existing application release and verify filtering and sorting in a browser on the lab. Keep `scripts/up.sh` argument-free and dedicated to deployment. Use the separate, argument-free `scripts/webpages.sh` to make `/orders/` and `/products/` available together through distinct localhost ports, printing both URLs. Check and stop existing listeners on those ports before opening new tunnels. Keep page access independent of the deployment lock; report forwarding failures and clean up both forwards started by this invocation on exit or Ctrl+C without stopping the lab. Verify both pages are reachable through `webpages.sh` and that tunnel cleanup leaves workloads running. Create `shardshop-order/README.md` following the structure of `shardshop-product/README.md`, with service responsibilities, navigation links, contents, regional webpage and order-service sections. Document prerequisites, deployment through `up.sh`, access through `webpages.sh`, the page URL and ports, region/product filtering, date sorting, pagination, refresh and browser history, and tunnel lifecycle. Link the new README from the main ShardShop README and update the product README to reflect the shared page-access workflow. **2026-10-21:** 307 order tests, 19 startup-script tests, 10 JavaScript checks and 21 selected integration scenarios pass. `up.sh` passes all five final acceptance scenarios; live browser filtering/sorting, both page tunnels and interruption cleanup pass, and the retained lab returns to its original stopped state. **Browser refinement:** names and copyable details, aligned controls, Created-header sorting and literal name search are implemented; 19 JavaScript checks and five focused integration scenarios pass. The broader run has one existing outage-recovery failure that passes unchanged on rerun. [Evidence](CHANGELOG.md#step-411) |
| 4.12 Regional and country currency policy | 3.5, 3.6, 4.5, 4.8, 4.11 | Done | Restrict new product prices by the seller's home region/country only: **US: USD only; EU: GBP and EUR only; ASIA: China (`CN`) → CNY, Vietnam (`VN`) → VND, South Korea (`KR`) → KRW, and Japan (`JP`) → JPY.** The ASIA mapping covers the four countries already present in the datasets; reject unmapped countries and unsupported region/country/currency combinations, and require an explicit mapping when adding countries. GBP is allowed throughout the application's EU region as requested; this currency change does not itself expand the country datasets. Persist an immutable seller country in the product API/domain/database instead of discarding the TSV country field, and validate the seller's region/country combination. Buyer region/address country does not restrict purchasing currency. Use one authoritative policy, with service-owned enforcement independent of workload datasets. Reject disallowed new product prices before business writes with documented HTTP errors. Customers may buy from any seller region/country, and orders retain the purchased products' currency regardless of the buyer's home region/country. Preserve the existing single-currency rule: all items and the specified order currency must match; reject mixed-currency orders without exchange conversion or cross-currency totals. The previous workload requirement to combine US/EU/ASIA sellers in every order is superseded because their permitted product currencies differ; continue exercising purchases where buyer and seller belong to different regions. Update catalog definitions, seeder/reader verification, order currency selection and producer planning to generate valid country-aware products and deterministic same-currency orders, including cross-region purchases. Prefer home currencies in the default workload using the [regional mix](shardshop-workload/shardshop-order-producer/RUNBOOK.md), while keeping every supported currency available to every buyer region. Version changed dataset payloads and creation keys, update counts/digests, and preserve restart/idempotency behavior. Replace unconditional USD/EUR seller-profit initialization with policy-appropriate balances; permit all configured supported currencies in normal ledger/deployment configuration and remove the assumption that every EUR order must be rejected. Retain deterministic ledger rejection/replay coverage through an explicitly scoped test policy and update saga gates/drills to use valid regional fixtures. Qualify per-currency fractional precision and enforce it consistently for prices, unit costs, order totals, ledger entries and profit balances; keep exact decimal strings, reject invalid fractional amounts rather than silently rounding, and update both webpages' formatting. Provide a retained-data cutover using fix-forward migrations: backfill seller countries only from verified provenance, report unresolved/noncompliant catalog rows and prevent their use in new orders, and preserve historical orders, snapshots, amounts, currencies, profits, message identities and ledger decisions. Previously accepted orders and identical retries must remain recoverable under their saved snapshots; never relabel historical money, reset the lab or rewrite applied migrations. Document legacy rows in read views. Update ARCHITECTURE, service contracts, schemas/examples, module READMEs and deployment settings to replace the prior region/currency-independence and USD/EUR-only assumptions. Acceptance covers every allowed mapping and forbidden combination, arbitrary non-dataset entities, missing/mismatched seller countries, GBP and each ASIA currency end to end, precision boundaries, mixed-currency rejection, country-scoped ASIA product pricing, cross-region purchases in every supported currency (including a US buyer purchasing a Japanese JPY product), exact profit balances, deterministic workload restart, legacy cutover/idempotent replay, and product/order page display. Run the affected module tests and PostgreSQL/RabbitMQ integration scenarios, then deploy through `up.sh` and verify the regional product/order pages and updated saga acceptance on the retained lab. **2026-10-21:** Live product/order pages verify all regions and currencies; sampled historical money is unchanged. After the validation fixes (balanced producer traffic over all 201 products, a saga gate that changes no workload or ledger setting, an exact catalog count and tests for each policy copy), 1,659 unit/provider checks, 71 packaged startup checks and all 127 integration scenarios pass. Application revision 36 passes all five retained deployment gates and both saga drills, after the replacement of one product pod that a VM stall deadlocked in `CatalogDeadline` (a preexisting defect from step 3.6; the subsequent [source fix](CHANGELOG.md#http-deadline-lock-inversion-fix) also covers order). Both pages and the retained lab remain running. [Evidence](CHANGELOG.md#step-412-validation-fixes) |
| 4.13 Workload traffic-control webpages | 4.12 | Done | Serve a dedicated webpage and workload-owned control/status HTTP API from each of `shardshop-order-producer`, `shardshop-product-reader` and `shardshop-product-seeder`, following the existing product/order pages' presentation and navigation. Provide a numeric target-rate input, increase/decrease controls, Apply, Pause and Resume; changes affect only the selected workload and take effect without rebuilding, redeploying or restarting it. Label units explicitly: product read starts/second across all reader workers, new order starts/second for the producer, and outgoing HTTP requests/second for the seeder, including creation and verification. Show the effective target, achieved rate, total backend HTTP request/error counts and current lifecycle state, including waiting, running, pausing, paused, completed and failed where applicable. Distinguish new-order throughput from allocation, submission, polling, retries and startup traffic; preserve the producer's single in-flight order and show when that limit prevents reaching the target. Validate finite, nonnegative rates against documented upper bounds on the server; zero pauses new work, invalid updates leave the previous setting intact, and concurrent updates apply atomically. Retain bounded concurrency, HTTP deadlines and retry budgets, with no catch-up bursts after a rate change or resume. Pause drains the current bounded operation; the producer finishes observing its current order before pausing new allocations. Keep control state in the application so browser refresh/closure does not reset it; document that process restart restores configured defaults. Rate changes must preserve dataset versions, run names, ordinal progress, service-issued IDs, retry keys and currency selection. Preserve catalog startup gates and Java-owned workload lifecycle, without database credentials or Kubernetes API access. For the seeder, retain the default finite bootstrap Job and its deployment completion gate; add an explicit interactive mode of the same application that remains reachable while idle or completed, permits one operator-started finite seed run at a time, and supports live pacing and pause/resume. Repeated seed runs reuse the existing keys and payloads without duplicating entities or resetting stock/profits. Deploy the interactive mode through the existing application release and document its lifecycle and timeout behavior. Extend `scripts/webpages.sh` to expose all three control pages alongside `/products/` and `/orders/` on distinct localhost ports, print their URLs and clean up every owned tunnel on exit; keep `up.sh` dedicated to deployment. Update ARCHITECTURE, workload API contracts and READMEs for HTTP serving, controls, defaults, bounds and access. Acceptance includes deterministic pacing and API tests, packaged integration coverage for live increases/decreases, zero/pause/resume, invalid/concurrent updates, workload isolation, restart defaults and idempotent seeder reruns. Deploy through `up.sh`, retain existing deployment acceptance checks, and verify all three pages in a browser with measured backend traffic changes; restore configured workload settings after verification. **2026-10-22:** 63 control-library, 286 workload and 95 selected harness tests, 12 page-script checks and three new PostgreSQL scenarios pass; `up.sh` passes all five acceptance scenarios, and the browser changed producer, reader and interactive-seeder traffic with measured backend effects before it restored the defaults. [Evidence](CHANGELOG.md#step-413) |
| 4.14 Concurrent order writes and a separate saga process | 4.13 | Done | Split the order lifecycle into two parallel processes. **Process 1 (order write):** the order producer writes new orders through the existing order HTTP API: the allocation POST, then the order PUT, which commits the order, its items and its saga row and answers `202`. The producer does not wait for the saga before it starts the next order. It keeps a configurable, bounded number of submissions in flight, paced by the step 4.13 rate of new order starts per second; this replaces the step 4.13 rule of one order in flight. A separate, bounded observer reads the status of non-terminal orders until they are terminal; it never blocks a submission. A configurable maximum of non-terminal orders stops new submissions until the saga catches up. The producer control page shows this backpressure, the submissions in flight, the non-terminal backlog, the write latency and the saga latency. Ordinals stay dense and deterministic. Concurrency must not change allocation identities, retry keys, payloads or currency selection. A restart replays from ordinal zero with the same bounded concurrency, continues to observe non-terminal orders and counts recovered orders separately. Pause stops new submissions; the submissions in flight finish, and the observer continues. **Process 2 (saga):** move the CDC relay (the Debezium readers and the RabbitMQ command publication) and the RabbitMQ result consumer out of the order HTTP JVM into a separate Deployment of the order application, for example `shardshop-order-saga`. It has one replica and the `Recreate` strategy, because each shard slot has one reader. The stock-reservation and profit workers also move there, so the HTTP process runs no background saga loop and its write latency does not compete with that work. The order HTTP Deployment then has at least two replicas with a rolling update. Each JVM that creates IDs reserves its own generator slot. Use one image and select the process by configuration. Keep the schema ownership, the database and broker logins, the outbox/inbox contracts and the applied Flyway history; add no SQL functions or triggers. **Targets:** on the lab, the write path (allocation and PUT, measured by the producer and by order for each request) has a p95 below 100 ms at 10 new orders/s with concurrent submissions. Remove avoidable work from that path. At that rate, the saga keeps up without a growing backlog, and the status reports its end-to-end latency. Measure the sustained saga throughput and the order HTTP capacity (four database connections per shard and 12 total per JVM; acquisition waits at most one second before `503`). Set and document the upper bounds of the producer rate and concurrency from these measurements, and revise the current maximum of 100 orders/s. Stock is not replenished: document how fast the tested rates use the catalog stock, and keep the default rate. **Verification:** unit tests for the bounded submission window, the observer, backpressure, Pause and replay. Packaged integration scenarios for concurrent submissions without duplicate allocations or oversold stock, idempotent retries under concurrency, a restart with non-terminal orders, a saga-process restart while writes continue, an HTTP rollout while the relay keeps its slots, and exactly one ledger decision for each order. Update the Helm chart, the release observer and the kind gates and drills (CDC relocation; scenarios 4, 5 and 11) for the new Deployment, and run them through `up.sh`. Measure the write latency, saga latency and backlog on the lab, then restore the configured workload settings. Update ARCHITECTURE (order lifecycle, workload lifecycle, runtime topology), the contracts and the READMEs. **Feasibility (2026-10-22, read-only lab data):** in 2,712 confirmed producer orders, the time from the allocation start to the order-write start was p50 12 ms and p95 31 ms, and the quorum standbys flushed in 0.7–6 ms. The saga after the write took p50 946 ms and p95 1,766 ms: stock reservation 178 ms, CDC publication 568 ms, and ledger decision and result return 257 ms (p50). Thus the write path already fits in 100 ms; only the wait for the saga limits the producer to approximately 0.8 orders/s. **Historical sizing estimate before measurement (2026-10-22; superseded by the measured results below):** the sustained maximum is approximately **30–40 new orders/s**, with a write-path p95 of approximately **50–60 ms**. The slowest stage of process 2 sets the sustained rate, because the bound on non-terminal orders stops submissions when the saga falls behind. That stage is the stock worker: one thread processes at most 8 orders per shard in each poll, then waits 250 ms; with 15–25 ms per order (today the minimum time from the write to the reservation transaction was 11–15 ms), it reserves approximately 28–39 orders/s. The next limits are higher: each CDC relay sends one confirmed message at a time per shard (approximately 100–200/s per shard), and the ledger command consumer and the order result consumer each process one message at a time (prefetch 1, approximately 65–200/s). At 30–40 orders/s, one order JVM has on average about one write in flight, far below its 12 concurrent database phases, so the write p95 stays near the low-load value (31 ms to the start of the order write, plus the PUT transaction). Four producer submissions in flight are sufficient. Short bursts can reach approximately 200 orders/s until the bound on non-terminal orders stops them; then the write p95 approaches 100 ms. Near the stock-worker limit, the saga p95 grows to several seconds; approximately 25–30 orders/s keeps it near 2–3 s. The stock worker settings permit at most a batch of 100 and a 100 ms poll; with one thread, that raises the limit only to approximately 40–65 orders/s. A higher rate needs parallel stock workers and consumers, which this step does not include. Record the measured maximum sustained rate and its write p95; a result below 25 orders/s needs an explanation. **2026-10-23:** implemented bounded concurrent submission and observation, two HTTP replicas and one saga process. Final `up.sh` passes 1,863 unit/startup checks and five acceptance scenarios; the three recovery drills and 17 final CDC/process scenarios pass. At target 10/s, measured throughput is 9.78 new orders/s with final rolling write p95 91.42 ms and no growing backlog. Target 30/s reaches 29.45/s with write p95 93.73 ms and bounded order/profit/outbox backlogs; aggregate target 40/s is backpressured, so the operating cap is 30/s and default remains 0.5/s. Allocation bursts and controlled pool saturation verify HTTP capacity and bounded `503` behavior. Results distinguish finite-stock cancellations and host contention. [Evidence and commands](CHANGELOG.md#step-414). |
| 4.15 Architecture overview, module design documents and sustainable capacity | 4.14 | Done | Simplify the root [ARCHITECTURE.md](ARCHITECTURE.md) into a concise system-design overview, using the user's Anki system-design material as the presentation reference. Keep only **top-level architecture:** the system's purpose, component responsibilities and boundaries, readable Mermaid diagrams and the main read/write/saga flows; **runtime topology:** pod counts and replica roles for the current kind deployment, separating application pods, shard primaries/standbys, ledger database, broker, operators/system pods and transient Jobs, with clear subtotals and counting scope; **sharding:** regional placement, seller/buyer ownership, product/order placement, ID-based routing, cross-shard interactions and replication/consistency at a conceptual level; and **performance:** maximum sustainable full-order write and product-read throughput, total and per shard, with latency, bottlenecks and links to dated evidence. Establish sustainable rates with bounded, repeatable lab measurements, reusing valid step 4.14 evidence and measuring missing read capacity. Define the latency/error and backlog criteria, warmup and observation duration; include stock, profit and outbox progress when judging sustained order throughput. Distinguish configured caps, short bursts, highest verified sustainable rates and a demonstrated saturation limit; never present the 10,000 reads/s control bound or an allocation-only burst as complete-system capacity. Label per-shard figures as measured or derived from the actual traffic mix; record hardware, topology, concurrency, concurrent read/write load and finite-stock cancellations in the detailed evidence. If a maximum cannot be established, state the verified lower bound and concrete measurement limitation. **Move deep details** into the owning service, core-library, workload, database, infrastructure or testing directory's own `ARCHITECTURE.md`: algorithms, schemas and wire contracts, transaction/locking rules, state machines, CDC/retry/recovery/retention behavior, configuration details and verification procedures. Preserve the existing guarantees and distinguish implemented behavior from planned features. Keep one authoritative home for each topic, with links from the overview and module READMEs; retain operational commands in the appropriate runbooks and historical evidence in CHANGELOG. **Acceptance:** the root document contains only the four high-level areas and navigation to detailed documents; pod counts match Helm/inventory; sharding and performance claims match implementation and evidence; all moved content has an owner, and all repository links, anchors, plan references and scenario references are updated or preserved without losing requirements. This step reorganizes documentation and measures capacity; it does not change runtime behavior, deployment defaults or applied migrations. Restore temporary workload settings and the lab's prior running state after measurements. **2026-10-23:** the overview now uses Mermaid and links to 16 owning architecture documents; all eleven acceptance requirements are retained. Helm and live inventory agree on 20 ShardShop pods and 36 total steady pods. Reused write evidence establishes 29.45 new orders/s (about 9.82 per buyer shard, derived); the bounded read probe qualifies 963.16 reads/s at target 1,000 with measured regional rates and p95 6–10 ms. Higher read targets undersupplied the requested rate, so the database maximum remains unestablished. Controls and the stopped lab state are restored. [Evidence and commands](CHANGELOG.md#step-415). |

### Milestone 5: Recovery, retention, and backpressure

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 5.1 Durable saga reconciliation | 4.14 | Done | Implement the [persisted reconciliation schedule](shardshop-order/ARCHITECTURE.md#reconciliation-schedule) in `shardshop-order-saga`; HTTP replicas run no background loops. Reuse the existing saga columns and permanent replay identities. Under the saga lock, re-check state, eligibility and the five-attempt budget; reuse an unpublished command without consuming another attempt. Replay preserves logical identity and the immutable snapshot. Injected-clock tests cover the 60/120/240/300/300-second waits, exhausted-budget alerts, restarts and removal of eligible published outbox rows without a sixth automatic enqueue. Lost results recover through stored ledger outcomes. Production transport cleanup belongs to 5.2. **2026-10-24:** scoped verification and 40 packaged recovery/CDC/stock scenarios passed, including all four reconciliation cases; see [evidence](CHANGELOG.md#step-51). |
| 5.2 Safe transport cleanup | 5.1 | Done | Implement the [retention rules](database/ARCHITECTURE.md#retention). Order outbox cleanup requires both confirmed publication and a durable CDC checkpoint past the insert. Preserve permanent business/replay identities, reconciliation counters, reservation history and closed guards, unpublished messages and unresolved quarantine. Cleanup deletes never become commands. Verify duplicate-command/result recovery after transport rows are removed, without duplicate stock, ledger or profit effects. **2026-10-24:** scoped verification and all 18 selected cleanup, migration, publisher and reconciliation scenarios passed, including exact replay after cleanup and concurrent rearm preservation; see [evidence](CHANGELOG.md#step-52). |
| 5.3 Manual reconciliation reset | 5.2 | Done | Implement the [operator reset](shardshop-order/ARCHITECTURE.md#reconciliation-schedule) for selected `PENDING_LEDGER` sagas: set attempts to zero and next eligibility to the current time under the order advisory and saga row locks. Define the operator entry point and least-privilege database login through the existing tooling boundaries. Repeated reset is safe, terminal decisions remain unchanged, and replay works after transport cleanup. The reset part of [scenario 7](shardshop-testing/ARCHITECTURE.md#scenario-7) passes. **2026-10-24:** native `psql` and a separate login restricted to the two retry columns pass reset, terminal no-op, contention, permission and replay checks. The original live operator check used a fresh lab. **2026-10-25:** the isolated pre-5.3 lab also passes current `cluster-create.sh` then `up.sh`, retaining 6,408 original row hashes and resource/database/allocator identities; the restricted reset, once-only replay and terminal no-op pass ([6.0 evidence](CHANGELOG.md#step-60)). See [step 5.3 evidence](CHANGELOG.md#step-53) and the [operator procedure](shardshop-order/RUNBOOK.md#manual-reconciliation-reset). |
| 5.4 Retry and capacity drills | 5.3 | Done | Complete the retry and capacity drills of [scenario 7](shardshop-testing/ARCHITECTURE.md#scenario-7) on the pinned broker: measure retry counts/timing and parking; hold a shard unavailable beyond the retry window and recover through DLQ plus reconciliation. Separately unbind and fill a DLQ while its broker/source stay running; prove retained-source pressure and negative confirms leave outboxes pending, then restore the binding or capacity and prove progress. Restore temporary settings through the existing drill guard. **2026-10-24:** both pinned-broker timing cases park on the sixth rejection after about 15.2 seconds. The shard-outage case and all four unbound/full DLQ cases pass, including same-outbox progress and exactly-once business effects. Seven final infrastructure/deployment/idle gates pass; temporary settings and owned diagnostics are cleaned up, and the prior running lab state is preserved. [Evidence and commands](CHANGELOG.md#step-54). |
| 5.5 Crash recovery and observability | 5.4 | Done | Complete [scenario 6](shardshop-testing/ARCHITECTURE.md#scenario-6), excluding promotion/failover owned by 6.2. Extend the existing saga-process drills from 4.14: crashes around database commit, broker confirmation, consumer acknowledgement and CDC checkpoints recover without duplicate effects while HTTP writes continue; an HTTP rollout preserves the saga and its slot ownership. Distinguish ordinary reconnect recovery from explicit recovery after slot/offset/WAL loss. Metrics expose stalled outboxes/CDC, pending or exhausted sagas, quarantine, queue capacity and pool/replication pressure. **2026-10-24:** 1,845 unit/provider checks, 75 packaged startup checks and 26 selected recovery scenarios pass. The qualified deployment passes all six release/deployment cases, all four explicit saga/observability scenarios and nine final infrastructure/release/idle gates. Unused-pool metric and migration-image reproducibility regressions are fixed; image advisory gates pass unchanged. The lab is returned to its original stopped state with retained data. [Evidence and commands](CHANGELOG.md#step-55). |

### Milestone 6: Read profiles, failover, restore, and operation

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 6.0 Application maintenance and retained-upgrade evidence | 5.5 | Done | Complete the October 28 component review with support/advisory/artifact evidence, including Node.js, and record RabbitMQ successor planning by November 2 with its January 15 qualification deadline. Retain the application/build patches, safe isolated-lab tooling and completed pre-5.3 data/operator proof. **Scope exclusion:** keep CNPG operator/chart and managed PostgreSQL image/configuration at the retained baseline; no operator upgrade, foundation reconciliation, database pod replacement, fencing, failover or new upgrade rehearsal belongs to 6.0. Qualify application maintenance through the passing release/recovery evidence and final kind gates; the user explicitly deferred all capacity qualification to 6.5/6.6/6.7. Reuse valid prior results; preserve failed runs and unresolved advisory dispositions. **2026-10-25:** source maintenance and the historical upgrade proof are implemented; one normal application release now passes (1,875 unit/provider checks, 75 startup checks, seven scan gates and six live release cases); both application recovery drills also pass. A corrected single 10/s capture met duration/sample eligibility (120.058077s, 1,177 post-warmup writes) but write p95 133.783 ms exceeded <100 ms. Both HTTP write-metrics endpoints were captured. The user deferred 10/s latency to 6.5, all 30/s writes to 6.6 and 1,000/s reads to 6.7. The incomplete 30/s attempt and failed reader qualification remain evidence, not current capacity claims. Final kind gates passed nine cases (0 failures/errors/skips, 108.2s), finishing 2026-10-25T19:18:48+04; wrapper exit/restoration 0 verified original stopped nodes, IDs/images/volumes/kubeconfig and no owned labs. 6.0 is Done under this explicit reduced scope; capacity remains unqualified. The user accepted the saved JRE, K3s and Flyway-driver findings for this local lab through October 28 without changing scan gates. CNPG successor availability is not a 6.0 acceptance condition. See the [bounded finish proposal](#minimum-completion-of-60) and [evidence](CHANGELOG.md#step-60). |
| 6.1 Strict replica-read drill | 6.0, 3.9, 4.13 | Done | Deploy product's implemented strict replica profile on the lab and test it with the reader. Add the limited `404` retry for seeder verification reads required by the [seeder contract](shardshop-workload/shardshop-product-seeder/ARCHITECTURE.md); startup lookups already wait. Complete the read checks of scenarios [3](shardshop-testing/ARCHITECTURE.md#scenario-3) and [8](shardshop-testing/ARCHITECTURE.md#scenario-8): controlled lag, one/both unavailable standbys, hung queries and bounded `503` without primary fallback. Before mutation, the drill guard captures all profile sources (ConfigMap, environment and JVM overrides) and registers their exact restoration. Restore the primary profile and saved workload controls, wait for all product pods to use the restored configuration, and pass the explicit `kind-deployment` primary-profile assertion before releasing the drill lock. **2026-10-26:** 1,904 unit/provider tests, 75 packaged startup checks and the 339.5s live drill pass. Controlled lag, one/both fenced standbys and hung queries meet the contract, including per-pod `503` below four seconds. Exact profile/control restoration and six final gates pass; the retained lab is stopped. [Evidence](CHANGELOG.md#step-61) |
| 6.2 Synchronous failover drills | 6.0, 5.5, 6.1 | Done | Complete the failover parts of scenarios [6](shardshop-testing/ARCHITECTURE.md#scenario-6) and [8](shardshop-testing/ARCHITECTURE.md#scenario-8) for real switchover and primary-failure promotion. Prove acknowledged saga commits survive and CDC continues from synchronized logical slots; reconnecting to `-rw` alone is insufficient. Verify CDC pauses while either standby is absent even when quorum writes continue. The former primary rejoins safely, no shard has two writable primaries, and an unconfirmed survivor is not automatically promoted after losing the primary and another standby. The October 26 [user decision](VERSIONS.md#step-62-existing-version-decision--2026-10-26) clears the maintenance gate only for qualification on pinned CNPG 1.30.1/chart 0.29.1 despite its saved findings, after verifying 1.30.1 is latest GA. The November 30 maintenance obligation, scan gates, support/advisory deadlines and historical 6.0 exclusions remain. Follow the failover contract of the explicitly qualified operator version and update the [1.30 reference](https://cloudnative-pg.io/docs/1.30/failover/) if that version changes. **2026-10-26:** 64 focused checks and the complete 743.7-second live drill pass, including both promotion cases, acknowledged saga/CDC continuity, the full 180-second unsafe-survivor window and fresh post-recovery orders. Automatic cleanup and all eight final gates pass. The restoration audit confirms original cluster identities/specifications/primaries/fences, PVCs/backing volumes, deployment specifications, routing/profile configuration and saved controls; owned finalizers and lock are absent, and the retained lab is stopped. [Evidence and commands](CHANGELOG.md#step-62). |
| 6.3 Coordinated backup and isolated restore | 5.3, 6.2 | Done | Implement the [coordinated backup policy](#7-replication-and-backup-policy) with artifacts outside kind and isolated restore targets. Retain schema/routing metadata, permanent outcomes, quarantine, replay identities and the latest allocator high-water mark. Quiesce writes and drain saga/relay work for the baseline. Logical dumps contain CDC offsets but no slot: require explicit CDC recovery before restored traffic, never silently skip retained commands. Checks prove schema/routing compatibility and order/saga/ledger consistency; older-backup orphan auditing and repair belong to 6.4. **2026-10-26:** 48 focused checks pass. Live coordinated backup (103.6s), isolated restore/CDC recovery (365.4s), final source gates including the successful focused reader recheck, allocator refresh and independent restoration audits pass. All four retained source nodes are stopped; owned restore resources are removed. Earlier failures remain recorded. [Evidence](CHANGELOG.md#step-63). |
| 6.4 Restore audit and recovery | 6.3 | Done | Implement the [operator audit and recovery](shardshop-order/ARCHITECTURE.md#operator-audit-planned) required by [scenario 8](shardshop-testing/ARCHITECTURE.md#scenario-8). Pause order writes; compare exported permanent ledger decisions with all primary orders by ID/fingerprint, quarantine missing/mismatched pairs and list orphan stock reservations. Recover original order/saga identities before replay; clear quarantine only after identity/outcome agreement. Retain unrecoverable ledger history and an unresolved incident. Verify detection after an older restore even when results were already acknowledged. This step also owns the optional asynchronous-loss drill. **2026-10-26:** 60 focused checks, the repeated native schema restore and the complete older-dump audit/recovery drill pass. The full run (458.471s) proves pre-delivery detection, HTTP quarantine blocking, original-record repair and once-only replay with unchanged catalog effects; unrecoverable history stays unresolved. Automatic and independent cleanup/input audits pass; the retained source lab remains stopped and unchanged. The optional physical asynchronous-loss profile is unqualified. [Evidence](CHANGELOG.md#step-64). |
| 6.5 Order-write p95 below 100 ms | 6.0, 4.14, 5.5 | Done | **2026-10-28 acceptance:** 1,201 accepted writes over 120.031668s (10.0057/s), final rolling write p95 **38.466 ms** and saga p95 1.533s; concurrent reads near 200/s. All sample, delivery, error, stock, backlog/drain and restoration criteria pass. [Closure evidence](CHANGELOG.md#steps-65-and-66--acceptance-closure--2026-10-28). Preserve and diagnose the earlier **133.783 ms** write-p95 failure; the 2026-10-28 local matrix now passes at **38.466 ms** (see latest measurement above), without a causal serial/parallel comparison. The user deferred write-latency qualification from 6.0 to this step; reuse passing 6.0 build, release and application drills while their inputs remain valid. **Acceptance:** the producer's final warmed rolling p95 for new orders, from allocation start through successful PUT acceptance, is **<100 ms at target 10 new orders/s**, with concurrent product reads at 200/s, no new write errors/deadline failures, no backpressure and bounded order/profit/outbox backlogs that drain after writes stop. Saga completion latency is reported separately. Keep the existing two HTTP replicas, one saga process, four submission workers, two observers and 100-order bound. Follow the [capacity protocol](shardshop-testing/ARCHITECTURE.md#capacity-measurement): 20s warmup, predetermined 120s monotonic observation at 10/s and >=1,024 post-warmup write samples; do not extend a run until it passes. Compare equivalent stock, cancellation mix, host resources, JVM age and collection overhead; change one suspected factor per bounded experiment. Warmup, collector overhead and background database work are hypotheses, not established causes. Capture both HTTP write-metrics endpoints without adding their p95s or substituting them for the producer metric. Make the smallest evidence-supported change and run its relevant regression checks; retain failed evidence and checkpoint before further costly work. Preserve durability, idempotency and the 100 ms limit; CNPG/managed PostgreSQL changes remain excluded. Record exact commands, artifacts and results in CHANGELOG and update the performance overview. Restore saved controls, producer configuration and the lab's prior state. Passing 6.5 is not a 6.0 completion condition. Full performance qualification also requires the separate 30/s acceptance in 6.6. |
| 6.6 Sustained 30 new orders/s | 6.0, 6.5, 4.14 | Done | **2026-10-28 acceptance:** 2,643 accepted writes over 90.039492s (**29.3538/s**, above 28.5/s), final rolling write p95 **85.702 ms** and saga p95 2.228s; concurrent reads near 200/s. No new errors, cancellations, recoveries or observed backpressure; stock remained positive and bounded backlogs drained. Controls and retained state restored. [Closure evidence](CHANGELOG.md#steps-65-and-66--acceptance-closure--2026-10-28). At the user's explicit request, own all 30/s write qualification deferred from 6.0 after the incomplete backpressured attempt (987 new orders in 40.044426s, ~24.65/s; not a sustained result or maximum). Diagnose and restore sustained target 30 new orders/s, delivering at least 95% of target (28.5 new accepted orders/s) over the observation window, with concurrent product reads at 200/s, no write errors/deadline failures, no backpressure and bounded pending-order/profit-intent/command-outbox backlogs that drain after writes stop. Control and record initial/final stock, selection, cancellation mix and background work; exhausted stock must not bypass saga/ledger work. Final full-performance acceptance also requires producer rolling write p95 **<100 ms at 30/s**, measured separately from saga completion. Keep two HTTP replicas, one saga process, four submission workers, two observers and the 100-order bound unless a separately agreed scope changes them. Use 20s warmup, a predetermined monotonic observation >=90s and >=1,024 post-warmup new-write samples; never extend until passing. Preserve the valid 10/s 133.783 ms failure and all failed/incomplete evidence; 154.613 ms from the short 30/s capture is diagnostic only. Use bounded hypothesis-driven experiments and relevant regression checks. Preserve durability/idempotency and exclude CNPG/managed PostgreSQL changes. Restore saved controls/template and prior lab state, and record commands/artifacts/results. Passing 6.6 is not a 6.0 completion condition. |
| 6.7 Qualify 1,000 product reads/s | 6.0, 4.15 | Planned | At the user's explicit request, own read qualification deferred from 6.0. Target 1,000 reads/s on the primary profile with two product pods, 16 reader workers and at most 16 HTTP connections. Pause the producer, drain submissions/non-terminal and background backlogs, and keep the interactive seeder idle. After at least 30s warmup, require three complete eligible non-overlapping 30s windows within a bounded 150s phase, ignoring reports ending less than 60s after the rate update. Each window must deliver >=950 reads/s (95% of target), only OK outcomes and client p95 <100 ms. Preserve the failed 948.2/s window and later 955.8/s window (p95 10/9 ms, all OK); the second does not waive the first failure or missing third window. This is unqualified evidence, not a server-capacity limit. Use bounded hypothesis-driven investigation; do not repeat until passing or weaken assertions. Record hardware, workload controls and background activity, restore saved controls and prior lab state, and retain commands/results in CHANGELOG. CNPG/managed PostgreSQL changes remain excluded. Passing 6.7 is not a 6.0 completion condition. |
| 6.8 HTTP/2 transport | 6.4, 6.6, 6.7 | Planned | All workload calls to product and order use HTTP/2 over cleartext (h2c) with prior knowledge. Qualify a request-level balancer, because a Kubernetes Service balances only TCP connections; select and record current versions, support, resources and verified charts from the [candidate review](VERSIONS.md#step-68-request-balancer-candidates). Define enrollment explicitly: only product and order HTTP pods, reader, producer, interactive seeder and bootstrap seeder participate. Do not enroll the whole `shardshop` namespace; keep the ten PostgreSQL pods, RabbitMQ, order saga, ledger and Flyway Jobs outside the mesh. Prove actual HTTP, SQL, replication and AMQP paths from deployed configuration and traffic. Application namespaces keep `restricted` Pod Security; qualify proxy UID/GID and any privileged CNI/node components separately under [rule 3](#5-local-infrastructure-details), without assuming sidecar UID 1337 applies to ambient components. The workloads change to an HTTP/2 client, because the classic Apache API supports only HTTP/1.1; verify Quarkus h2c prior knowledge. Update the [HTTP connection policy](shardshop-workload/shardshop-product-reader/ARCHITECTURE.md#http-connection-policy) and scenario 1 while preserving deadlines, bounded `503`, startup checks and metrics. Re-run 3.9, 4.14, 5.4, 5.5 and 6.1; also re-run any completed 6.2–6.4 drills on the final proxy topology. Compare HTTP/1.1 and HTTP/2 using the [capacity protocol](shardshop-testing/ARCHITECTURE.md#capacity-measurement) and the equal-stock procedure below. Update measured capacity and pod counts. |
| 6.9 Complete demo and acceptance run | 6.4, 6.8 | Planned | Verify the README and owning runbooks on one disposable fresh lab, preserving the retained lab. Reuse and document the owned-cluster/private-kubeconfig test configuration qualified in 6.0; keep the default `shardshop`/`kind-shardshop` workflow unchanged. Stop the retained lab before creating the uniquely named owned four-node lab; never run both simultaneously. Register cleanup before creation, delete only the disposable lab on every exit, verify its containers/context are gone, and restore the retained lab's prior running state. Do not run the fixed-name destruction recipe against retained data. All eleven architecture scenarios have commands and passing evidence, including full Snowflake lifecycle checks. Every installed component is inside its support window. |

Step 6.8 must give both transport measurements identical initial stock per product,
catalog definitions, workload selection/quantities, warmup, duration and concurrency.
Use sequential disposable labs initialized from the same baseline under instruction 4,
or implement two fresh catalog namespaces/versions with equivalent definitions and
verified stock through the public seeding API. Merely changing the producer run name
or rerunning the existing seeder does not replenish stock. Drain prior saga work,
record starting/ending stock and confirmed/cancelled counts, and reject comparisons
whose stock-cancellation mix differs. Preserve retained data; never refill stock by SQL.
The present dataset implementation must gain the selected isolation mechanism before
this comparison can run.

### Minimum completion of 6.0

6.0 is **Done under the user's explicit reduced local-maintenance scope**.
The final `kind-infrastructure | kind-deployment | kind-release | kind-drills-idle`
gates passed nine cases with zero failures/errors/skips in 108.2s, finishing
2026-10-25T19:18:48+04. The approved final-gate wrapper exited 0; restoration
verified original node IDs/images/volumes/kubeconfig, stopped nodes and no owned
labs. No capacity rerun, application rebuild or recovery-drill repeat was needed.
See the exact command and artifacts in [evidence](CHANGELOG.md#step-60).

Reuse the passing release/recovery/gate evidence while its inputs remain valid.
The user deferred 10/s write latency to 6.5, sustained 30/s writes to 6.6 and
1,000/s reads to 6.7; all remain Planned with unchanged acceptance criteria.
Failed/incomplete performance evidence remains, and full performance readiness is
unqualified. Step 6.1 subsequently passed on October 26; see its separate
[evidence](CHANGELOG.md#step-61). Local advisory acceptance ends October 28;
scan gates and support deadlines remain unchanged. CNPG/operator, managed
PostgreSQL and further database recovery/rehearsal were excluded from 6.0;
the October 26 decision permits 6.2 qualification on the existing CNPG pin,
while the separate November 30 maintenance obligation remains.

### Milestone 7: AWS deployment (optional, advanced)

This track starts only after step 6.9 passes locally. It adds a cloud target
without changing local behavior: every earlier step must still run and pass on
kind. Qualify each new tool, provider, and service under [VERSIONS.md](VERSIONS.md)
before use, and record costs and teardown commands.

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 7.1 Terraform AWS foundation | 6.9 | Planned | `infra/terraform/` provisions a VPC across three availability zones, an EKS cluster on a Kubernetes minor inside CNPG's support matrix, workers spread across the zones, ECR repositories, an encrypted S3 bucket for backups, and least-privilege workload IAM. State lives in a locked remote backend, and `terraform destroy` removes everything. |
| 7.2 EKS values and operators | 7.1 | Planned | The same vendored CloudNativePG chart and `values.yaml` install the operator on EKS; `infra/helm/shardshop/eks-values.yaml` renders the shared chart and inventory with gp3 storage and one instance of each shard per zone. Preserve the separate order HTTP and saga Deployments, process modes and rollout strategies from 4.14; the step 1.3 and 2.1 topology drills pass on EKS. |
| 7.3 Cloud durability | 7.2 | Planned | Every shard and the ledger back up to S3 with continuous WAL archiving and pass a point-in-time restore; RabbitMQ runs three brokers with three-member quorum queues; runtime secrets come from AWS Secrets Manager. The local profile keeps its single broker and logical dumps. |
| 7.4 GitOps delivery and cloud acceptance | 7.3 | Planned | Argo CD applies the [database layer](database/ARCHITECTURE.md#database-change-management) and deploys the six application artifacts, including both order processes, from the EKS overlay with ECR digest pins. Implement an explicit delivery contract replacing Helmfile stage dependencies/hooks and Helm revision-based Job identities: Argo [renders with `helm template`](https://argo-cd.readthedocs.io/en/stable/user-guide/helm/), so `.Release.Revision` cannot identify a new sync. Use [sync phases/waves](https://argo-cd.readthedocs.io/en/stable/user-guide/sync-waves/) plus readiness checks and pre/post-sync verification Jobs or a gated CI workflow for the existing Maven safety and acceptance checks. Give migration/seeder attempts collision-free identities and safe retention/deletion policies; never delete an active/interrupted migration to retry it. Verify no-op resync, changed migrations, failure before publication, interrupted attempts, recovery and seeder reruns without caching a completed Job or claiming SQL rollback. Prevent selective sync from bypassing required hooks/gates. All eleven scenarios pass on EKS with evidence and costs; the local Helmfile workflow still passes 6.9 unchanged. |
| 7.5 Stable AWS webpage URLs and access control | 4.10, 7.4 | Planned | Qualify an EKS-compatible Gateway API controller, including its TLS and authentication integrations; deliver `GatewayClass`, `Gateway` and `HTTPRoute` resources through GitOps in the EKS overlay. Webpages and their required APIs, starting with `/products/`, use stable hostnames backed by Route 53 DNS and HTTPS with ACM-managed certificates and HTTP-to-HTTPS redirection; Terraform owns DNS and certificates. Document the public or private/VPN access model and enforce authentication and role-based authorization on both webpages and APIs through a supported controller integration or an identity-aware proxy. Expose only intended routes and methods; other APIs, databases and brokers remain internal. Acceptance proves valid HTTPS, rejection of unauthenticated and unauthorized requests, and prevention of direct backend access that bypasses these controls. A copied URL such as `/products/?region=EU&page=3` restores the selected view on another authorized device, including after login; the hostname survives pod replacement and application redeployment without port-forwarding. Document the URLs, access setup, certificate renewal, costs and teardown; step 6.9 still passes locally unchanged. |

The supported-version gate (milestone 0) and first replicated shard (1.3) must
pass before repeating database infrastructure. The first complete order demo is
4.9: accepting an order alone is not completion of the saga. Individual recovery
and failover steps retain their own acceptance gates.

## 2. Scope and target topology

The [system overview](ARCHITECTURE.md) defines the scope, application roles,
[runtime topology and pod counts](ARCHITECTURE.md#2-runtime-topology), and
[sharding](ARCHITECTURE.md#3-sharding). [VERSIONS.md](VERSIONS.md) defines the versions and
support windows. This implementation note is not in the architecture:

- After a promotion, the operator moves each `-rw` Service to the new primary.
  Open connections and transactions can still fail. See
  [CloudNativePG architecture](https://cloudnative-pg.io/docs/1.30/architecture/).

## 3. Modules, data ownership, and routing

The [core design](shardshop-core/ARCHITECTURE.md#goal-and-module-boundaries) defines
Maven modules and dependency rules. The [database design](database/ARCHITECTURE.md)
defines schema ownership; [routing](shardshop-core/sharding/ARCHITECTURE.md) and
[ID generation](shardshop-core/idgen/ARCHITECTURE.md) define placement, the shard
inventory and generator allocation. The [runbook](RUNBOOK.md#build-and-run) lists the
modules and their application classes. This implementation note is not in the
architecture:

- Routing is not authorization. If many requests use the same sellers or buyers,
  one shard can get more load than the others.

## 4. Technology choices

Use long-supported releases where the project/vendor offers them, and maintained
stable GA releases with planned upgrades elsewhere. The selected policy is
**community releases with regular upgrades**; commercial extended support is
outside scope. This applies to runtimes, every direct/transitive library, build/test
plugins, container OS packages, and operational tooling. Stable does not imply
LTS. The explicitly requested pre-1.0 Snowflake library is the narrow exception
to the maintained-release requirement: no published support term is assumed.
The official Quarkus plugin also carries three exact plugin-only XML prereleases,
qualified under the scoped exception in the version policy.
Follow the dated baseline and lifecycle evidence in [VERSIONS.md](VERSIONS.md).

| Concern | Implemented baseline or explicitly planned target |
|---|---|
| Java | JDK 25 or newer for build and tests (bytecode targets release 25), with no vendor or exact-patch restriction; the application and migration images use the OpenJDK 25 runtime of the Ubuntu JRE image, never Temurin |
| Local Kubernetes | Kubernetes 1.36.x on kind with a digest-pinned node image; `docker`, `kind`, `kubectl`, and `helm` come from the user's `PATH` (tested with Docker Desktop 4.92.0, kind 0.33.0, kubectl 1.36, and Helm 4.3.0) |
| Node layout | One control-plane node and three worker nodes; Docker Desktop VM with at least 12 GB of memory |
| Database lifecycle | CloudNativePG 1.30.1 initially, installed from its verified Helm chart, with upgrades before operator EOL; three independent three-instance quorum shard clusters and separate ledger storage |
| Database | PostgreSQL 18, reviewed patch 18.6, with maintained 18.x updates and pinned image digests |
| Application | Six Quarkus 3.40.1.1 applications, five library JARs (common, idgen, sharding, workload-control and datasets), and test-only `shardshop-testing`: twelve Java modules under six top-level Maven children; JVM mode, a scoped platform BOM, and supported branch upgrades |
| Persistence | Explicit JDBC repositories with PostgreSQL JDBC and Agroal; bounded pools per service, pod, shard, and endpoint |
| Identifiers | Explicitly pinned `de.mkammerer.snowflake-id:snowflake-id:0.0.2`; decimal strings on the wire and positive `BIGINT` in PostgreSQL; reviewed support-policy exception |
| Database change management | CNPG `DatabaseRole`, `Database` and `Publication` resources for roles, schemas and CDC publications; the Flyway OSS CLI (13.8.1 initially) from its official image, pinned by digest, on the OpenJDK runtime of the Ubuntu JRE image, for one migration stream per schema under `database/`, run as Kubernetes Jobs by that schema's migrator |
| Messaging | RabbitMQ 4.3.6 with its bundled Erlang/OTP, qualified in step 4.3 and pinned in VERSIONS.md and the lock; requalify upgrades within its support window. The [architecture's retry/DLQ contract](infra/ARCHITECTURE.md#rabbitmq-and-delivery-reliability) applies |
| Change data capture | Debezium Engine and its PostgreSQL connector (`pgoutput`), embedded in the order saga process with one reader per shard and offsets in each shard database (JDBC offset store); pinned and qualified under VERSIONS.md in step 4.4 |
| Deployment packaging | Skaffold 2.25.0 for packaged image builds, Helmfile 1.8.0 for ordered real Helm releases, and vendored verified operator charts (CloudNativePG chart 0.29.1 today); one ordered inventory/shared chart with environment values. Native kind delivery verifies build IDs/manifest digests; the same inventory/templates serve the later cloud target |
| Cloud target (optional) | AWS EKS provisioned with Terraform in milestone 7, after the local lab passes step 6.9: an EKS overlay, the same operator charts, and Argo CD GitOps delivery for the database layer and the applications. No local step depends on it |
| Libraries | Quarkus platform-managed extensions and compatible JDBC/Jackson/logging/test libraries; qualify RabbitMQ and Debezium separately and audit inherited overrides |
| Build and testing | Command-line Maven 3.9.16 or newer (Quarkus plugin minimum), separately pinned GA build plugins, JUnit/Mockito, Cucumber scenarios with Testcontainers, and Kubernetes drills |

The infrastructure support baseline was checked on 2026-09-28; the Quarkus
framework choice was checked on 2026-10-03. PostgreSQL 18 has multi-year
maintenance; the OpenJDK 25 selection carries no assumed vendor support horizon.
Kubernetes, CNPG, RabbitMQ, Debezium, Quarkus, and many libraries need regular supported-release upgrades. Recheck sources before implementation and
pin exact artifacts only after compatibility and image-availability verification.
Apart from the three qualified plugin-only XML artifacts, no prereleases,
floating tags, external snapshots, unchecked inherited library versions or
assumed paid support are allowed.

### Quarkus framework migration

Step 0.5 completed the framework migration and skeleton qualification. Keep the
following implementation rules when extending the implemented HTTP, persistence,
messaging and workload behavior.

1. Import `io.quarkus.platform:quarkus-bom:3.40.1.1` in the ShardShop parent and
   pin `io.quarkus.platform:quarkus-maven-plugin:3.40.1.1` separately. Retain the
   reviewed Jackson/pgJDBC security overrides recorded by step 6.0. Configure
   augmentation/code generation only for the six applications. Keep common free
   of Quarkus dependencies. Idgen and sharding are ordinary library JARs. Their
   domain classes have no framework imports, and each library has its own `config`
   package for Quarkus wiring. Audit inherited dependency versions,
   test providers and plugin overrides against the effective Quarkus model.
   Each application uses Quarkus JVM `fast-jar` packaging. Ship the complete
   `target/quarkus-app/` directory, and launch it as the
   [runbook](RUNBOOK.md#build-and-run) shows. Native compilation is out of scope.
   [Maven tooling](https://quarkus.io/guides/maven-tooling/).
2. Use Quarkus lifecycle APIs, CDI constructor injection and SmallRye Config.
   Preserve the implemented service/process roles and workload lifecycles;
   startup validation remains separate from starting runtime work.
   [Lifecycle](https://quarkus.io/guides/lifecycle/),
   [configuration](https://quarkus.io/guides/config-reference/).
3. Add extensions only in modules and milestones that need them: Quarkus REST
   with Jackson (`quarkus-rest-jackson`), Jakarta validation
   (`quarkus-hibernate-validator`), PostgreSQL JDBC with Agroal
   (`quarkus-jdbc-postgresql`), SmallRye Health (`quarkus-smallrye-health`) and
   Prometheus metrics (`quarkus-micrometer-registry-prometheus`). Retain explicit
   JDBC transactions on one selected shard and bounded pools; keep blocking IO
   off event-loop threads. Migrations remain external Flyway Jobs. Use the
   RabbitMQ Java client (`com.rabbitmq:amqp-client`) behind module-owned adapters
   to preserve mandatory routing, returns/confirms and acknowledgement ordering;
   keep Debezium Engine embedded in order and qualify both libraries against the
   Quarkus dependency graph. Workloads retain the architecture's HTTP-only client
   contract and receive no database/broker credentials.
   [REST](https://quarkus.io/guides/rest/),
   [datasources](https://quarkus.io/guides/datasource/),
   [RabbitMQ Java client](https://www.rabbitmq.com/client-libraries/java-api-guide).
4. Keep plain JUnit/Mockito tests for domain and routing code. Test startup and
   CDI with `quarkus-junit` and, when necessary, `quarkus-junit-mockito`. Keep the
   coverage and the Mockito agents, and test packaged launches as well as
   in-process startup. Retain the opt-in `integration` profile and pinned
   Testcontainers images; keep automatic Dev Services disabled for the ordinary
   test run and use
   explicit infrastructure in integration tests. Refresh `VERSIONS.md`,
   `versions.lock.yaml`, audit reports, READMEs and runbooks only with newly verified
   versions, checksums and commands. [Testing](https://quarkus.io/guides/getting-started-testing/).

## 5. Local infrastructure details

The runbooks describe the implemented
[cluster](infra/RUNBOOK.md#kind-topology), [storage and database sizing](database/RUNBOOK.md#shard-clusters)
and the [generator registry](shardshop-core/idgen/RUNBOOK.md#generator-allocation-at-jvm-startup).
These rules apply to the remaining steps:

- Use namespace `shardshop` and context `kind-shardshop`; scripts pass the
  context explicitly on every command. Keep the operator installation version explicit.
- Bootstrap secrets at runtime from environment input or generated credentials;
  commit references only. Separate migration, application, and CDC permissions.
  Workload apps receive HTTP configuration, never SQL credentials.
- Run applications in Kubernetes for the demo so Service DNS resolves normally.
  Keep database/broker endpoints internal; expose APIs by port-forward.
  Document separate port-forwards for IDE debugging if needed.
- Start infrastructure and services, then the idempotent product seeder Job and
  the workload Deployments. Each workload decides in Java when it sends load: the
  reader and the order producer start only after they verify the complete seeded
  dataset through product, and they stop with an error after a bounded wait. The
  default workload performs no continuous product writes after seeding.

Multiple local nodes share one physical machine. This setup teaches pod/process
failure handling but cannot protect against laptop or disk failure.

### Portability beyond kind

The lab targets kind, but these rules keep a later move to AWS EKS (milestone 7),
OpenShift, or another distribution confined to the cluster bootstrap and environment values:

1. Keep everything kind-specific in `infra/kind.yaml` and the bootstrap; every
   other manifest is plain Kubernetes.
2. Keep one ordered shard inventory of names and immutable regions in
   `infra/shards.yaml` and shared Helm
   templates in `infra/helm/shardshop/`. Environment values (initially
   `kind-values.yaml`) set only what differs between clusters, such as storage
   class and anti-affinity. Helmfile manages real Helm releases on kind; bootstrap
   resources outside release ownership remain ordinary Kubernetes manifests; never add a directory or copied manifest for another shard.
3. Every application, database and broker pod meets the Kubernetes `restricted`
   Pod Security Standard (non-root, no privilege escalation, all capabilities
   dropped, `RuntimeDefault` seccomp). Prefer admission-assigned IDs; the current
   Flyway Jobs and RabbitMQ StatefulSet explicitly use UID/GID 10001, with
   RabbitMQ also using fsGroup 10001. Nonzero fixed IDs meet Kubernetes Restricted
   but do not establish OpenShift restricted-SCC compatibility: namespace-assigned
   UID/group ranges can reject them. An OpenShift port must remove/adapt these
   settings and qualify image/PVC permissions, without changing retained volume
   ownership blindly. Label `shardshop` with
   `pod-security.kubernetes.io/enforce: restricted`; leave CNPG's
   `podSecurityContext` unset. Step 6.8 must separately document proxy identities,
   admission results and any privileged node/CNI components in dedicated system
   namespaces; it must not relax `shardshop` admission. No mesh exception is
   qualified by this plan. See [Kubernetes Restricted](https://kubernetes.io/docs/concepts/security/pod-security-standards/#restricted)
   and [OpenShift SCC rules](https://docs.redhat.com/en/documentation/openshift_container_platform/4.20/html/authentication_and_authorization/managing-pod-security-policies).
4. Drills cause failures through the Kubernetes API (force-delete pods, cordon or
   drain nodes). A drill that must stop a whole node is a kind-only extra, kept
   separate from the portable drills.
5. Step 4.3 qualifies RabbitMQ with the selected non-root UID/GID and retained PVC.
   A different admission-assigned identity and OpenShift SCC behavior require
   separate verification before claiming portability to that environment.
6. Install third-party operators from their vendored, verified Helm charts with
   shared values in `infra/helm/`, so every cluster runs the same chart version
   and image pins; cluster-specific settings belong in that cluster's values file.

A move to OpenShift would still need a decision on the PostgreSQL operator:
community CloudNativePG does not support OpenShift, and EDB's certified operator
(API group `postgresql.k8s.enterprisedb.io`) is a commercial product outside the
community-only policy in [VERSIONS.md](VERSIONS.md).

## 6. Implementation boundaries

The [product](shardshop-product/ARCHITECTURE.md#product-api),
[order](shardshop-order/ARCHITECTURE.md#order-creation-and-ledger-saga) and
[infrastructure](infra/ARCHITECTURE.md#rabbitmq-and-delivery-reliability) designs
define the HTTP contracts, the validation order, the saga, reconciliation,
quarantine and message delivery. The repository's [AGENTS.md](../../AGENTS.md) defines the code layers.
These implementation notes are not in the architecture:

- Keep an explicit shard handle from shard selection to commit. Do not use
  thread-local routing. Test concurrent requests for routing leakage.
- Before you release code that needs a schema change, verify the version of each
  schema owner on every shard.

## 7. Replication and backup policy

The [database design](database/ARCHITECTURE.md#sharding-and-replication)
defines quorum synchronous replication and logical-slot synchronization. The
[order recovery design](shardshop-order/ARCHITECTURE.md#orders-lost-by-asynchronous-failover-or-restore)
defines the planned audit for lost orders; the [acceptance scenarios](shardshop-testing/ARCHITECTURE.md#acceptance-scenarios)
define the durability checks. These implementation notes for steps 6.3 and 6.4 are
not in the architecture:

- Dump the three shards and the ledger database to storage outside kind. Restore
  them into isolated targets. For each database, record its identity, routing
  names, region versions, owner schema versions and backup time.
- Separate dumps are not one consistent snapshot. Before a baseline backup, stop
  the producers and wait until no saga or relay has open work. If you do not,
  reconcile the restored order and ledger state explicitly.
- Keep the permanent ledger outcomes in each backup and restore. Export the broker
  definitions. The outboxes and reconciliation supply the replay data.
- A logical dump contains the old CDC offsets of its shard, but no replication
  slot. Thus, a shard that returns to service from a dump needs the explicit CDC
  recovery.
- With the recovery metadata, keep the Snowflake epoch and layout, the dataset
  versions, the order-allocation mappings, the reserved result identities and the
  allocator history. A restore keeps the latest allocator high-water mark.
- Base backups with continuous WAL archiving for point-in-time recovery are later
  work, for example step 7.3 on AWS. Replicas also copy accidental deletes, thus
  they do not replace backups.

## 8. Planned layout and verification

The layout is implemented except the paths explicitly marked milestone 7. Each
module's `ARCHITECTURE.md` owns its design, `RUNBOOK.md` its procedures, and README
its start and monitoring entry points.

```text
microservices/shardshop/
  PLAN.md
  README.md                       # how to start the lab and its monitors
  RUNBOOK.md                      # operations, configuration and tests
  ARCHITECTURE.md
  CHANGELOG.md                    # evidence and commands of the done steps
  VERSIONS.md                     # baseline, support evidence, update policy
  versions.lock.yaml              # verified artifact inventory, support dates, open blockers
  pom.xml                         # parent POM aggregator
  shardshop-core/                 # library POM parent and aggregator
    common/                       # shared validation and utilities, with unit tests
    idgen/                        # ID generation and its Quarkus wiring; product/order only
    sharding/                     # routing and its Quarkus wiring; product/order only
    workload-control/             # shared live controls and pacing; workload apps only
  shardshop-workload/
    pom.xml                       # workload POM aggregator
    shardshop-datasets/            # pure ID-free definitions; workload dependencies only
    shardshop-product-seeder/      # bootstrap Job and interactive seeder Deployment
    shardshop-product-reader/      # read-load application
    shardshop-order-producer/      # order-load application
  shardshop-product/              # seller and product API
  shardshop-order/                # one image; separate HTTP and saga Deployments
  shardshop-ledger/               # ledger consumer and result relay
  shardshop-testing/              # Cucumber: Testcontainers integration profile; live kind profile
  database/                       # Flyway streams: shard/catalog, shard/ordering, ledger/ledger
  infra/kind.yaml                # kind-only cluster configuration
  infra/shards.yaml              # only ordered list of shard names and immutable regions
  skaffold.yaml                  # local packaged image builds and artifact tags
  infra/helmfile.yaml.gotmpl      # release dependencies, sync stages and Maven gates
  infra/helm/shardshop/           # shared infrastructure, migration/routing/application templates
    kind-values.yaml             # kind storage class and anti-affinity strictness
    eks-values.yaml              # milestone 7: EKS storage class and zone placement
  infra/helm/cnpg/                # vendored CloudNativePG chart and pinned values
  infra/images/flyway/            # Flyway migration image on the Canonical JRE
  infra/images/application/       # application images on the OpenJDK runtime, without kubectl
  infra/terraform/               # milestone 7: AWS infrastructure (optional)
  scripts/                       # explicit cluster/application lifecycle
    cluster-create.sh            # provision/reconcile infrastructure; fresh allocator only; no applications
    up.sh                        # resume existing nodes, build, migrate, deploy and restart pods
    lib/lifecycle.sh             # sourced lifecycle implementation; no extra entry point
    down.sh                      # stop every cluster node, retain data
    cluster-destroy.sh           # permanently remove cluster, storage and kubeconfig
    webpages.sh                  # access only: five localhost tunnels, no lifecycle lock
```

ShardShop's parent aggregator is registered in `microservices/pom.xml` (step 0.2).
Tests obey the testing rules in [AGENTS.md](../../AGENTS.md). HTTP client tests use
in-process loopback servers. Testcontainers tests run in the `integration` Maven
profile, as the Cucumber scenarios of `shardshop-testing`. The step 3.15 `kind`
profile verifies infrastructure safety, deployed routing/topology and workloads
using an explicit invocation snapshot. Its default tag is `kind-deployment`;
`kind-drill` selects all disruptive families (database, workload, saga and retry/capacity);
`kind-saga-drill` narrows selection to the order-process recovery checks from 4.14.
`kind-retry-drill` selects the shard-outage and DLQ capacity checks from 5.4.
Both are opt-in and use the
shared recovery guard. Turn
every architecture acceptance scenario into a reproducible check, without adding scripts.

The lifecycle commands below run from the repository root. Create the foundation
once before application deployment; each later `up.sh` resumes retained nodes and
restarts applications even for an unchanged build.

```bash
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
```

Retain the lab between runs:

```bash
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/up.sh
```

For an intentional destructive reset, retire prior data/replay inputs and run:

```bash
bash microservices/shardshop/scripts/cluster-destroy.sh
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
```

Use native `helm template` for previews, as shown in the
[inventory runbook](shardshop-core/sharding/RUNBOOK.md#shard-inventory-and-routing).
The [recovery runbook](infra/RUNBOOK.md#recover-retained-data-or-failed-startup) distinguishes
safe startup retries from lost-storage/allocator recovery; lifecycle commands
provide no backup or restore automation.

Build only ShardShop or the affected submodule; never run the repository's root
Maven build for this project. Developer checks remain available through Maven:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify
# Prepare $snapshot with "Kind verification snapshot" in shardshop-testing/RUNBOOK.md first.
# Keep the same snapshot for both kind commands; the lab must be migrated and deployed.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot"
# Optional disruptive drills; never invoked by up.sh.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-drill
kubectl --context kind-shardshop -n shardshop get clusters.postgresql.cnpg.io -L shardshop.javacraft/region
kubectl --context kind-shardshop -n shardshop get pods,pvc,svc
```

The [verification runbook](shardshop-testing/RUNBOOK.md#kind-verification-snapshot)
documents snapshot and suite/tag selection. Neither default builds nor `up.sh`
run disruptive drills. Steps 4.9 and 4.14 extended workload/process acceptance;
step 6.2 qualified promotion/failover coverage on October 26; see its
[dated evidence](CHANGELOG.md#step-62).

Live scenarios must restrict mutations to this disposable lab and report their
results. Keep destructive volume/cluster cleanup separate from startup, shutdown
and deployment verification. Backup and restore procedures use native
tools or Maven-invoked Java tooling with explicit source/target arguments, without
introducing another script.
