# ShardShop changelog

This changelog records the evidence and the exact commands of each done step in
[PLAN.md](PLAN.md). Run the commands from the repository root. The
[README](README.md) runbooks explain them, and [VERSIONS.md](VERSIONS.md) keeps
the detailed version records.

## Step 0.1

**2026-09-28:** all seven image digest chains verify; the kind 1.36.5 node image
returns 404, so the user-authorized 1.36.4 fallback is pinned; digest-pinned
Canonical OpenJDK 25 images build the product and start all six applications as
non-root.

**Check:** recompute the [lock](versions.lock.yaml)'s digests as
[VERSIONS.md](VERSIONS.md#step-01-verification-record) describes.

## Step 0.2

**2026-09-28:** all six `clean verify` builds and JAR launches pass on OpenJDK 25
with 100% line coverage from an empty Maven cache; the build passes on JDK 25 and
26 and rejects JDK 21.

**Commands:** the per-application loop in the [README](README.md#build-and-run);
`mvn -B -ntp -f microservices/shardshop/pom.xml validate`.

## Step 0.3

**2026-09-28:** all 20 unit tests pass and `integration` runs `*IT` through
Failsafe; each application's effective POM matches the lock's 17 plugin pins,
overrides and test-image digests, with no prereleases.
[VERSIONS.md](VERSIONS.md#step-03-verification-record) has the detailed record.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -Pintegration,audit clean verify`
([README](README.md#dependency-and-test-validation)).

## Step 0.4

**2026-09-28:** the router tests pass with 100% line and branch coverage on JDK 25
and 26, with no warnings; step 2.4 later made the shard list configurable.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/sharding -am clean verify`.

## Step 0.5

**2026-10-04:** all six applications pass `-Pintegration,audit clean verify` on
Quarkus 3.40.1, Maven 3.9.16 and Java 25, with 62 unit/startup tests, 10 packaged
startup tests and 100% line coverage. The three plugin XML prereleases use the
version-policy exception. All six packages start as a non-root user on the pinned
JRE. **2026-10-06:** the builds have no warnings, and a review removed the unused
application Mockito dependencies.

**Commands:** the [README build loop](README.md#build-and-run) with
`-Pintegration,audit`; [qualification evidence](VERSIONS.md#step-05-verification-record).

## Step 1.1

**2026-09-29:** Docker Desktop 4.92.0 runs a 12 GB VM. The node image has 155
advisories (10 critical, 42 high), which the project accepts for this
loopback-only, disposable lab. `up.sh` creates four Ready v1.36.4 nodes and stops
on a smaller VM. A rerun keeps all volume data, and the `shardshop` namespace
enforces `restricted` Pod Security. **2026-09-30:** `down.sh` stops the nodes, and
`up.sh` starts them again with the same Clusters, PVCs and primaries.
[VERSIONS.md](VERSIONS.md#step-11-verification-record) has the detailed record.

**Commands:** `bash microservices/shardshop/scripts/up.sh` (twice);
`bash microservices/shardshop/scripts/down.sh`;
`kubectl --context kind-shardshop get nodes`.

## Step 1.2

**2026-09-29:** CNPG 1.30.1 supports the v1.36.4 server. Chart 0.29.1 matches its
index SHA-256 and its cosign signature, and `values.yaml` pins the operator by
digest. An uninstall does not remove the 11 CRDs. A rerun keeps the CRDs, the
operator and the `shard-a` identity.
[VERSIONS.md](VERSIONS.md#step-12-verification-record) has the detailed record.

**Commands:** `bash microservices/shardshop/scripts/up.sh` (twice);
`helm --kube-context kind-shardshop -n cnpg-system get manifest cnpg`;
`cosign verify` of the chart's `oci_reference` in the [lock](versions.lock.yaml).

## Step 1.3

**2026-09-29:** PostgreSQL 18.6 runs one primary and two quorum standbys (`ANY 1`)
on three workers, each with its own 2 GiB PVC. Logical slot synchronization is on,
with a 512 MB slot-WAL budget. `verify-topology.sh` confirmed replication to both
standbys, a synchronized failover slot and the data after a standby replacement.
Commits continued with one standby fenced and waited with both fenced.

**Commands:** `bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/verify-topology.sh shard-a`.

## Step 2.1

**2026-09-29:** nine Ready instances (three primaries and six quorum standbys) use
nine 2 GiB PVCs, with one instance of each shard on each worker. The drill of each
shard confirmed independent identities and replication only inside that shard, and
the scenario 2 topology checks pass. With 512 MiB limits, the nine pods used
563 MiB, without OOM events or restarts. This value needs a new measurement under
load. Steps 2.3 and 2.5 own the scenario 2 schema and grant checks.

**Commands:** `bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/verify-topology.sh <shard>` for each shard.

## Step 2.2

**2026-09-29:** `ledger-db` is a fourth, single-instance CNPG Cluster with its own
2 GiB PVC and internal `ledger-db-rw` Service, without synchronous standbys or
logical decoding. Restricted client Jobs connected over `verify-full` TLS and read
a committed row after a pod replacement. The server rejected a wrong password, and
the PVC, system identifier and credentials did not change. Step 2.6 owns the
schema and grants.

**Commands:** `bash microservices/shardshop/scripts/up.sh` (twice);
`kubectl --context kind-shardshop -n shardshop delete pod ledger-db-1`, then wait
until it is Ready.

## Step 2.3

**2026-09-30:** Flyway 13.8.1 Jobs on a trimmed OpenJDK 25 image (74 packages, no
Docker Scout findings) connect as `catalog_migrator` over `verify-full` TLS and act
as `catalog_owner`. The clusters disable superuser access. Read-only queries on all
nine instances gave identical ownership, grants and constraints, with no triggers
or functions.

**Commands:** `bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/migrate.sh` (reruns apply zero migrations);
see the [runbook](README.md#database-schemas-and-migrations-step-23).

## Step 2.4

**2026-09-30:** the shared chart renders all 26 earlier infrastructure resources
and the migration Jobs without change, and the 14 Cluster/PVC identities did not
change after a reapply. The chart rejects empty, duplicate, malformed or
`ledger-db` inventories. `migrate.sh` migrates one shard at a time, publishes
`shardshop-routing` only after that, and refuses a changed shard list. Product and
order fail startup without a valid shard list.

**Commands:**
`helm lint microservices/shardshop/infra/helm/shardshop -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml`;
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-order -am verify`;
`bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/migrate.sh`;
`bash microservices/shardshop/scripts/verify-topology.sh`.

## Step 2.5

**2026-10-01:** ordering V1 and its repeatable grants migrate as `ordering_owner`
on all three primaries. All nine instances have the 12 owner-owned tables, with no
custom functions, triggers or sequences. Restricted TLS client Jobs passed 597
assertions (371 expected rejections) for `order_app`, `product_app` and
`ordering_cdc`. Each `ordering_order_outbox` publication contains only outbox
inserts. A second migration run applied nothing, and the 35 Cluster/PVC/Secret
identities and credential hashes did not change.

**Commands:** `bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/migrate.sh` (twice);
`bash microservices/shardshop/scripts/verify-topology.sh --routing-only`; see the
[runbook](README.md#ordering-schema-and-catalog-access-step-25).

## Step 2.6

**2026-10-01:** ledger V1 and its repeatable grants migrate through
`ledger_migrator` as the non-login `ledger_schema_owner`. Seven tables, including
the history, have that owner, with no custom functions, triggers or sequences.
Restricted TLS `ledger_app` Jobs passed 129 assertions (90 expected rejections). A
review then changed outbox and quarantine inserts to column grants, and 99 more
assertions passed (60 expected rejections). Code review and mocked orchestration,
not a live failure, confirm that a failed ledger migration blocks routing
publication. A second migration run applied nothing in all seven Jobs. The step
did not run ShellCheck.

**Commands:** `bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/migrate.sh` (twice);
`bash microservices/shardshop/scripts/render.sh migration ledger-db ledger`;
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-ledger -am verify`;
see the [runbook](README.md#ledger-schema-and-replay-records-step-26).

## Step 3.1

**2026-10-06:** product/order OpenAPI 3.1.1 documents and order/ledger JSON Schema
2020-12 envelopes are self-contained, with inline examples.
`scripts/verify-contracts.py` validates their structure, examples and boundaries,
and all seven resources match their owner JARs. **2026-10-07:** step 3.5 replaced
fixture IDs with ID-free POSTs and service-issued IDs ([step 3.5](#step-35)).
**2026-10-09:** a review removed `BUYER_NOT_FOUND` from the order PUT response,
because only an existing buyer has allocations. `verify-contracts.py` passes again
with 63 inline examples, 51 response examples and 424 boundary cases.

**Commands:** [contract validation](README.md#http-and-message-contracts-step-31);
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-order,shardshop-ledger -am -Pintegration clean verify`.

## Step 3.2

**2026-10-06:** common replaces six parser copies and the duplicate digest code.
All affected builds pass 186 unit/startup tests and 10 packaged routing tests,
without warnings, with 100% line and branch coverage. Audit graphs confirm the
dependency boundaries, and all six packaged applications contain the common JAR.

**Command:** [six-application audit](README.md#dependency-and-test-validation).

## Step 3.3

**2026-10-06:** after the three libraries moved under `shardshop-core`, the
focused build and the six-application audit pass 215 unit/startup tests and 40
packaged startup checks, without warnings, with 100% line and branch coverage
across nine Java modules. Common runs 79 tests, idgen 30 and sharding 54.
Generator classes and Snowflake appear only in idgen and product/order classpaths,
dependency graphs and SBOMs; only product/order application packages contain them.
Compiler probes and Maven Enforcer reject idgen, sharding and Snowflake in ledger,
workloads and other unauthorized consumers, also as optional or transitive
dependencies. The idgen and sharding dependency graphs stay independent. The
scenario 10 library checks pass.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-order -am clean verify`;
[six-application audit](README.md#dependency-and-test-validation).

## Step 3.4

**2026-10-07, with review fixes:** 292 unit/startup tests and 48 packaged checks
pass without warnings, with 100% line and branch coverage. The live kind drill
reserves distinct IDs for concurrent starts and for a container restart in the
same pod. It rejects a stale identity and a counter rollback, and confirms the
narrow RBAC. The admission policy now matches the object name on collection
deletes, and `generator-registry.sh check` probes that case. The drill uses the
launcher's `--check-startup` mode. The retained high-water mark was 12, with the
original registry UID.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-order -am clean verify`;
`bash microservices/shardshop/scripts/verify-generator-allocation.sh`;
`bash microservices/shardshop/scripts/up.sh`;
[lifecycle and launch runbook](README.md#generator-allocation-at-jvm-startup-step-34).

## Step 3.5

**2026-10-07:** the six-application audit passes 559 unit/client/startup tests and
48 packaged startup checks without warnings, with 100% line and branch coverage
across all ten Java modules. Contract examples and 424 boundary cases pass, and the
packaged contracts match the sources. Only workloads package the dataset JAR;
direct, optional and transitive dependency probes reject it outside workloads. The
fixture check passes for all 150 seller, 300 product and 3000 buyer creation
payloads.

**Commands:** [six-application audit](README.md#dependency-and-test-validation);
[dataset check](README.md#workload-owned-datasets-and-service-issued-ids-step-35).

## Step 3.6

**2026-10-07, with review fixes:** the product/order audit and the final product
audit pass 649 unit/startup tests and 48 packaged startup checks, without warnings,
with 100% Java line coverage in all five affected modules. Disposable PostgreSQL
checks pass 74 migration and privilege cases, 163 OpenAPI-validated provider
responses on four shards (two US) and 18 default-TLS/restart responses. The
provider checks include empty fixed/chunked/unframed POSTs, malformed media, 32 KiB
boundary/oversized bodies, client disconnects and late completion after the
deadline. The product SBOM contains 237 components; its package contains no
workload dataset, Mockito or Flyway runtime.

On `kind-shardshop`, Flyway applied V2 and the corrected repeatable grants on all
three primaries through `catalog_migrator` acting as `catalog_owner`. A second run
validated all seven streams and applied nothing. All nine instances pass 207
read-only V2, ownership and permission checks, the deployed routing checks pass,
and V1 did not change. The order reservation role has no profit or credit access
until step 4.6. The allocation drill passed with `--check-startup`, consumed
exactly four slots (high-water 8 → 12), and kept the registry UID, the routing and
all 14 Cluster/PVC identities. Its temporary pods and images were removed.

**Commands:** [provider and lab runbook](README.md#seller-and-product-provider-step-36).

## Step 3.7

**2026-10-07:** the scoped audit build passes 717 unit/startup tests and 24
packaged product startup checks, with no warnings at the normal log level. The 48
seeder tests cover 100% of the seeder's 192 lines and 74 branches. On four
disposable shards, `verify-product-seeder.py` passed: a blocked run stopped after
59 sellers and 118 products, two later complete runs kept the same IDs and
digests, and the replica-profile run failed. One complete seeder JVM run took 3.9
seconds. The seeder POM and its dependencies are unchanged (147 SBOM components).
The step did not use the kind lab, because product deployment and the seeder Job
are step 3.9.

**Commands:** [seeder runbook](README.md#product-seeder-step-37).

## Step 3.8

**2026-10-08:** the six-application audit, with its libraries, passes 872
unit/startup tests and 48 packaged startup checks, with no warnings. The 67 reader
tests cover 420 of 421 lines and 176 of 179 branches; the remaining items are the
warning for threads that do not stop in 10 seconds, race paths and a connect
timeout. The `ProductRequests` tests use a loopback HTTP server for connection
reuse, each status, invalid and oversized bodies, repeated attempts at the fixed
interval, refused connections, the deadline, the pool limit, the time to live,
idle closing and interruption. A Quarkus startup test rejects a 4.999 s deadline.
Common's strict JSON decoding moved into `JsonResponses`; the 195 common tests
cover all lines and branches. The reader adds Apache HttpClient 5.6.4, HttpCore
5.4.3 and HttpCore H2 5.4.3 (150 SBOM components, previously 147); the package
contains no Vert.x or Netty.

On four disposable shards, `verify-product-reader.py` passed twice, in 89 and 88
seconds. The second run: 200.0 requests/s; 16 `DEADLINE` reads during the pause,
the longest in 5019 ms; 528 `TRANSPORT` and 1219 `REPLICA_UNAVAILABLE` reads
during the restarts; 12629 reads in total. A previous run showed that, at 200
requests/s on loopback, the pool kept 5 connections after the first lifetime. The
step did not use the kind lab.

**Commands:** [reader runbook](README.md#product-reader-step-38).
