# ShardShop implementation plan

## 1. Implementation milestones

This is the implementation checklist for `microservices/shardshop`, updated on
2026-10-09. Every step starts as **Planned**; existing design documents and routing
vectors do not mean the corresponding application behavior is implemented.

**Framework:** all six applications use **Quarkus 3.40.1** (3.40 LTS line) in JVM
mode on Java 25. Step 0.5 and [VERSIONS.md](VERSIONS.md#step-05-verification-record)
record the qualification. [Release status](https://quarkus.io/releases/).

**Architecture constraints:** every milestone below must obey these rules of
[ARCHITECTURE.md](ARCHITECTURE.md):

- [ID ownership](ARCHITECTURE.md#1-goal-and-module-boundaries): only product and
  order create IDs.
- [Workload datasets](ARCHITECTURE.md#workload-datasets-service-issued-ids-and-routing):
  ID-free definitions in `shardshop-workload`.
- [Seller profit](ARCHITECTURE.md#4-product-generation-and-read-load): confirmed
  margin for each currency.
- [Regional placement](ARCHITECTURE.md#3-shared-postgresql-sharding-and-replication):
  `shard-a=US`, `shard-b=EU` and `shard-c=ASIA`.
- [Flyway SQL](ARCHITECTURE.md#migrations): no SQL functions or triggers.

**Retained baselines:** on 2026-10-01, a reset of the empty lab catalogs applied the
consolidated `V1__catalog.sql` with the regional seller constraints and comments.
Retained baselines are now immutable. Later schema changes use fix-forward migrations.

### Instructions for executing a step

1. Read the repository's `AGENTS.md`, this plan,
   [ARCHITECTURE.md](ARCHITECTURE.md), and [VERSIONS.md](VERSIONS.md). The
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
   Use real database/broker integration tests where IO matters and bounded polling
   for Kubernetes checks. Infrastructure steps are verified by live runs against
   `kind-shardshop`, recorded as evidence. Add a script only when a step needs a
   repeatable live check or lab operation. Set the `kind-shardshop` context
   explicitly in every command ([§5](#5-local-infrastructure-details)).
5. Mark the row **Done** only after its acceptance condition and relevant checks
   pass. Then add the date and one result sentence to its acceptance cell. Record
   the evidence and the exact commands in the step's entry of
   [CHANGELOG.md](CHANGELOG.md). Use
   **Blocked** with a concrete reason when completion is prevented; resume as
   **In Progress** when the blocker is resolved. Partial work is not **Done**.
6. Take Maven, `kind`, `kubectl`, `helm`, and Docker from the user's `PATH`.
   Never download, vendor, wrap, or version-pin tool binaries in the repository:
   no project-local tool folders such as `.local/bin` and no Maven Wrapper. Record
   tested versions in [VERSIONS.md](VERSIONS.md) instead. Third-party charts and
   manifests are artifacts, not tools: vendor them only with a verified checksum
   in the lock. Write lab start, stop and deploy scripts as short bash. Write
   checks in bash or Python 3. A Python check uses only the standard library and
   the packages pinned in `scripts/contract-validation-requirements.txt`. Record
   evidence as short text in [CHANGELOG.md](CHANGELOG.md), and do not commit dated scan or report
   files.
7. Milestones 0–6 run and are verified entirely on this machine, on the local
   kind lab or in disposable local containers. Milestone 7 (AWS with Terraform)
   is an optional advanced track that
   starts after step 6.6; never make a local step depend on it or on a cloud
   service.
8. Keep each installed component inside its support window at all times. This
   rule replaces step 6.5. Before you start a step, read the review deadlines in
   [VERSIONS.md](VERSIONS.md#4-maintenance-and-acceptance-gates) and the lock. If a
   deadline occurs before the step can end, upgrade that component first. In
   VERSIONS.md, record these items:
   - the successor and its compatibility checks
   - the upgrade and recovery procedure
   - the results of the checks that exist at that time

Execute steps in dependency order. Use the table order, except for step 0.5,
which follows step 2.4. “Depends on” lists direct prerequisites and includes
their prerequisites transitively. Scenario numbers refer to the
[architecture's acceptance scenarios](ARCHITECTURE.md#7-consistency-tradeoffs-and-verification).
Milestone numbers 0-7 remain stable for references from other documents.

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
| 2.3 Database change management and catalog schema | 2.1 | Done | The [database layer](ARCHITECTURE.md#database-change-management) owns the catalog roles, schema and Flyway stream, and no migration needs a superuser session. **2026-09-30:** all nine instances have identical catalog ownership, grants and constraints, without triggers or functions. [Evidence](CHANGELOG.md#step-23) |
| 2.4 Single shard inventory and configured routing | 2.3, 0.4 | Done | One ordered inventory of shard names and immutable regions renders all shard resources and the routing snapshot, and the topology guards fail closed. **2026-09-30:** the shared chart renders the earlier resources without change, and the 14 Cluster/PVC identities stay the same. [Evidence](CHANGELOG.md#step-24) |
| 2.5 Ordering schema and catalog access | 2.3, 2.4 | Done | The ordering stream creates the buyer, allocation, order, saga, transport and quarantine tables on every shard, with the outbox publication, the CDC offsets table and the [role matrix](ARCHITECTURE.md#roles). **2026-10-01:** restricted TLS client Jobs pass 597 grant assertions. [Evidence](CHANGELOG.md#step-25) |
| 2.6 Ledger schema and permanent decisions | 2.2, 2.3 | Done | The ledger stream creates entries, permanent decisions, result bindings with publication attempts, inbox, outbox and command quarantine, with separate migration and runtime roles. **2026-10-01:** restricted TLS `ledger_app` Jobs pass 228 grant assertions. [Evidence](CHANGELOG.md#step-26) |

### Milestone 3: Product contracts, IDs, and workloads

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 3.1 HTTP and message contracts | 0.5 | Done | Each owning module holds its self-contained OpenAPI document or message schema, and clients keep their own DTOs. **2026-10-06:** `verify-contracts.py` validates their structure, examples and boundaries. [Evidence](CHANGELOG.md#step-31) |
| 3.2 Common utilities, ID validation and currency selection | 3.1 | Done | `shardshop-common` owns the one `IdParser` and the unsigned SHA-256 calculation for all six applications and routing. **2026-10-06:** the affected builds pass 186 unit/startup tests with 100% line and branch coverage. [Evidence](CHANGELOG.md#step-32) |
| 3.3 Bounded Snowflake generation | 0.5 | Done | `shardshop-idgen` gives each product/order process one eager, bounded Snowflake generator, as [ARCHITECTURE](ARCHITECTURE.md#identifier-generation-and-representation) defines. **2026-10-06:** 215 unit/startup tests, 40 packaged startup checks and the scenario 10 library checks pass. [Evidence](CHANGELOG.md#step-33) |
| 3.4 Generator allocation at JVM startup | 1.1, 3.3 | Done | The product/order launcher reserves a fresh generator ID before every JVM start, as [ARCHITECTURE](ARCHITECTURE.md#generator-identity-across-processes-and-restarts) defines. **2026-10-07:** the live kind drill reserves distinct IDs for concurrent starts and for a container restart. [Evidence](CHANGELOG.md#step-34) |
| 3.5 Workload-owned datasets and service-issued IDs | 3.1, 3.2 | Done | `shardshop-datasets` holds the ID-free `catalog-v1` and `buyers-v1` definitions, and the workload clients use only service-issued IDs. **2026-10-07:** 559 unit/client/startup tests pass with 100% line and branch coverage. [Evidence](CHANGELOG.md#step-35) |
| 3.6 Seller and product API on primaries | 0.4, 2.3, 3.2, 3.4, 3.5 | Done | Product serves the seller/product API of [ARCHITECTURE §4](ARCHITECTURE.md#4-product-generation-and-read-load) on primaries, with the fix-forward catalog migration V2. **2026-10-07:** 163 OpenAPI-validated provider responses pass, and the lab applied V2 on all primaries. [Evidence](CHANGELOG.md#step-36) |
| 3.7 Product seeder application | 3.5, 3.6 | Done | Repeated seeding recovers the same service-issued IDs for all 150 sellers and 300 products, and completion requires successful verification reads. **2026-10-07:** `verify-product-seeder.py` passes on four disposable shards. [Evidence](CHANGELOG.md#step-37) |
| 3.8 Product reader application | 3.5, 3.6 | Done | The reader sends bounded read load and reports request, latency and error metrics, as the [HTTP connection policy](ARCHITECTURE.md#http-connection-policy) defines. **2026-10-08:** `verify-product-reader.py` passes twice on four disposable shards. [Evidence](CHANGELOG.md#step-38) |
| 3.9 Product deployment and seeding gate | 3.7, 3.8 | Planned | Product, the seeder Job and the reader run on the lab behind the [seeding gate](ARCHITECTURE.md#workload-lifecycle). Repeated runs pass scenario 1 for the reader and scenario 3 for primary reads. One image build and scan script makes the application images, with an OS package inventory and advisory check, and step 4.8 uses it again. The product and order images take kubectl from a digest-pinned image, as the step 3.4 drill does. Product counts the requests of each pod with the Micrometer Prometheus extension ([§4](#quarkus-framework-migration)). Do not tune the reader's connection rotation, because step 6.7 replaces the HTTP/1.1 client. The order producer joins the gate in step 4.8. |

### Milestone 4: Complete order-to-ledger saga

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 4.1 Order validation and atomic acceptance | 2.5, 3.4, 3.6 | Planned | Order serves buyer creation, order allocation and order PUT/GET, as [ARCHITECTURE §5](ARCHITECTURE.md#5-order-creation-and-ledger-saga) and the order OpenAPI define. Fix-forward ordering migrations add the buyer person fields, creation keys and unit-cost snapshots, and V1 stays unchanged. Scenario 9 passes, and provider tests match the order OpenAPI. |
| 4.9 Stock reservation step | 2.5, 4.1 | Planned | Order reserves and releases stock as the [stock reservation rules](ARCHITECTURE.md#stock-reservation) define. Scenario 11 passes. |
| 4.2 RabbitMQ topology and policies | 1.1, 0.5, 3.1 | Planned | The persistent broker and its queues, policies, feature flags and limits match [ARCHITECTURE §6](ARCHITECTURE.md#6-rabbitmq-and-delivery-reliability). Select a release inside its support window when the step starts, and record it in VERSIONS.md and the lock. The broker runs with an arbitrary user ID ([portability rule 5](#portability-beyond-kind)). |
| 4.3 Order outbox CDC relay | 4.2, 4.9 | Planned | Pin and qualify Debezium in VERSIONS.md. The relay obeys the [CDC rules](ARCHITECTURE.md#cdc-from-shard-writes-to-the-ledger), and published `RecordOrder` envelopes match the order-owned message schema. The relay checks of scenario 6 pass. Steps 5.5 and 6.2 add its crash and failover cases. |
| 4.4 Ledger command consumer | 2.6, 3.2, 4.2 | Planned | The ledger consumer records decisions, replays duplicates and quarantines conflicts, as [ARCHITECTURE](ARCHITECTURE.md#ledger-decisions-replay-identities-and-transport) sections 3 and 5 define, with the advisory-lock serialization. Commands with invalid IDs are rejected without requeue. |
| 4.5 Ledger result publisher | 4.4 | Planned | The result relay publishes stored outcomes with the reserved IDs and the publication-attempt checks of [ARCHITECTURE §5](ARCHITECTURE.md#reconciliation-and-replayed-outcomes). Result envelopes match the ledger message schema. |
| 4.6 Order result consumer | 4.3, 4.5 | Planned | Order applies results, releases stock after a cancellation, and quarantines absent or conflicting results, as [ARCHITECTURE §5](ARCHITECTURE.md#5-order-creation-and-ledger-saga) defines. Confirmation credits seller profit exactly once for each item, as the [profit rules](ARCHITECTURE.md#4-product-generation-and-read-load) define. Results with invalid IDs are rejected without requeue. |
| 4.7 Order producer application | 3.5, 4.1 | Planned | The HTTP-only order producer creates the buyers, then allocates and submits orders, as [ARCHITECTURE](ARCHITECTURE.md#workload-datasets-service-issued-ids-and-routing) sections 1 and 3 define. This includes cross-region, mixed-region and EUR-rejection orders. The producer keeps the returned IDs over retries and restarts, and it polls the order status within bounded deadlines. |
| 4.8 Saga deployment and first end-to-end run | 3.9, 4.6, 4.7 | Planned | Order, ledger and the order producer run on the lab, with images from the step 3.9 script. The seeding gate also blocks the order producer, and a replacement order pod on either worker resumes CDC from the stored offsets. Scenarios 4, 5 and 11 pass end to end. |

### Milestone 5: Recovery, retention, and backpressure

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 5.1 Durable saga reconciliation | 4.8 | Planned | Lost results recover through stored ledger outcomes; injected-clock checks prove at most five automatic replay enqueues even after restarts and published-row cleanup. |
| 5.2 Safe transport cleanup | 5.1 | Planned | Transport cleanup obeys the [retention rules](ARCHITECTURE.md#retention). Permanent records, unpublished messages and unresolved quarantine survive, and cleanup deletes never become commands. |
| 5.3 Manual reconciliation reset | 5.2 | Planned | Implement the operator reset of [ARCHITECTURE §5](ARCHITECTURE.md#reconciliation-and-replayed-outcomes). Define how an operator starts the reset and which database login it uses. Give that login only the grants that the reset needs. The reset part of scenario 7 passes. |
| 5.4 Retry and capacity drills | 5.3 | Planned | The retry and capacity drills of scenario 7 pass on the pinned broker. |
| 5.5 Crash recovery and observability | 5.4 | Planned | Scenario 6 passes, except its failover case, which step 6.2 owns. Metrics show stalled outboxes and CDC readers, sagas, quarantine, queue capacity, and pool or replication pressure. |

### Milestone 6: Read profiles, failover, restore, and operation

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 6.1 Strict replica-read drill | 3.9 | Planned | Deploy product's strict replica read profile from step 3.6 on the lab, and test it with the reader. Add the limited `404` retry of the [workload lifecycle](ARCHITECTURE.md#workload-lifecycle) to the seeder's verification reads and the workloads' startup lookups. The read checks of scenarios 3 and 8 pass. |
| 6.2 Synchronous failover drills | 5.5, 6.1 | Planned | The failover parts of scenarios 6 and 8 pass for switchover and primary failure. Each drill proves a real promotion, not a restart. The former primary rejoins safely, and a shard never has two writable primaries. Design the drills for the [CNPG failover behavior](https://cloudnative-pg.io/docs/1.30/failover/). |
| 6.3 Coordinated backup and isolated restore | 5.3, 6.2 | Planned | Isolated restores retain schema/routing metadata, permanent outcomes, quarantine, and the latest allocator high-water mark; backup artifacts live outside kind. After each restore, checks prove schema/routing compatibility and the consistency of orders, sagas and the ledger. |
| 6.4 Restore audit and recovery | 6.3 | Planned | The restore and audit parts of scenario 8 pass, as [ARCHITECTURE §5](ARCHITECTURE.md#orders-lost-by-asynchronous-failover-or-restore) defines. This step also runs the optional asynchronous-loss drill. |
| 6.7 HTTP/2 transport | 4.8, 6.1 | Planned | All workload calls to product and order use HTTP/2 over cleartext (h2c) with prior knowledge. A qualified request-level balancer sends the requests of each HTTP/2 connection to all ready pods, because a Kubernetes Service balances only TCP connections. [VERSIONS.md](VERSIONS.md#step-67-request-balancer-candidates) lists the candidates. Record the selected component, its versions, support window and resource use there, and vendor its charts with verified checksums. Application namespaces keep `restricted` Pod Security. The workloads change to an HTTP/2 client, because the classic Apache API supports only HTTP/1.1. Verify that Quarkus 3.40 accepts h2c with prior knowledge. Update the [HTTP connection policy](ARCHITECTURE.md#http-connection-policy) and scenario 1. The read deadline, product's `503` behavior, the reader's startup deadline check and the reader metrics stay the same. The checks of steps 3.9, 4.8 and 6.1 pass again. Record an HTTP/1.1 baseline first, then measure the latency and CPU cost of the proxy hop under the same load. |
| 6.6 Complete demo and acceptance run | 6.4, 6.7 | Planned | A fresh lab follows the README without hidden steps; all eleven architecture scenarios have commands and passing evidence, including full Snowflake lifecycle checks. Every installed component is inside its support window. |

### Milestone 7: AWS deployment (optional, advanced)

This track starts only after step 6.6 passes locally. It adds a cloud target
without changing local behavior: every earlier step must still run and pass on
kind. Qualify each new tool, provider, and service under [VERSIONS.md](VERSIONS.md)
before use, and record costs and teardown commands.

| Step / deliverable | Depends on | Status | Acceptance condition |
|---|---|---|---|
| 7.1 Terraform AWS foundation | 6.6 | Planned | `infra/terraform/` provisions a VPC across three availability zones, an EKS cluster on a Kubernetes minor inside CNPG's support matrix, workers spread across the zones, ECR repositories, an encrypted S3 bucket for backups, and least-privilege workload IAM. State lives in a locked remote backend, and `terraform destroy` removes everything. |
| 7.2 EKS values and operators | 7.1 | Planned | The same vendored CloudNativePG chart and `values.yaml` install the operator on EKS; `infra/helm/shardshop/eks-values.yaml` renders the shared chart and inventory with gp3 storage and one instance of each shard per zone; the step 1.3 and 2.1 topology drills pass on EKS. |
| 7.3 Cloud durability | 7.2 | Planned | Every shard and the ledger back up to S3 with continuous WAL archiving and pass a point-in-time restore; RabbitMQ runs three brokers with three-member quorum queues; runtime secrets come from AWS Secrets Manager. The local profile keeps its single broker and logical dumps. |
| 7.4 GitOps delivery and cloud acceptance | 7.3 | Planned | Argo CD applies the [database layer](ARCHITECTURE.md#database-change-management) and deploys the six applications from the EKS overlay, with images pulled from ECR by digest; all eleven architecture scenarios pass on EKS with recorded evidence and costs; the local lab still passes step 6.6 unchanged. |

The supported-version gate (milestone 0) and first replicated shard (1.3) must
pass before repeating database infrastructure. The first complete order demo is
4.8: accepting an order alone is not completion of the saga. Individual recovery
and failover steps retain their own acceptance gates.

## 2. Scope and target topology

[ARCHITECTURE.md](ARCHITECTURE.md) sections 1–3 define the scope, the six
applications, the [runtime topology](ARCHITECTURE.md#2-runtime-topology), the pod
counts and the shard layout. [VERSIONS.md](VERSIONS.md) defines the versions and
support windows. This implementation note is not in the architecture:

- After a promotion, the operator moves each `-rw` Service to the new primary.
  Open connections and transactions can still fail. See
  [CloudNativePG architecture](https://cloudnative-pg.io/docs/1.30/architecture/).

## 3. Modules, data ownership, and routing

[ARCHITECTURE.md](ARCHITECTURE.md#1-goal-and-module-boundaries) section 1 defines
the Maven modules and their dependency rules. Its
[section 3](ARCHITECTURE.md#3-shared-postgresql-sharding-and-replication) defines
schema ownership, routing contract version 2, ID generation, generator allocation,
regional placement and the shard inventory. The [README](README.md) lists the
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

| Concern | Planned choice |
|---|---|
| Java | JDK 25 or newer for build and tests (bytecode targets release 25); OpenJDK 25 application runtime; no vendor or exact-patch restriction |
| Local Kubernetes | Kubernetes 1.36.x on kind with a digest-pinned node image; `docker`, `kind`, `kubectl`, and `helm` come from the user's `PATH` (tested with Docker Desktop 4.92.0, kind 0.33.0, kubectl 1.36, and Helm 4.3.0) |
| Node layout | One control-plane node and three worker nodes; Docker Desktop VM with at least 12 GB of memory |
| Database lifecycle | CloudNativePG 1.30.1 initially, installed from its verified Helm chart, with upgrades before operator EOL; three independent three-instance quorum shard clusters and separate ledger storage |
| Database | PostgreSQL 18, reviewed patch 18.6, with maintained 18.x updates and pinned image digests |
| Application | Six Quarkus 3.40.1 applications and four library JARs (common, idgen, sharding and datasets), under five top-level Maven children; JVM mode, a scoped platform BOM, and supported branch upgrades |
| Persistence | Explicit JDBC repositories with PostgreSQL JDBC and Agroal; bounded pools per service, pod, shard, and endpoint |
| Identifiers | Explicitly pinned `de.mkammerer.snowflake-id:snowflake-id:0.0.2`; decimal strings on the wire and positive `BIGINT` in PostgreSQL; reviewed support-policy exception |
| Database change management | CNPG `DatabaseRole`, `Database` and `Publication` resources for roles, schemas and CDC publications; the official Flyway OSS CLI image (13.8.1 initially), pinned by digest, for one migration stream per schema under `database/`, run as Kubernetes Jobs by that schema's migrator |
| Messaging | Stable RabbitMQ 4.3 or later (native quorum delayed retry) with its bundled Erlang/OTP. Step 4.2 installs a release inside its support window, and VERSIONS.md and the lock record it. The [architecture's retry/DLQ contract](ARCHITECTURE.md#6-rabbitmq-and-delivery-reliability) applies |
| Change data capture | Debezium Engine and its PostgreSQL connector (`pgoutput`), embedded in the order service with one reader per shard and offsets in each shard database (JDBC offset store); pinned and qualified under VERSIONS.md in step 4.3 |
| Deployment packaging | Vendored, verified Helm charts for third-party operators (the CloudNativePG chart 0.29.1 today); one ordered shard inventory and shared Helm templates with environment values for ShardShop's own manifests. The same inventory/templates serve the later cloud target |
| Cloud target (optional) | AWS EKS provisioned with Terraform in milestone 7, after the local lab passes step 6.6: an EKS overlay, the same operator charts, and Argo CD GitOps delivery for the database layer and the applications. No local step depends on it |
| Libraries | Quarkus platform-managed extensions and compatible JDBC/Jackson/logging/test libraries; qualify RabbitMQ and Debezium separately and audit inherited overrides |
| Build and testing | Command-line Maven 3.9.16 or newer (Quarkus plugin minimum), separately pinned GA build plugins, JUnit/Mockito/Testcontainers, and Kubernetes drills |

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
following implementation rules for later milestones, which add HTTP, persistence,
messaging and workload behavior.

1. Import `io.quarkus.platform:quarkus-bom:3.40.1` in the ShardShop parent and
   pin `io.quarkus.platform:quarkus-maven-plugin:3.40.1` separately. Configure
   augmentation/code generation only for the six applications. Keep common free
   of Quarkus dependencies. Idgen and sharding are ordinary library JARs. Their
   domain classes have no framework imports, and each library has its own `config`
   package for Quarkus wiring. Audit inherited dependency versions,
   test providers and plugin overrides against the effective Quarkus model.
   Each application uses Quarkus JVM `fast-jar` packaging. Ship the complete
   `target/quarkus-app/` directory, and launch it as the
   [README](README.md#build-and-run) shows. Native compilation is out of scope.
   [Maven tooling](https://quarkus.io/guides/maven-tooling/).
2. Use Quarkus lifecycle APIs, CDI constructor injection and SmallRye Config.
   Until its implementing step, a skeleton keeps its startup-and-exit smoke
   behavior. That step adds the long-running lifecycle of the service or workload.
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
   `versions.lock.yaml`, audit reports and the README only with newly verified
   versions, checksums and commands. [Testing](https://quarkus.io/guides/getting-started-testing/).

## 5. Local infrastructure details

The README describes the implemented
[cluster, storage and database sizing](README.md#local-kubernetes-cluster-operator-and-databases-steps-1121)
and the [generator registry](README.md#generator-allocation-at-jvm-startup-step-34).
These rules apply to the remaining steps:

- Use namespace `shardshop` and context `kind-shardshop`; scripts pass the
  context explicitly on every command. Keep the operator installation version explicit.
- Bootstrap secrets at runtime from environment input or generated credentials;
  commit references only. Separate migration, application, and CDC permissions.
  Workload apps receive HTTP configuration, never SQL credentials.
- Run applications in Kubernetes for the demo so Service DNS resolves normally.
  Keep database/broker endpoints internal; expose APIs by port-forward.
  Document separate port-forwards for IDE debugging if needed.
- Start infrastructure and services, run the idempotent product seeder Job, and
  wait for its successful completion before starting reader and order producer
  Deployments. Fail the startup gate if seeding fails. The default workload
  performs no continuous product writes after seeding.

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
   class and anti-affinity. Render with Helm, then apply ordinary Kubernetes
   resources; never add a directory or copied manifest for another shard.
3. Every pod meets the Kubernetes `restricted` Pod Security Standard (non-root, no
   privilege escalation, all capabilities dropped, `RuntimeDefault` seccomp)
   without fixed user or group IDs, which also satisfies OpenShift's restricted
   security context constraints. The Flyway migration Jobs are the one exception:
   their image and Jobs select UID/GID 10001. Label the `shardshop` namespace
   `pod-security.kubernetes.io/enforce: restricted` so Kubernetes rejects any pod
   that breaks this. Leave CloudNativePG's `podSecurityContext` unset: its
   defaults meet the profile, and under OpenShift constraints it lets pods
   inherit them.
4. Drills cause failures through the Kubernetes API (force-delete pods, cordon or
   drain nodes). A drill that must stop a whole node is a kind-only extra, kept
   separate from the portable drills.
5. Verify that RabbitMQ runs and keeps its data under an arbitrary user ID when
   step 4.2 deploys it.
6. Install third-party operators from their vendored, verified Helm charts with
   shared values in `infra/helm/`, so every cluster runs the same chart version
   and image pins; cluster-specific settings belong in that cluster's values file.

A move to OpenShift would still need a decision on the PostgreSQL operator:
community CloudNativePG does not support OpenShift, and EDB's certified operator
(API group `postgresql.k8s.enterprisedb.io`) is a commercial product outside the
community-only policy in [VERSIONS.md](VERSIONS.md).

## 6. Implementation boundaries

[ARCHITECTURE.md](ARCHITECTURE.md) sections
[4](ARCHITECTURE.md#4-product-generation-and-read-load),
[5](ARCHITECTURE.md#5-order-creation-and-ledger-saga) and
[6](ARCHITECTURE.md#6-rabbitmq-and-delivery-reliability) define the HTTP
contracts, the validation order, the saga, reconciliation, quarantine and message
delivery. The repository's [AGENTS.md](../../AGENTS.md) defines the code layers.
These implementation notes are not in the architecture:

- Keep an explicit shard handle from shard selection to commit. Do not use
  thread-local routing. Test concurrent requests for routing leakage.
- Before you release code that needs a schema change, verify the version of each
  schema owner on every shard.

## 7. Replication and backup policy

[ARCHITECTURE.md](ARCHITECTURE.md#3-shared-postgresql-sharding-and-replication)
section 3 defines quorum synchronous replication and logical-slot synchronization.
Its section 5 defines the audit for
[orders lost by failover or restore](ARCHITECTURE.md#orders-lost-by-asynchronous-failover-or-restore),
and [section 7](ARCHITECTURE.md#7-consistency-tradeoffs-and-verification) defines
the durability profiles. These implementation notes for steps 6.3 and 6.4 are
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

```text
microservices/shardshop/
  PLAN.md
  README.md
  ARCHITECTURE.md
  CHANGELOG.md                    # evidence and commands of the done steps
  VERSIONS.md                     # baseline, support evidence, update policy
  versions.lock.yaml              # verified artifact inventory, support dates, open blockers
  pom.xml                         # parent POM aggregator
  shardshop-core/                 # library POM parent and aggregator
    common/                       # shared validation and utilities, with unit tests
    idgen/                        # ID generation and its Quarkus wiring; product/order only
    sharding/                     # routing and its Quarkus wiring; product/order only
  shardshop-workload/
    pom.xml                       # workload POM aggregator
    shardshop-datasets/            # pure ID-free definitions; workload dependencies only
    shardshop-product-seeder/      # product seeder Job application
    shardshop-product-reader/      # read-load application
    shardshop-order-producer/      # order-load application
  shardshop-product/              # seller and product API
  shardshop-order/                # buyer and order API, saga, and stock reservation step
  shardshop-ledger/               # ledger consumer and result relay
  database/                       # Flyway streams: shard/catalog, shard/ordering, ledger/ledger
  infra/kind.yaml                # kind-only cluster configuration
  infra/shards.yaml              # only ordered list of shard names and immutable regions
  infra/helm/shardshop/           # shared infrastructure, migration and routing templates
    kind-values.yaml             # kind storage class and anti-affinity strictness
    eks-values.yaml              # milestone 7: EKS storage class and zone placement
  infra/helm/cnpg/                # vendored CloudNativePG chart and pinned values
  infra/images/flyway/            # Flyway migration image on the Canonical JRE
  infra/terraform/               # milestone 7: AWS infrastructure (optional)
  scripts/                       # up.sh (Kubernetes), migrate.sh (schemas), verification, backup, restore
```

ShardShop's parent aggregator is registered in `microservices/pom.xml` (step 0.2).
Tests obey the testing rules in [AGENTS.md](../../AGENTS.md). HTTP client tests use
in-process loopback servers. Local containers and Kubernetes drills run in
explicit integration profiles. Turn every architecture acceptance scenario into a
reproducible check.

The planned commands below run from the repository root after the module,
profiles, and scripts are implemented. Build only Shardshop or the affected
submodule; never run the repository's root Maven build for this project.

```bash
mvn -f microservices/shardshop/pom.xml clean verify
mvn -f microservices/shardshop/pom.xml -Pintegration verify
bash microservices/shardshop/scripts/up.sh
kubectl --context kind-shardshop -n shardshop get clusters.postgresql.cnpg.io -L shardshop.javacraft/region
kubectl --context kind-shardshop -n shardshop get pods,pvc,svc
bash microservices/shardshop/scripts/verify-topology.sh --routing-only
bash microservices/shardshop/scripts/verify-topology.sh
bash microservices/shardshop/scripts/verify-sagas.sh
bash microservices/shardshop/scripts/verify-failover.sh
```

Verification scripts must restrict mutations to this disposable lab and report
their scenarios. Keep destructive volume/cluster cleanup separate from startup
and verification. Supply explicit source/target arguments for backup and restore.
