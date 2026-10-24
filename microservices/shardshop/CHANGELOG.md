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

**Commands:** the per-application loop in the [README](RUNBOOK.md#build-and-run);
`mvn -B -ntp -f microservices/shardshop/pom.xml validate`.

## Step 0.3

**2026-09-28:** all 20 unit tests pass and `integration` runs `*IT` through
Failsafe; each application's effective POM matches the lock's 17 plugin pins,
overrides and test-image digests, with no prereleases.
[VERSIONS.md](VERSIONS.md#step-03-verification-record) has the detailed record.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -Pintegration,audit clean verify`
([README](RUNBOOK.md#dependency-and-test-validation)).

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

**Commands:** the [README build loop](RUNBOOK.md#build-and-run) with
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
see the [runbook](database/RUNBOOK.md#database-schemas-and-migrations).

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
[runbook](database/RUNBOOK.md#ordering-schema-and-catalog-access).

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
see the [runbook](database/RUNBOOK.md#ledger-schema-and-replay-records).

## Step 3.1

**2026-10-06:** product/order OpenAPI 3.1.1 documents and order/ledger JSON Schema
2020-12 envelopes are self-contained, with inline examples.
`scripts/verify-contracts.py` validates their structure, examples and boundaries,
and all seven resources match their owner JARs. **2026-10-07:** step 3.5 replaced
fixture IDs with ID-free POSTs and service-issued IDs ([step 3.5](#step-35)).
**2026-10-09:** a review removed `BUYER_NOT_FOUND` from the order PUT response,
because only an existing buyer has allocations. `verify-contracts.py` passes again
with 63 inline examples, 51 response examples and 424 boundary cases.

**Commands:** [contract validation](shardshop-core/RUNBOOK.md#http-and-message-contracts);
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-order,shardshop-ledger -am -Pintegration clean verify`.

## Step 3.2

**2026-10-06:** common replaces six parser copies and the duplicate digest code.
All affected builds pass 186 unit/startup tests and 10 packaged routing tests,
without warnings, with 100% line and branch coverage. Audit graphs confirm the
dependency boundaries, and all six packaged applications contain the common JAR.

**Command:** [six-application audit](RUNBOOK.md#dependency-and-test-validation).

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
[six-application audit](RUNBOOK.md#dependency-and-test-validation).

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
[lifecycle and launch runbook](shardshop-core/idgen/RUNBOOK.md#generator-allocation-at-jvm-startup).

## Step 3.5

**2026-10-07:** the six-application audit passes 559 unit/client/startup tests and
48 packaged startup checks without warnings, with 100% line and branch coverage
across all ten Java modules. Contract examples and 424 boundary cases pass, and the
packaged contracts match the sources. Only workloads package the dataset JAR;
direct, optional and transitive dependency probes reject it outside workloads. The
fixture check passes for all 150 seller, 300 product and 3000 buyer creation
payloads.

**Commands:** [six-application audit](RUNBOOK.md#dependency-and-test-validation);
[dataset check](shardshop-workload/shardshop-datasets/RUNBOOK.md#workload-owned-datasets-and-service-issued-ids).

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

**Commands:** [provider and lab runbook](shardshop-product/RUNBOOK.md#seller-and-product-provider).

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

**Commands:** [seeder runbook](shardshop-workload/shardshop-product-seeder/RUNBOOK.md).

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

**Commands:** [reader runbook](shardshop-workload/shardshop-product-reader/RUNBOOK.md).

## Step 3.9

**2026-10-10:** the product audit build passes 651 unit/startup tests and 24
packaged startup checks, with no warnings at the normal log level. The new
`ProductMetricsTest` checks that product counts reads by route template on
`/q/metrics` and makes no metric series for raw paths. Product keeps 100% line
coverage (622 lines). The Micrometer Prometheus extension adds 14 runtime artifacts,
Vert.x codegen 4.5.34 among them (251 SBOM components, previously 237).

`build-application-images.sh` built the product, seeder and reader images. Two
builds from the same packages gave the same manifest digests. Docker Scout 1.24.0
found critical and high advisories only in two Go binaries: pebble of the JRE base
image and kubectl. The newest JRE base digest and kubectl 1.36.5 do not remove them.
23 reviewed OpenVEX statements cover them, and the three images pass the gate. The
copied kubectl matches the published checksum of the 1.36.5 release. Docker Scout
does not index 6 of the 12 Ubuntu packages of the JRE base, glibc among them. OSV
lists 14 Ubuntu advisories of medium priority for glibc, openjdk-25 and zlib, which
Scout does not report. `SBOM-AND-RUNTIME` stays open
([gap](VERSIONS.md#os-packages-without-an-advisory-check)).

On `kind-shardshop`, `verify-product-workload.py` passed in 6 minutes 53 seconds.
A held advisory lock made four seeder pods fail at `catalog-v1.EU.seller.10`. The
Job failed, and the reader stayed at zero replicas. Two complete runs recovered the
key-to-ID digest `97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`
and kept 150 sellers and 300 products. Each reader started after the completion of
its Job. Both product pods received reads within 5 seconds, and within 26 seconds
after each pod replacement. Each report interval had only `OK` reads at 200/s. An
earlier run with the same images and scripts also passed.

Cluster and PVC identities and the registry UID did not change. The generator
high-water mark went from 16 to 26 during development and the checks. With a 1-CPU
limit, CPU throttling slowed the product JVM starts, and the reader's startup
lookups failed. The 2-CPU limits removed this problem. After a product rollout, the
reader can still restart one or two times, because the cold product JVMs return
`503`. One run stopped because the clock of the Docker Desktop VM jumped and the
kubelets stopped their pod synchronization. A kubelet restart on each node recovered
the lab, and the next run passed.

**2026-10-11:** fixes of the validation findings. The Job controller adds `Complete`
14–196 ms after `SuccessCriteriaMet`, in a different update. Thus `deploy.sh` now
waits for `Complete` after the first condition, and `verify-product-workload.py`
waits for `Failed` after `deploy.sh` stops at `FailureTarget`. After the fixes,
`verify-product-workload.py` passed in 8 minutes 20 seconds. The validation and this
run moved the generator high-water mark from 26 to 29.

**2026-10-11:** revalidation. `helm --set-string` divides a value at each comma and
removes backslashes. On the lab, `deploy.sh 'reval-20261011-ok\'` rendered the Job of
the earlier run `reval-20261011-ok`, stopped the reader, and failed only at
`k create`. `render.sh` now gives the run name and the image arguments to Helm with
`--set-literal`, and the chart schema rejects such names before `deploy.sh` changes
the lab. The rendered manifests for valid input did not change. The revalidation
runs did not restart product, thus the high-water mark stayed at 29. Then the user
reopened the step, because the workload applications must own their lifecycle
([PLAN 3.9](PLAN.md#milestone-3-product-contracts-ids-and-workloads)).

**2026-10-11:** workload-owned lifecycle. The reader owns its start condition:
`CatalogWait` repeats the catalog resolution with SmallRye Fault Tolerance while an
entity is missing or product is unavailable, for at most 5 minutes. A response that
differs from the dataset stops startup at once. `deploy.sh` only renders and applies
the manifests, and the reader Deployment has one replica. The 73 reader tests pass.
In them, `CatalogWaitTest` shows that the reader sends no load while an entity is
missing, starts after the entity appears, stops after the maximum wait, and does not
repeat a conflicting response. The tests cover 449 of 450 lines and 183 or 184 of
187 branches, with the same remaining items as step 3.8: one race path depends on
thread timing. The audit build has no warning lines. The extension adds 16 runtime
artifacts, without Vert.x or Netty: the package went from 50 to 66 artifacts. The
SBOM went from 150 to 165 components, because it already listed
`commons-logging-jboss-logging` as a test dependency. After a Quarkus test
application stops, Quarkus adds `[Error Occurred After Shutdown]` to later ERROR
messages in the same test JVM, thus one log assertion checks only the start of the
message. The rebuilt reader image indexed 89 packages and passed the gate with the
same statements.

The lab nodes were stopped. After their start, the product container restarts moved
the generator high-water mark from 29 to 31. Then `verify-product-workload.py`
passed in 5 minutes 14 seconds. Two runs recovered the digest
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`. Both product
pods received reads within 5 seconds, and within 21 seconds after each pod
replacement. Each report interval that the check examined had only `OK` reads, at
186–200/s. The pod replacements moved the high-water mark from 31 to 33.

**2026-10-11:** fixes of the lifecycle validation.

- **Tests:** in a scratch copy, the wait ignored `503` and the lookup deadline, and
  all 73 reader tests still passed. New `ProductReaderTest` cases show that
  `503 CATALOG_UNAVAILABLE`, `503 READ_REPLICA_UNAVAILABLE` and a lookup after its
  deadline make the reader wait. A `500` stops startup at once. The new cases fail
  when the wait ignores these outcomes, and also when it repeats a `500`. The 77
  reader tests pass.
- **Logs:** the reader sets the Apache `HttpRequestRetryExec` logger to WARN. Against
  a closed port, the packaged reader logged 206 lines in 33 seconds before this
  change, and 14 lines after it: one line for each repeat.
- **Records:** VERSIONS.md and the lock now record the 16th runtime artifact,
  `commons-logging-jboss-logging` **2.0.0.Final**.
- **Lab:** the lab nodes were stopped. After their start, the reader with the old
  image repeated its lookups 7 times (`TRANSPORT`, then `503` from the cold product
  pods). Then it resolved the digest `97af5c77…`. In the next run, the reader
  started its load 5 seconds before its seeder Job completed. The seeder completed
  in its first pod, and all reads were `OK`.
- **New reader image:** the image passed the gate with 89 packages. On the lab, it
  logged one wait line for a `503`, resolved the same digest, and read only `OK` at
  181–200/s. The node starts moved the high-water mark from 33 to 38. The `deploy.sh`
  runs did not restart product.

**Commands:** [deployment runbook](infra/RUNBOOK.md#deploy-with-upsh).

## Step 3.10

**2026-10-12:** the new test-only module `shardshop-testing` holds 27 Cucumber 8.0.4
scenarios in five features, as the `*-testing` modules of the other projects in
this repository do. Failsafe runs them only with `-Pintegration`. They start the
packaged product, seeder and reader against one PostgreSQL 18.6 container with
the lab's catalog roles, and Flyway 13.8.1 applies `database/shard/catalog` through
its Java API as `catalog_migrator`. Product connects with `verify-full` TLS to four
shard databases (two US). The scenarios replace these checks:

| Feature | Scenarios | Replaced check |
|---|---|---|
| `CatalogMigration.feature` | 5 | `verify-catalog-migration.py`, `verify-catalog-lab.sh` |
| `ProductApi.feature` | 15 | `verify-product-provider.py` |
| `ProductTransport.feature` | 4 | `verify-product-transport.py` |
| `ProductSeeder.feature` | 1 | `verify-product-seeder.py` |
| `ProductReader.feature` | 2 | `verify-product-reader.py` |

The six scripts are deleted; their evidence in the entries of steps 3.6–3.9 stays.
These cases of the scripts were not moved, because other checks own them:

- The SHA-256 constant of V1: Flyway validates the checksums of the applied
  migrations at each `migrate.sh` run on the lab.
- The `lsof` connection counts and the paused product (SIGSTOP) of the reader check:
  connection rotation and the read deadline are client behavior, and
  `ProductRequestsTest` and `ProductReaderTest` check them against a loopback server.
  `ReaderConfigurationTest` checks that the packaged defaults are the time limits of
  the HTTP connection policy, and the reader scenario checks the 16 open connections
  of the warm-up in the pool counts of the reports.
- The OpenAPI schema validation of each response: the scenarios compare the exact
  bodies, and step 3.12 adds the schema validator.
- The rollback of one transaction after a duplicate key: that is PostgreSQL
  behavior, not a catalog rule.

Since step 3.9, the reader waits for the catalog. Thus the reader check without
replicas now starts the reader with a 10-second maximum wait and a 2-second maximum
backoff, and the reader check also shows that the reader does not read before the
seeder runs.

Five temporary mutations showed that the scenarios detect regressions: a stale
grant in `R__catalog_grants.sql`, a product that always returns `201`, product TLS
with `sslmode=require`, a seeder digest with swapped IDs, and a reader that does not
wait for `REPLICA_UNAVAILABLE`. 14 scenarios failed, each at the expected step. The
files were then restored.

The validation of the step found two gaps against the deleted scripts, both with
temporary mutations in a copy of the tree:

- Only 3 of the 15 product API scenarios checked the product log. A product that
  logged a WARN line for each rejected body and an ERROR line for each expired
  deadline passed all scenarios. Now a hook stops product after each scenario and
  checks the logs of all its JVMs, also the shutdown. The same mutation now fails the
  3 scenarios that send invalid input, oversized bodies and an incomplete body.
- No test checked the packaged defaults of the reader. A 300-second connection
  lifetime and a 30-second deadline each passed all tests. Now each of them fails
  `ReaderConfigurationTest`.

After these changes, the full ShardShop build with `-Pintegration,audit clean verify`
passed in 4 minutes: 884 unit/startup tests, 48 packaged routing checks and the 27
scenarios (2 minutes 15 seconds). The documented command of the module, with `-am`,
also passed. The product, seeder and reader SBOMs did not
change (251, 147 and 165 components); the SBOM of `shardshop-testing` lists 71
test-scope components. The only warning line is the
`Ryuk has been disabled` WARN line of Testcontainers.

Qualification: Testcontainers 2.0.5 is the platform version. The parent pins Flyway
13.8.1 for the tests, because the platform manages 12.0.0. The module imports
`cucumber-bom` 8.0.4 to replace the version ranges of the Cucumber libraries. The 39
artifacts that no other ShardShop module resolves matched Maven Central's SHA-1
files, and OSV listed no advisories for them. The PostgreSQL test pin moved to the
2026-10-07 build of `postgres:18.6-trixie`. Docker Scout reports 6 critical and 36
high advisories for it, which the user accepted for local test containers.
Testcontainers runs without its Ryuk reaper: Ryuk 0.14.0 publishes its port on all
host interfaces, runs privileged with the Docker socket, and contains Go stdlib
1.23.12 with 4 critical and 29 high advisories. A JVM shutdown hook removes the
container ([details](VERSIONS.md#step-310-integration-test-dependencies)).

The step did not use the kind lab.

**Commands:** [integration tests](shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers).

## Step 3.11

**2026-10-13:** product and order reserve their generator ID with Java code in their
own JVM. At each start, the eager CDI producer of `shardshop-idgen` reads the registry
ConfigMap and sends one JSON patch through the Kubernetes API with Apache HttpClient
5.6.4, the client of the reader. The patch tests the UID, the resource version and the
counter. The request uses the projected service account token, and the client trusts
only the service account CA. The registry, compare-and-set, retention and RBAC rules
stay. These items are deleted: `GeneratorLauncher`, `ApplicationProcess`,
`KubectlRegistry`, `ProcessCommandRunner` and their tests, the kubectl stage of the
application images, `vex/kubectl.openvex.json`, the kubectl image pin with its
advisory records, and `verify-generator-allocation.sh`.

Behavior changes:

- `KubernetesRegistry` bounds each request with a scheduled cancel at its timeout
  (at most 5 seconds) and reads the token file again for each request. A refused
  PATCH (401, 403 or 404) now stops the start at once, with a fixed message. A
  conflict, a policy denial, a server error or a lost response burns the candidate,
  as before.
- `shardshop.id.registry.uid` selects the reservation; `url`, `namespace`,
  `token-file` and `ca-file` have in-cluster defaults. A fixed
  `shardshop.id.generator-id` together with a registry UID stops the start, and a
  packaged JVM in a pod refuses a fixed ID. These rules replace the reservation pin
  `shardshop.launcher.reserved-generator-id` of the launcher.
- The product Deployment sets `SHARDSHOP_ID_REGISTRY_UID`. All six images start
  `quarkus-run.jar`, and product runs one JVM per pod.
- The generator producer has a later startup priority than the other startup beans.
  Thus an invalid routing snapshot or catalog configuration stops the start before a
  reservation, also in a crash loop. The launcher reserved before each JVM start, thus
  such a crash loop used one slot for each restart.

Tests:

- idgen has 158 unit tests (163 before). `KubernetesRegistryTest` uses an in-process
  HTTP server: the request format, the token of each request, the state validation,
  the 64 KiB response limit, each status, a lost response and the request timeout.
  One line has no unit coverage: the return of a successful reservation in the
  producer. The scenarios run it.
- Product has 28 and order has 27 packaged startup checks (24 each before). Tests for
  a fixed ID next to a registry UID (from the environment and from a file), a fixed ID
  in a pod, a non-canonical UID and a missing CA file replace the four tests of the
  launcher pin. Other tests show that an invalid routing snapshot (product and order)
  and an invalid catalog configuration (product) stop the start before the
  reservation.
- `GeneratorAllocation.feature` has 11 scenarios. Each scenario starts one
  unprivileged k3s v1.36.5 container and applies the generator objects of the chart,
  which `helm template` renders in the integration profile. The scenarios check the
  reservation at each start and restart, the generator ID of new sellers, six
  concurrent starts (IDs 1 to 6), the permissions of seven service accounts, the
  retention policy, and the failures: another registry UID, an exhausted, missing or
  unreadable registry, a refused PATCH, an untrusted CA, a host name that the server
  certificate does not name and an API server that does not answer. Product stopped
  5.6 seconds after it started against the paused API server. The scenarios replace
  the step 3.4 drill and use no lab slots.

Six temporary mutations in a copy of the tree showed that the tests detect
regressions. The scenarios failed for five of them, each at the expected step:

- An allocator without the UID check: product started with the UID of another
  registry.
- A refused PATCH that is retried as a conflict: the deadline message replaced the
  update failure.
- A generator that does not use the reserved ID: a seller had generator ID 1, not 2.
- A client that trusts every certificate: product started with the client CA.
- A client without the request deadline: product did not stop within 60 seconds.

A patch without the version and counter tests did not fail the concurrent scenario,
because the retention policy also rejects a counter that does not increase.
`KubernetesRegistryTest` failed for it, because it checks the exact patch body. The
unit tests also failed for the first two mutations. The copy was then deleted.

The validation of the step found three gaps, with mutations in a copy of the tree
and checks against a separate k3s container. The fixes are part of the step:

- No test checked the host name of the API server certificate. A client without the
  host name check passed all tests, because the k3s certificate names 127.0.0.1. A
  new scenario gives product an OpenJDK hosts file (`jdk.net.hosts.file`) with a name
  that the certificate does not name. The same mutation fails it now: product
  started and reserved an ID.
- Product reserved its ID before it checked its routing snapshot: an invalid routing
  file used one slot at each start. The three new packaged startup tests failed
  before the change of the startup priority, with the message of the API client.
- The permission table did not check `patch` on `shardshop-generator-identity` and
  `shardshop-routing`, as the deleted drill did. A Role that allows `patch` on
  `shardshop-routing` now fails the scenario at these cells.

After the fixes, the full ShardShop build with `-Pintegration,audit clean verify`
passed in 5 minutes 25 seconds, with the kind lab stopped: 879 unit/startup tests, 55
packaged routing checks and 38 scenarios (3 minutes 31 seconds). Before the fixes, it
passed in 8 minutes, with 52 packaged routing checks and 37 scenarios. The only
warning line is the `Ryuk has been disabled` WARN line of Testcontainers. Compared
with a build of HEAD, the SBOMs of product (251 to 254) and
order (146 to 149) add only httpclient5 5.6.4, httpcore5 5.4.3 and httpcore5-h2 5.4.3,
which step 3.8 qualified; idgen (143 to 149) also adds jackson-databind 2.21.7 and its
two Jackson artifacts. No component was removed. OSV listed no advisories for them on
2026-10-12 ([details](VERSIONS.md#step-311-generator-allocation-dependencies)).

`build-application-images.sh` built the product, seeder and reader images. Docker
Scout indexed 187 product packages (265 before), 70 and 89. The only Go binary is
`/usr/bin/pebble`, the pebble statements cover its findings, and `/usr/bin/kubectl`
does not exist in the product image. The product image was
`sha256:3995a73f6fcb38705cdb1e8c5a1fe44e28728c50215a8724349ac75a748c2992`. After the
fixes, the product image is
`sha256:94f6261486e7b2871f9615353956290587f178e231294fac5a61f709a59763f0`, again with
187 packages. The seeder and reader packages do not contain idgen, thus their images
did not change.

Lab run:

- `up.sh` started the stopped lab with the registry at high-water 38 and its original
  UID. The two old product pods first failed while the API server started, then
  their launchers reserved IDs 39 and 40.
- `deploy.sh step311-deploy` rolled out the new product image. The two new product
  JVMs logged `Reserved generator ID 41` and `42`.
- `verify-product-workload.py` passed in 197 seconds. Both runs recovered the
  key-to-ID SHA-256 `97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`,
  and both product pods received reads before and after each pod replacement. The
  replacement pods reserved the new IDs 43 and 44.
- The host example of the README reserved ID 45 through the kind API server, with a
  `kubectl create token` token and the CA of the kubeconfig. The high-water is now 45.
- At approximately 190 reads/s, each product pod used 17–23% of one core and 268 MiB
  of anonymous memory with one JVM (310 MiB with two JVMs in step 3.9).

Lab run after the fixes:

- The validation moved the high-water from 45 to 49. A container restart in the same
  pod (SIGTERM to the JVM) and a pod replacement each reserved a new ID.
- `up.sh` started the stopped lab, and the old product pods reserved IDs 50 and 51.
  `deploy.sh step311-fix` rolled out the new product image, and its two JVMs reserved
  IDs 52 and 53.
- A pod of the new image in a scratch namespace, with an invalid routing snapshot and
  the registry UID, stopped with the routing message. It used no slot.
- `verify-product-workload.py` passed in 194 seconds. Both runs recovered the same
  key-to-ID SHA-256, and the replacement pods reserved the new IDs 54 and 55. The
  high-water is now 55.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -Pintegration,audit clean verify`;
`bash microservices/shardshop/scripts/up.sh`;
`bash microservices/shardshop/scripts/build-application-images.sh product product-seeder product-reader`
(only `product` after the fixes);
`bash microservices/shardshop/scripts/deploy.sh step311-deploy` (`step311-fix` after the fixes);
`python3 -B microservices/shardshop/scripts/verify-product-workload.py`;
[generator runbook](shardshop-core/idgen/RUNBOOK.md#generator-allocation-at-jvm-startup).

## Step 3.12

**2026-10-13:** each module that owns a contract checks it in a Maven unit test, and
one script builds all images. These files are deleted: `scripts/verify-contracts.py`,
`scripts/contract-validation-requirements.txt`, `scripts/build-migration-image.sh`
and `shardshop-workload/shardshop-datasets/scripts/verify-datasets.py`, which
imported the contract script. Their evidence in the entries of steps 3.1 and 3.5
stays.

| Test | Count | Checks |
|---|---|---|
| `ProductApiContractTest` | 15 | The product OpenAPI document |
| `OrderApiContractTest` | 16 | The order OpenAPI document |
| `RecordOrderContractTest` | 11 | The RecordOrder schema and example, with the rules that connect it to the order OpenAPI examples |
| `LedgerResultContractTest` | 9 | The ledger result schema and its two examples |
| `DatasetSourcesTest` | 5 | The name pools, the name/email pairing, the buyer countries of each region with 250 buyers for each Asian country, the seller countries and the retained source hashes |
| `Contracts.feature` | 4 scenarios | The examples across modules and the 3,450 creation payloads of catalog-v1 and buyers-v1 |

On 2026-10-13, the user selected the platform-managed Vert.x 4.5.34 validator, cross-module
checks in `shardshop-testing`, and a `migrate.sh` that only requires a loaded image.

- `ContractDocument` and `ExampleIds` in the test JAR of `shardshop-common` load each
  document once and validate with Vert.x JSON Schema. A scratch harness ran the cases of
  `verify-contracts.py` against Vert.x and networknt 2.0.8. The helper corrects the
  four gaps of Vert.x that it found ([details](VERSIONS.md#step-312-contract-test-dependencies)):
  impossible `date-time` values, integral decimals with a negative scale, an
  exception for an empty email, and the relative server URL of the product contract.
- The tests keep all boundary values of the two scripts. On the current documents,
  they find the same 63 inline examples and 51 response bodies as the walk of the
  deleted script. The 64 examples of step 3.5 belong to an earlier version of the
  documents.
- New checks: each schema object against the JSON Schema 2020-12 meta-schema, path
  parameters, repeated operation IDs, duplicate YAML keys, and the agreement of type,
  outcome and `rejectionReason` of a ledger result.
- `CatalogApi` checks each product response of the scenarios against the product
  OpenAPI document: the document must define the status for the operation, and the
  body must match the schema of that status. The full run checks 189 responses (79 ×
  400, 40 × 200, 37 × 201, 15 × 503, 10 × 404, 7 × 409, 1 × 422). This closes the gap
  that the entry of step 3.10 records.

Temporary mutations in a copy of the tree showed that the checks detect regressions:

- 12 mutations of the contracts, the examples and a name pool failed 13 unit tests,
  each in the expected test: a wider price pattern, a stock maximum of 2147483648, a
  parameter example with a space, a 16-digit phone, a 101-character city, a status
  URL without its end assertion, a changed fingerprint, a changed amount, a timestamp
  without `format` (2026-02-30 and hour 25 were accepted), a decision after the
  result time, a recorded result with `rejectionReason`, and a changed email part.
- A changed product example price, a buyer email with two dots and a seller creation
  without status 409 failed three scenarios. The last one failed with "The contract
  has no status 409 for POST /api/v1/sellers".
- An error message of at most 10 characters failed 7 of the 15 product API
  scenarios, also for the raw HTTP requests and the incomplete body.

The copy was then deleted.

Image script:

- `build-application-images.sh flyway` builds `infra/images/flyway` with the tag of
  the pinned reference of the chart values, prints the inventory and the advisories,
  applies the gate of the application images, and loads the image into kind. It
  registers the digest on a node only if it is the pinned digest.
- Version 2 of `vex/jre-pebble.openvex.json` also names
  `pkg:docker/shardshop-flyway@13.8.1-openjdk25`. The migration image contains the
  same pebble binary, and its entrypoint is Java too.
- `migrate.sh` builds no image. After its topology guard, it checks that every node
  has the pinned migration reference.

Lab run:

- `docker start` started the stopped nodes. The two product pods restarted and
  reserved new IDs: the high-water moved from 55 to 57, with the same registry UID.
- `build-application-images.sh flyway`: Docker Scout indexed 74 packages and found
  only the Go stdlib 1.26.7 of `/usr/bin/pebble` (2 critical, 8 high, 3 unrated). The
  gate loaded the VEX document and passed. The build gave the reviewed config ID
  `sha256:f6a88d7ea657…`. `kind load` skipped all four nodes, because they already had
  this image ID from the import of 2026-09-30. Thus the digest check compared that
  import: manifest digest
  `sha256:19765ac553684c76f061d5ffb558874a6814951f91e11c6ad8b0d5bef39ce73b` on all four
  nodes.
- With a scratch values file that pins a digest that no node has, `migrate.sh`
  stopped at once with `Run scripts/build-application-images.sh flyway first`, and no
  migration Job changed. With the same file, the image script stopped before it
  registered the digest.
- `migrate.sh` ran the seven Jobs on the reviewed digest in 68 seconds. Each stream
  validated its migrations, all schemas were up to date, and the routing version
  `e1b347bc…` was published again.
- `down.sh` stopped the lab again.

The validation of the step found three gaps, and these fixes close them:

- No test checked that the buyers of EU live in all 27 countries. With all MT buyers
  moved to CY and a new manifest hash, all 26 dataset tests passed.
  `DatasetSourcesTest` now checks the buyer countries of each region, and the same
  mutation fails it with `EU buyers live in each country of the region`.
- The lab run above did not show a new import of the migration image. A second lab
  run did:
  - `docker start` moved the high-water from 57 to 59, with the same registry UID.
  - `crictl rmi` removed all names of the migration image on `shardshop-worker2`.
    Then `migrate.sh` stopped with `Run scripts/build-application-images.sh flyway
    first; shardshop-worker2 has no …`, and no Job or ConfigMap changed.
  - `build-application-images.sh flyway` imported the build only on that node. The
    node received `import-2026-10-13@sha256:19765ac5…`, the pinned manifest digest.
  - `migrate.sh` validated all seven streams on that digest in 53 seconds, and the
    routing did not change. `down.sh` stopped the lab again.
- `up.sh` and step 6 of the lab reset named only `migrate.sh` as the next step. They
  now name `build-application-images.sh flyway` first.

The full ShardShop build with `-Pintegration,audit clean verify` passed in 5 minutes
42 seconds, with the kind lab stopped: 935 unit/startup tests (879 before), 55
packaged routing checks and 42 scenarios (3 minutes 51 seconds). The only warning line
is the `Ryuk has been disabled` WARN line of Testcontainers. Compared with a build of
HEAD, only test-scope components were added, and none was removed: product 254 to 257
(`vertx-json-schema`, `vertx-openapi`, `snakeyaml`), order 149 to 167, ledger 143 to
161 and common 16 to 34 (also `vertx-core` and its Netty family of step 3.6),
datasets 13 to 16 (Jackson) and `shardshop-testing` 71 to 90. The idgen, sharding,
seeder, reader and producer SBOMs did not change, and the packages of product, order
and ledger contain none of the new libraries. The three new artifacts matched Maven
Central's SHA-1 files, and OSV listed no advisories for them on 2026-10-13.

**Commands:** `mvn -B -ntp -f microservices/shardshop/pom.xml -Pintegration,audit clean verify`;
`bash microservices/shardshop/scripts/build-application-images.sh flyway`;
`bash microservices/shardshop/scripts/migrate.sh`;
[contract checks](shardshop-core/RUNBOOK.md#contract-checks).

## Step 3.13

**2026-10-13:** `up.sh` builds the ShardShop reactor from current sources, qualifies
Flyway/product/seeder/reader images, provisions the lab and loads the images on
every kind node. The image-build implementation replaces
`build-application-images.sh`; migration and application deployment remain separate
until step 3.16.

- Prerequisites include Maven, Buildx and Scout. The script resolves the ShardShop
  POM from its own path and runs `clean verify`, including when invoked outside
  the repository. All four image builds and advisory gates finish before the
  first cluster operation or image load. The reviewed VEX statements and Flyway
  digest pin remain unchanged.
- Buildx writes each image's config ID to the invocation's temporary directory.
  Before loading, the local tag must still identify that build. Loading remains
  sequential per node; CRI manifest digests must agree across nodes, and Flyway's
  digest must match the chart pin before registration.
- The live run from `/private/tmp` passed 935 unit/startup tests and 55 packaged
  routing checks. Scout indexed 74 Flyway, 187 product, 70 seeder and 89 reader
  packages. Each image passed the existing critical/high gate with its reviewed
  pebble statements; the previously documented OS inventory limitation remains.
- An isolated source copy, with spaces in its path, added a marker field to
  `ProductApplication.java` and ran the same `up.sh`. The resulting image contained
  the marker in the compiled class. A test-only kind command stopped that run
  before cluster changes, and the original local image tags were restored.
  After inserting invalid Java into that copy, another real build failed at
  compilation before image or cluster operations despite existing packages and
  cached tags. The working tree's Java sources were never changed.
- Nine temporary command-fixture checks passed: missing Buildx, missing Scout,
  failed Maven, failed image build, failed SBOM, failed advisory inventory,
  rejected critical/high findings on the final image, scanner errors on that
  image, and a missing current application package. Every case returned nonzero
  without a kind/kubectl operation or stale-image load. These checks cover shell
  orchestration; the Maven and image checks above use the real tools.

The first live run started the stopped nodes, and the nodes received new IP
addresses. The kindnet process of the control plane kept `HOST_IP=172.25.0.2`, its
earlier address, but `shardshop-worker` now had this address and the control plane
had `172.25.0.5`. Thus kindnet handled that worker as its own node and added no route
to the worker's pod network `10.244.2.0/24`. CoreDNS runs on the control plane, thus
no pod on `shardshop-worker` could resolve a name. The ledger probe of `up.sh` ran in
`ledger-db-1` on that worker and failed. A restart of the ledger pod, of the
kube-proxy of that worker and of the worker itself did not change the control-plane
routes. The implementation then moved the ledger probe to the first shard primary on
another worker, and the final live run returned zero. But the standbys `shard-a-2`,
`shard-b-3` and `shard-c-2` did not stream ("could not translate host name"), and one
of the two product pods was not ready. CNPG still reported all clusters as healthy.

The validation of the step found these problems, and these fixes close them:

- The ledger probe is the same as before step 3.13: each primary probes its own `-rw`
  Service by name, thus the probe also needs cluster DNS on that node. A failure now
  names the pod. The README tells how to find and repair a missing kindnet route, and
  PLAN step 3.14 now requires a DNS and standby check after each node start.
- A new kindnet pod on the control plane received `HOST_IP=172.25.0.5` and added the
  route. `ip route del` removed the route to its own pod network that the earlier
  process had added. Then DNS worked on `shardshop-worker`, and all six standbys
  streamed again.
- Each `up.sh` run moved the `:local` tags to a new build. After that, the CRI used
  kind's `import-<date>@<digest>` name for the earlier images, did not find them and
  failed with `CreateContainerError`. Scratch pods of the deployed product image
  `sha256:94f62614…` and of an earlier reader image failed in this way on
  `shardshop-worker2` and `shardshop-worker3`. The removal of the import name did not
  help, because the CRI cache kept the reference. A tag did help. Thus `up.sh` now also
  tags each loaded image `sha256-<digest>` on each node. A one-time command gave this
  tag to the 17 earlier ShardShop images on each node. Then the product pod on
  `shardshop-worker` started and reserved generator ID 61.
- Two clean builds of the same sources gave different JAR files, thus each `up.sh` run
  loaded three new images. The ShardShop POM now sets `project.build.outputTimestamp`.
  Two clean builds in a scratch copy then gave identical `quarkus-app` trees for
  product, seeder and reader. COPY keeps the file times, but the BuildKit cache ignores
  them, thus an unchanged rebuild uses the cached image layer again. The exporter
  option `rewrite-timestamp=true` had no effect with the Docker driver.
- `--vex-author 'ShardShop image review'` also accepted the document authors
  `Not the ShardShop image review` and `ShardShop image reviewer`. With
  `^ShardShop image review$`, Docker Scout accepts only the exact author and stops for
  the two other authors.
- The README gives the duration of a rerun, PLAN step 3.20 no longer names the deleted
  script, and the commands below use repository paths.

After the fixes, `up.sh` ran two times from another working directory, with the lab
already started. Both runs passed 935 unit/startup tests and 55 packaged routing
checks without warning lines, and all four images passed the gate with the anchored
author pattern. The first run loaded the new images on all four nodes with their
`sha256-<digest>` tags and returned zero in 393 seconds. The second run built the
same three image IDs, `kind load` skipped all 16 node loads, and the run returned zero
in 217 seconds. Then DNS worked from a pod on each worker, each shard had two
streaming standbys, both product pods were ready, and the kindnet `HOST_IP` of each
node was the address of that node. A scratch pod of the deployed product image
started on `shardshop-worker2`. The registry kept its UID, with high-water 61/1023.
The lab remains running. The fixes did not run migrations or deploy a new application
release.

**Commands:**

```bash
# From the repository root: run up.sh from another working directory.
(cd /tmp && bash "$OLDPWD/microservices/shardshop/scripts/up.sh")
# Each primary probes its own -rw Service; this also needs cluster DNS on its node.
kubectl --context kind-shardshop --request-timeout=15s -n shardshop exec ledger-db-1 -c postgres -- pg_isready -h ledger-db-rw -t 5
# Each node needs a route to the pod network of each other node.
kubectl --context kind-shardshop get nodes -o 'custom-columns=NAME:.metadata.name,IP:.status.addresses[0].address,CIDR:.spec.podCIDR'
docker exec shardshop-control-plane ip route
# Repair: a new kindnet pod on the control plane gets the current node address.
kubectl --context kind-shardshop -n kube-system delete pod -l app=kindnet --field-selector spec.nodeName=shardshop-control-plane
bash -n microservices/shardshop/scripts/up.sh microservices/shardshop/scripts/deploy.sh microservices/shardshop/scripts/migrate.sh microservices/shardshop/scripts/render.sh microservices/shardshop/scripts/shards.sh
git diff --check
```

The failure checks of the implementation and of the validation used temporary
harnesses outside the repository, with command stubs and scratch copies. They are not
kept, because PLAN instruction 9 permits no new scripts.


## Step 3.14

**2026-10-13:** `up.sh` owns inventory rendering, cluster reconciliation and
allocator initialization/checking. Each invocation snapshots the chart, inventory,
environment values, kind configuration and vendored operator chart/values.
`generator-registry.sh` is removed; `render.sh` and `shards.sh` remain only for the
migration, deployment and verification callers that later steps retire.

- A missing kind cluster is created; a retained cluster starts only its stopped
  nodes. Incompatible node images, roles, counts, networking or port settings fail
  without recreating the cluster. Published and reserved shard membership, ordering
  and regions must match. Storage, placement, bootstrap, instance-count and
  PostgreSQL-major changes are rejected before infrastructure apply.
- After starting retained nodes, `up.sh` restarts kindnet to refresh its node
  addresses. Restricted, temporary pods prove DNS from all four nodes, including
  the control plane. Database checks require the desired PostgreSQL/operator images
  and resource quantities on every pod, real `-rw` connectivity, applied PostgreSQL
  parameters without pending restarts, exact streaming quorum standbys, and
  current-generation DatabaseRole/Database statuses.
- Only a cluster successfully created by this invocation can initialize allocator
  state, and only after admission dry-run denials establish protection. Creation
  uses `create`, then pins the returned registry UID in the immutable identity.
  Existing missing, partial, malformed or mismatched state fails with independent
  recovery instructions. Neither a rerun nor a failed initialization resets state.
- An unchanged full run against the populated `kind-shardshop` lab returned zero.
  Before/after snapshots matched every Secret UID and payload hash, PVC UID/volume,
  Cluster/Database UID, PostgreSQL system identifier, catalog row count and ID hash,
  and both allocator objects. The allocator retained UID
  `e9c89848-6b3f-43a2-97d0-8543b12b8914` and high-water 61.
- Fresh setup passed in the isolated `shardshop-314-fresh` cluster with its own
  kubeconfig. The source copy was in a path containing spaces; only the cluster
  name differed in `up.sh` and `kind.yaml`. Automatic initialization produced a new
  immutable identity and high-water 0 before any application deployment or ID
  allocation. All four node DNS probes, database checks and image loads passed.
- The next full run on that cluster removed all three helper scripts from the
  scratch copy, stopped exactly one worker, and changed every database CPU request
  from 250m to 275m and ledger `max_connections` from 100 to 110. Only that worker's
  Docker start timestamp changed. All ten pods converged; ledger reported 110 with
  no pending restart, and all six shard standbys streamed. The primaries finished
  their normal restart within the readiness deadline without manual recovery.
  Secret hashes, volume/database identities and allocator state remained identical.
- All 36 temporary Bash 3.2 command-fixture cases passed, including topology and
  node mismatches, missing/partial/invalid allocator state, fresh-namespace API
  failure, removal of an immutable placement key, supported changes, stale or
  unapplied PostgreSQL parameters, pending restart and pod-list API failure.
  Ten additional live checks against the disposable cluster rejected changed membership,
  order and regions in both topology snapshots, a node-image mismatch, and existing
  missing/partial/mismatched allocator state without repairing it. Admission-cache
  propagation required a bounded wait in the fault-injection harness. The disposable
  cluster was then deleted; the populated lab's allocator was never modified by
  these fault injections.

The first final whole-cluster resume attempt stopped during the product image
advisory gate: Docker Scout reported an EOF from its GraphQL API after five
attempts. A credential-free reachability check also timed out. The full retry
passed Maven again, then Scout requested Docker sign-in during Flyway's advisory
inventory. Both runs returned nonzero before starting any node or loading an
image. The normal `up.sh` build and scan gates remain unchanged; a fresh full
rerun requires working Scout connectivity/authentication.

To complete the infrastructure acceptance and restore the retained lab, a temporary
copy exercised the unchanged cluster stage against all four stopped original nodes.
Its build calls checked the exact four Docker image IDs from the earlier successful
full qualification, rejecting any differing tag, then used those qualified images.
It did not rebuild or claim a new scan. That focused resume returned zero: kindnet
restarted, DNS worked from all four nodes, desired database state and all six
streaming quorum standbys passed, and both existing product pods became Ready.
Every Secret UID/payload hash, PVC, Cluster/Database UID, PostgreSQL system identifier,
and all 150 seller/300 product ID hashes matched the original snapshot. Registry
and identity UIDs stayed unchanged; high-water advanced from 61 to 63 for the two
restarted product JVMs. No temporary DNS pods remained, and only the retained
`shardshop` kind cluster remained running.

Each completed full run passed 935 unit/startup tests and 55 packaged routing
checks through the scoped ShardShop `clean verify`, followed by all four image
advisory gates. Shell syntax, Helm lint and whitespace checks passed. The temporary
harnesses and state hashes were kept outside the repository; no new maintained
script or generated report was added. SQL migrations and application release
deployment remain step 3.16.

**Commands:**

```bash
# Normal reconciliation, also valid from any working directory.
bash microservices/shardshop/scripts/up.sh
# Retained whole-cluster stop/resume qualification.
bash microservices/shardshop/scripts/down.sh
(cd /tmp && bash "$OLDPWD/microservices/shardshop/scripts/up.sh")
# This Maven command is run by every up.sh invocation, never the root reactor.
mvn -B -ntp -f microservices/shardshop/pom.xml clean verify
helm lint microservices/shardshop/infra/helm/shardshop -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml
bash -n microservices/shardshop/scripts/*.sh
git diff --check
# Temporary orchestration and live rejection harnesses used for this qualification.
python3 /private/tmp/shardshop-314-review/verify.py
python3 /private/tmp/shardshop-3.14-live-guards.py
# Focused whole-cluster resume after the external Scout failure (qualified IDs checked).
bash /private/tmp/shardshop-3.14-focused-resume.sh
```

The isolated configuration run used its own `KUBECONFIG` and
`SHARDSHOP_ENV_VALUES=/private/tmp/shardshop-3.14-supported-values.yaml` with the
scratch copy's `scripts/up.sh`; that values file preserved kind storage/placement
and set `postgresql.resources.requests.cpu: 275m` and
`ledger.parameters.max_connections: "110"`. The snapshot command was
`python3 /private/tmp/shardshop-3.14-snapshot.py <output.json> <context>`;
it stores hashes, never Secret values.

**2026-10-13 review fixes:** Database rollout checks now default each omitted
resource request to its limit, matching Pod admission while retaining explicit
requests, including zero. `up.sh` exports the current kind endpoint into the
caller's persistent kubeconfig using kind's normal `KUBECONFIG` handling, so
subsequent migration, deployment and kubectl commands retain access after it exits.

A temporary regression harness executes the old and updated source snippets.
Of 16 resource cases, the old code incorrectly rejects seven valid limits-only
or partially specified request configurations; the updated code accepts them and
continues to reject mismatched resources. Four kubeconfig cases use the real kind
exporter with isolated files: missing context, stale context, a stale context in
a multi-file configuration, and a missing first file. In each case the old child
can reach `/readyz` but the caller cannot after it exits; the updated child and
caller both succeed, with unrelated contexts and clusters preserved.

The scoped Maven build passes 935 unit/startup tests and 55 packaged routing
checks. All 36 existing infrastructure fixtures, shell syntax, Helm lint and
whitespace checks pass. Updated read-only checks on the retained lab pass for
allocator identity/high-water (still 63), every database pod's desired resources
and parameters, and all six streaming quorum standbys. This verification uses
isolated kubeconfig files and read-only live checks; it does not repeat the full
image build/advisory or cluster stop/start qualification above.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml clean verify
python3 /private/tmp/shardshop-314-fixes/regressions.py --kind
python3 /private/tmp/shardshop-314-review/verify.py
bash /private/tmp/shardshop-314-fixes/live-readiness.sh
bash -n microservices/shardshop/scripts/*.sh
helm lint microservices/shardshop/infra/helm/shardshop -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml
git diff --check
```

## Step 3.15 — infrastructure verification foundation

**2026-10-13 (partial):** Refactored the step 3.14 lifecycle implementation from
672 to 233 lines of Bash. Helm now renders DNS probes, the fresh registry identity
and the JSON view of the snapshotted kind configuration. The opt-in Maven `kind`
suite owns configuration, node, retained-state, admission, DNS and database
assertions. Kubernetes and CNPG still reconcile resources; Bash sequences native
tools and retains image build/Scout/load gates. There is no embedded Python in
`up.sh`; host `jq` only selects inventory fields and CRI digests.

The initial scoped build now uses `clean install -DinstallAtEnd=true`, including
verification and caching the successful reactor artifacts locally for subsequent
module-only Failsafe gates. A direct Failsafe reactor invocation after `verify`
alone failed to resolve the common test JAR and other reactor dependencies;
installation resolves that boundary without rebuilding at every gate. Native
JUnit tag expressions select scenarios through `shardshop.kind.tags`. A regression
showed that Cucumber's execution-time tag filter silently skipped all seven
scenarios for an unknown tag; JUnit discovery filtering now fails that case.

**Verification:** the scoped clean build and subsequent install both passed 962
unit/startup tests (including 27 new kind helper tests) and 55 packaged routing
checks. Six live scenarios passed on the retained lab: configuration, nodes,
retention, allocator admission, per-node DNS and database readiness. Negative
Maven runs rejected an unsupported API bind address, an unknown phase and fresh
bootstrap on a retained lab. A deliberately duplicated DNS probe caused a partial
create failure; cleanup removed the created Pod, confirmed with a bounded native
deletion wait. The new CRI digest query returned a consistent digest for all four
images on all four nodes and retained the reviewed Flyway pin. Shell syntax,
Helm lint, all new render modes and whitespace checks passed. The effective
`integration` POM excludes `KindIT`; normal builds ran no live scenarios.

The retained API briefly became unavailable during qualification and recovered
without intervention. Diagnostics showed API-server restarts, CPU pressure and
slow etcd reads, without OOM evidence; the precise termination trigger was not
established. The allocator UID remained
`e9c89848-6b3f-43a2-97d0-8543b12b8914`. Its high-water mark advanced from 63 to 65:
product logs confirm reservations 64 and 65 by two restarted JVMs before the live
suite. The checks used admission dry-runs and did not rewind allocation state.
No DNS probes remained after verification.

This qualification exercised the infrastructure gates against the retained lab;
it did not repeat full image build/advisory, fresh-cluster or stop/start acceptance.
The historical step 3.14 evidence remains above. Workload/deployment scenarios and
explicit disruptive drills are still required to finish step 3.15, now a
prerequisite for step 3.16.

**Commands:**

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml install -DinstallAtEnd=true
# Prepare the immutable snapshot as documented in README's local-cluster section.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
    failsafe:integration-test failsafe:verify -Dit.test=KindIT \
    -Dshardshop.kind.snapshot="$snapshot" \
    -Dshardshop.kind.tags='kind-config | kind-nodes | kind-retention | kind-allocator | kind-dns | kind-infrastructure'
# Temporary qualification fixtures, not maintained scripts.
python3 /private/tmp/shardshop-delegation/check-gates.py
python3 /private/tmp/shardshop-delegation/partial-dns-check.py
python3 /private/tmp/shardshop-delegation/check-image-digests.py
bash /private/tmp/shardshop-delegation/final-gates.sh
bash -n microservices/shardshop/scripts/up.sh
helm lint microservices/shardshop/infra/helm/shardshop --values microservices/shardshop/infra/helm/shardshop/kind-values.yaml --values microservices/shardshop/infra/shards.yaml
git diff --check
```

**2026-10-13 disposable-lab cleanup follow-up:** The user's 20:49 screenshot
showed a second four-node cluster, `shardshop-v314`, competing with the retained
lab. Scratch verification logs identify it as a separate step 3.14 fresh-cluster
run with failed/retried DNS checks. Docker Desktop's container events confirm
that its four nodes were deleted at 20:54:59–20:55:18 Europe/Samara, before this
follow-up inspection; this inspection did not perform those deletions. The
current Desktop daemon contains only the four original `shardshop` nodes, all
Ready, and no `kind-shardshop-v314` kubeconfig context. PLAN's verification
instructions now require owned disposable clusters to be cleaned up on success,
failure or interruption, with capacity planning before another full lab starts.

```bash
kind get clusters
docker ps -a --filter name=shardshop --format '{{.ID}} {{.Names}} {{.Status}}'
kubectl config get-contexts -o name
kubectl --context kind-shardshop --request-timeout=10s get nodes -o wide
```

## Step 3.16 — prerequisite audit

**2026-10-13 (blocked):** The requested implementation depends on step 3.15.
Inspection confirmed that the opt-in kind suite has seven infrastructure scenarios;
workload/deployment checks and explicit recovery drills still reside in
`verify-product-workload.py` and `verify-topology.sh`. PLAN instruction 2 requires
recording missing prerequisites instead of silently expanding the selected step.
Step 3.16 therefore remains unimplemented pending completion of step 3.15 or an
explicit change to that dependency. No cluster resources were changed by this audit.

Verified by reading the kind profile and `kind-features/Lifecycle.feature`, comparing
the two verification scripts, and running `git diff --check`.

**2026-10-13 numbering correction:** Swapped the two steps so the table follows
dependency order: step 3.15 now covers Maven live verification (formerly 3.16),
and step 3.16 covers migration and deployment through `up.sh` (formerly 3.15).
References in the plan, architecture, README and this changelog use the new
numbers. The prerequisite, implementation scope and completion statuses are unchanged.

## Step 3.15 — live verification completion

**2026-10-13:** Completed the Maven replacement of `verify-topology.sh` and
`verify-product-workload.py`, then removed both scripts. The `kind` profile now
defaults to three `kind-deployment` scenarios: deployed catalog and credential
checks, traffic through both product replicas with a fresh all-OK reader interval,
and routing/replication/worker/PVC checks across every inventory shard.
`kind-workload` and `kind-topology` select the respective subsets. Existing
infrastructure gate tags remain available to `up.sh`.

Explicit `kind-drill` selection runs two scenarios. The database drill verifies
standby read-only behavior, cross-shard isolation, persistent synchronized failover
slots, standby replacement with the same PVC, commits with one standby, and blocked
commits with both standbys fenced followed by recovery. The workload drill compares
saved seller/product IDs across two temporary HTTP seeder Jobs, replaces one product
pod, requires a generator ID above the pre-replacement high-water mark, then checks
traffic and a fresh clean reader interval again. `kind-database-drill` and
`kind-workload-drill` select these independently.

Both drills share an owner-scoped ConfigMap lock and register recovery before
mutation. Cleanup restores fencing, joins or terminates the drill writer, removes
owned SQL objects/slots and temporary Jobs, and waits for recovery. Ordinary JVM
shutdown interrupts and joins the guarded drill before cleanup can run; failed
recovery retains the lock. Cleanup never rewinds generator allocation. Forced
termination still requires the documented manual recovery procedure.

**Verification:** the affected-module build passes 877 unit/startup tests, including
64 kind helper tests, plus 28 packaged product routing checks. Regression tests
failed before fixes for cleanup overtaking an in-flight mutation and for rejecting
the valid zero-read baseline of a fresh product pod, then passed afterward. The
default live suite passed all three scenarios, both explicit drills passed, and
the default suite passed again after recovery. The default selection exercised no
disruptive scenario. An unknown tag fails discovery, and the effective Testcontainers
`integration` profile still excludes `KindIT`; normal builds ran no live scenarios.

Qualification used the existing `shardshop` cluster and already-qualified deployed
images. All ten PVC UID/PV bindings and both allocator ConfigMap identities were
preserved. The workload replacement consumed one generator ID, advancing
`highWaterMark` from 65 to 66. All database clusters recovered healthy, no fence,
probe schema/publication/slot/writer, temporary drill Job or drill lock remained,
and the reader was restored from the qualification replica to its original zero
replicas. Only the original four-node cluster remains. This run did not repeat
image qualification, fresh bootstrap, full stop/resume or the Testcontainers suite.
Step 3.15 is Done; its prerequisite blocker on step 3.16 is resolved, and 3.16 is
Planned.

**Commands:**

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am install -DinstallAtEnd=true
# Prepare $snapshot from this lab's inputs using README's kind snapshot instructions.
# Both suites require an already-deployed reader; qualification restored its original replicas afterward.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
    failsafe:integration-test failsafe:verify -Dit.test=KindIT \
    -Dshardshop.kind.snapshot="$snapshot"
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
    failsafe:integration-test failsafe:verify -Dit.test=KindIT \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-drill
# Repeat the default command above after the drills.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration \
    help:effective-pom -Doutput=/private/tmp/shardshop-315.jgDezv/integration-effective-pom.xml
# Expected failure: no scenario matches this tag.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
    failsafe:integration-test failsafe:verify -Dit.test=KindIT \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-missing-test-tag
bash -n microservices/shardshop/scripts/*.sh
git diff --check
```

### Step 3.15 correctness review fixes

**2026-10-13:** Fixed the three review findings. Database fencing and recovery now
use atomic JSON Patch tests of the Cluster UID and resource version, preserving
an operator fence changed between observation and mutation. A conflict stops the
mutation; failed recovery keeps the shared drill lock. Reader interval and total
reports now include the digest of the exact resolved catalog passed to `start()`.
The live verifier reads a bounded recent log window, so accumulating logs and
rotation of the startup summary do not remove its catalog evidence. Workload
credential checks now accept only documented literal HTTP settings and reject
generic configuration, JVM options, command overrides and executable hooks/probes.

Regression tests failed before the fixes for initial/restoration fence races,
oversized and rotated reader logs, missing periodic catalog evidence, and
credential injection. The affected-module build then passed **892 unit/startup
tests**, including **77 kind helper tests**, and **28 packaged routing checks**.
Both selected packaged-reader Testcontainers scenarios passed, including catalog
resolution, matching report digests, load, product restart recovery, shutdown and
the startup-failure deadline; the other 40 Cucumber scenarios were not selected.
A Kubernetes server dry-run accepted the conditional fence patch and rejected a
stale version without changing the live fence. `git diff --check` passed.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration \
    failsafe:integration-test failsafe:verify -Dit.test=CucumberIT \
    '-Dcucumber.filter.name=^The reader (waits|stops).*'
git diff --check
```

These fixes did not rebuild or deploy lab images or rerun the disruptive live
drills. Rebuild and deploy the reader image to provide periodic digest evidence
on kind; the existing reader remains scaled to zero.

## Step 3.16

**2026-10-13:** `up.sh` now migrates and deploys the images from its current
qualified build. It verifies each node's CRI image ID against the build ID as well
as requiring a consistent manifest digest; all images load before migration Jobs
or the new application release. Helm snapshots SQL/TOML into immutable ConfigMaps
and defines ordered, content-addressed Jobs: catalog on every shard, ordering on
every shard, then ledger. The retained topology reservation is checked before SQL,
and current-generation CNPG publications must pass before routing is published.

Helm derives application release and rollout identities from the desired images,
effective configuration, topology, dataset and owned chart versions. Normal startup
needs no run argument. The owned chart is now version 0.2.0; static application or
migration Pod/Job template changes must increment it. Identical releases reuse
completed seeding and leave unchanged pod templates in place. Migration execution
identity also includes Cluster and Database UIDs. Before a different migration
revision can run, its older owned terminal Jobs are removed, preventing A → B → A
inputs from reusing stale success evidence. Matching active Jobs are retained;
only terminal failed Jobs with verified ownership and desired templates are
deleted for retry. A local directory lock serializes `up.sh` invocations.

Six opt-in Java gates observe reservation, Job ownership/attempts, terminal
migration and seeder results, and publication generations. Native Kubernetes waits
own rollout/Job status, Flyway owns validation/schema changes, and the applications
still own catalog seeding and reader gating. The compatibility helpers remain until
step 3.20. Step 3.17's final startup deployment acceptance remains separate;
`up.sh` currently waits for successful seeding without claiming the full reader
traffic acceptance. Reused migration Jobs trust retained database state; README
documents forcing fresh Flyway execution after manual database changes/restores.

**Verification:** two complete `up.sh` runs passed image qualification and 1,035
unit/startup tests plus 55 packaged product/order routing checks each. The second
run started from `/private/tmp` and preserved all 60 Pods, 36 Jobs, both Deployment
identities/templates/generations, ten PVC bindings, four Clusters, four Databases,
and allocator high-water 68. The first run advanced the previously deployed
product/reader digests to the current source builds; the new reader's recurring
catalog digest and clean 200 requests/s intervals were observed. A simultaneous
startup was rejected by the lock.

Focused qualification of the new phases reused those already-qualified image
digests. Copied catalog repeatable migrations first changed successfully, then a
copied retained ordering V1 checksum failed Flyway validation on shard-a. Both
application deployments/pods, the later migration Jobs and allocator remained
unchanged. Restoring the original SQL resumed safely: each catalog's repeatable
history advanced from rank 4 to rank 6 with its original checksum 1634557694,
proving A → B → A executed again. The pending supported pool-size configuration
then rolled product and reader on the same images and seeded the same catalog IDs.
The checked-in configuration was restored afterward.

The final default kind suite passed all three deployment scenarios. All 21 new
focused tests passed again after declaring the already-qualified, platform-managed
SnakeYAML dependency directly. A controlled loader regression accepted a wrong
CRI image ID before the fix and rejected it afterward; four command fixtures
covered completed reuse, retaining an active timed-out Job, terminal failure retry,
and rejection before mutation. Helm lint, shell syntax and whitespace checks pass.

The qualification-only seeder Job, failed/superseded migration Jobs and two copied
SQL ConfigMaps were removed. All databases are healthy, all ten PVC UID/PV bindings
and routing/reservation/allocator identities are preserved, and catalog IDs retain
digest `97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`.
The allocator advanced from 66 to 72 for the initial, configuration-change and
restoration product rollouts; unchanged startup consumed no IDs. The reader was
restored to its original zero replicas after verification. Only the original
`shardshop` cluster remains. This step did not repeat fresh bootstrap, full
stop/resume, the disruptive database drills or the Testcontainers suite.

**Commands:**

```bash
# Each startup runs the scoped clean install, qualification and all phase gates.
bash microservices/shardshop/scripts/up.sh
(cd /private/tmp && bash /Users/nikita.lipatov/projects/GitHub/JavaCraft/microservices/shardshop/scripts/up.sh)
# Temporary qualification snapshots/harnesses, not maintained entry points.
q=/private/tmp/shardshop-316.Gp7Exf
bash "$q/release-phases.sh" "$q/bad-migration" # expected Flyway checksum failure
bash "$q/release-phases.sh" "$q/configuration"
bash "$q/release-phases.sh" "$q/base"
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
    -Dtest=HelmReleaseTest,KindMigrationTest test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
    failsafe:integration-test failsafe:verify -Dit.test=KindIT \
    -Dshardshop.kind.snapshot="$q/base"
bash "$q/capture-state.sh" "$q/final"
python3 "$q/check-state.py" "$q/first.resources.json" "$q/unchanged.resources.json" \
    --mode unchanged --allocator-before "$q/first.allocator.json" --allocator-after "$q/unchanged.allocator.json"
helm lint microservices/shardshop/infra/helm/shardshop \
    -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml
bash -n microservices/shardshop/scripts/*.sh
git diff --check
```

## Step 3.16 native tool ownership

**2026-10-14:** replaced the earlier step 3.16 orchestration with Skaffold
2.25.0 builds and Helmfile 1.8.0 releases. `skaffold.yaml` owns the four Docker
artifacts and their content tags. `helmfile.yaml.gotmpl` orders the existing CNPG
release and the new infrastructure, migrations and application releases. The
owned chart is version 0.3.0. `up.sh` shrank from 311 to 218 lines; it retains
local bootstrap, snapshots/locking, Maven/Scout, credential/allocator bootstrap
and verified native kind image delivery. No replacement shell wrapper or Java
Job controller was added.

Helm runs weighted migration hooks in catalog/shard, ordering/shard, ledger
order, then waits for the application rollout and the current revision's seeder
hook. Flyway validates actual database history every time; the custom completed
Job cache, execution identities, desired/live Job comparison and retry cleanup
are removed. Immutable SQL ConfigMaps remain content-addressed. Successful
migration Jobs are deleted by Helm; failed migrations and completed seeders have
a 24-hour diagnostic TTL. Application pod templates exclude release revisions.
The product configuration checksum uses the complete rendered properties file,
including static defaults; the reader Deployment metadata identifies the current
seeder without forcing a reader restart.

The retained lab passed topology, allocator, infrastructure and migration-idle
preflight. Sixty-six matching resources transferred to the appropriate releases
using metadata-only patches guarded by observed UID and resourceVersion.
Namespace, Secrets, allocator state, PVCs, operator-created resources and legacy
Jobs stayed outside that transfer. Initial server-side infrastructure adoption
encountered an existing admission-policy field owner; ShardShop releases now use
Helm's three-way merge, while CNPG retains its existing server-side mode. No
forced field takeover was used.

**Verification:** two complete revised `up.sh` invocations passed **1,034
unit/startup tests and 55 packaged routing checks each**, built/scanned all four
Skaffold artifacts and verified their config IDs and consistent manifest digests
on every kind node. The unchanged run from `/private/tmp` preserved both
Deployment specs/UIDs, all 13 application/database pod UIDs, four Cluster UIDs,
four Database UIDs, ten PVC UID/PV bindings and allocator high-water 74. Both
seeder attempts reported the retained catalog ID digest
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`.

Focused Helmfile syncs reused those qualified images. A copied catalog repeatable
changed successfully on all three shards, followed by a deliberate copied
ordering V1 checksum mismatch. Helm stopped at the first ordering hook; the
application release, both Deployment specs, application/database pod identities
and allocator state remained unchanged. Restoring the original SQL succeeded
without deleting the failed attempt or repairing history. Each catalog history
advanced from rank 6/checksum 1634557694 through rank 7/checksum 1181151422 to
rank 8/checksum 1634557694, proving A → B → A executes again. A supported
pool-size change then rolled only product on the same image digests; the reader
pod and catalog ID digest stayed unchanged.

The checked-in configuration was restored through Helmfile. The final default
kind suite passed all **three deployment scenarios**, including fresh clean
reader traffic through both product replicas. All four database clusters are
healthy; retained Cluster/Database UIDs, all ten PVC UID/PV bindings and allocator
identity are unchanged. Allocator high-water advanced 72 → 78 across the initial,
configuration-change and restoration product rollouts; the unchanged run consumed
no IDs. The reader returned to its original zero replicas after verification.
The deliberate failed qualification Job was removed after its logs were captured;
Helm history and already committed SQL remain intact. Only the original
`shardshop` kind cluster exists.

The full build includes the new Helm rendering and Java gate regressions. The
current-seeder lookup and complete-configuration checksum regressions failed
before their fixes and pass afterward. Helm lint, shell syntax, YAML validation
and whitespace checks pass. README, ARCHITECTURE, VERSIONS and PLAN now describe
native tool ownership, one-time adoption, fresh Flyway validation, hook retention
and interrupted Helm recovery. Step 3.16 is Done; step 3.17's automatic final
acceptance gate remains Planned. This refactor did not repeat fresh bootstrap,
full stop/resume, disruptive database drills or the Testcontainers suite; those
remain part of the subsequent workflow qualification.

**Commands:**

```bash
# Each startup includes the scoped Maven clean install, image qualification and phase gates.
bash microservices/shardshop/scripts/up.sh
(cd /private/tmp && bash /Users/nikita.lipatov/projects/GitHub/JavaCraft/microservices/shardshop/scripts/up.sh)
# Prepared temporary snapshots use already-qualified images; they are not entry points.
q=/private/tmp/shardshop-native.I1uViO
export SHARDSHOP_ROOT="$PWD/microservices/shardshop"
SHARDSHOP_SNAPSHOT="$q/bad" helmfile -f "$q/bad/helmfile.yaml.gotmpl" \
    -l stage=application sync --skip-deps --skip-needs # expected Flyway checksum failure
SHARDSHOP_SNAPSHOT="$q/configuration" helmfile -f "$q/configuration/helmfile.yaml.gotmpl" \
    -l stage=application sync --skip-deps --skip-needs
SHARDSHOP_SNAPSHOT="$q/snapshot" helmfile -f "$q/snapshot/helmfile.yaml.gotmpl" \
    -l stage=application sync --skip-deps --skip-needs
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
    failsafe:integration-test failsafe:verify -Dit.test=KindIT \
    -Dshardshop.kind.snapshot="$q/snapshot"
python3 "$q/check-unchanged.py" "$q/first.resources.json" "$q/unchanged.resources.json"
helm lint microservices/shardshop/infra/helm/shardshop \
    -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml
bash -n microservices/shardshop/scripts/up.sh
git diff --check
```

First/Last product-page navigation follow-up:

- The centered controls now read First, Previous, Page N, Next, Last. First
  returns to the selected region's first page; Last follows the remaining
  cursors to its current final page. Their disabled states match Previous/Next.
  Last resolves a concrete, shareable page URL within the existing eight-second
  deadline, and Refresh retries a failed jump.
- All 35 JavaScript tests pass, including appended pages, empty continuations,
  loading/error states, regional isolation, copied URLs and browser history.
  Scoped Maven verification now passes, including 269 product tests and 30
  packaged product startup checks, completing the earlier blocked check.
- Browser checks against a temporary loopback fixture confirm the centered
  layout, First/Last navigation in two regions, Back restoring the final page,
  and all four buttons disabled for an empty region. The lab was not redeployed.

```bash
node --test microservices/shardshop/shardshop-product/src/test/js/products.test.cjs
node --check microservices/shardshop/shardshop-product/src/main/resources/META-INF/resources/products/products.js
node --check microservices/shardshop/shardshop-product/src/test/js/products.test.cjs
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am verify
git diff --check
```

**2026-10-21 — product-page deployment cache repair:** The live HTML, CSS and
JavaScript matched the source, but each response advertised
`public, immutable, max-age=86400`. A browser reproduced new HTML using an older
cached script/style: First/Last stayed disabled, the left controls were misplaced,
and a copied page number was ignored. The page and assets now return `no-store`;
versioned script/style URLs also bypass assets cached under the old URLs.
README documents the one-time hard refresh needed for previously cached HTML.

The explanation below the table now has three labeled lines for stock, earnings
and currencies, without the fixed text-width limit that wrapped the old paragraph.
Browser checks with controlled data confirm First, Last, intermediate-page button
states, centered pagination and the revised text layout.

The two new HTTP regression cases failed on the original cache headers and pass
after the fix. All 35 JavaScript tests pass. Scoped Maven verification also passes
the packaged browser/assets scenario; the other 117 scenarios and unrelated unit
tests are intentionally filtered out. The running lab was inspected but not
redeployed by this repair.

```bash
# Regression baseline: two expected cache-policy failures before the fix.
mvn -f microservices/shardshop/pom.xml -pl shardshop-product -am \
  -Dtest=ProductPageTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dshardshop.failIfNoTests=false test
# Passing checks after the fix:
node --test microservices/shardshop/shardshop-product/src/test/js/products.test.cjs
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Pintegration verify -Dtest=ProductPageTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false \
  '-Dcucumber.filter.name=^The browser and its assets are included in the packaged provider$'
git diff --check
```

## Step 3.17

**2026-10-14:** the application release now invokes `kind-release | kind-deployment`
through a native Helmfile `postsync` hook. Startup reports success only after the
qualified image/configuration, current Deployment generation and pod ownership,
runtime image digests, current successful seeder and ready product Service
endpoints pass, together with the existing routing, replication, credential,
catalog and fresh reader-traffic assertions. The reader stays running. `up.sh`
still has 218 lines; the gate is declared in Helmfile, and the native image-delivery
check now selects the platform manifest described below.

`kind-release` is a read-only observer requiring the invocation's `images.json`;
legacy standalone `kind-deployment` remains unchanged. Native Helm rendering
provides the intended configuration, and existing Kubernetes quantity handling is
reused. All observations have bounded waits and errors identify resource/status
mismatches without printing configuration or credentials. The Helmfile hook skips
acceptance after a failed Helm operation and propagates acceptance failures;
Helm itself can still report `deployed` when its later Helmfile gate fails.
Neither the observer nor startup rolls back committed SQL or repairs resources.
Explicit disruptive drills remain separate from normal startup.

Fresh-node qualification exposed two delivery prerequisites left by the Skaffold
migration:

- The old Flyway pin identified Docker's archive index, whose annotations include
  the tag. The retained `13.8.1-openjdk25` archive was `19765ac…`, whereas the same
  build exported with Skaffold's content tag was `19db99e…`. Both reference the
  identical platform manifest `96efa775…` and config `f6a88d7e…`. The native adapter
  now reads the exact tag's containerd target and verifies its manifest config
  against the scanned Docker build ID. The Flyway pin records that platform
  manifest; no executable image content changed. The original empty-lab attempt
  reproduced the rejection before SQL/application deployment.
- OpenVEX version 2 matched the earlier fixed image tags. A new metadata-only
  reader build was correctly blocked because its content tag had no matching
  product statement. Version 3 uses named Docker product PURLs without a tag;
  the ten CVEs, exact Go `1.26.7` subcomponent and Java-entrypoint justification
  are unchanged. The changed image passes the strict Scout gate. Controls with
  an unrelated product name or Go `1.26.6` still exit 2; updating a local alias
  alone failed against Scout's cached SBOM and was restored. No advisory gate
  was disabled or bypassed.

The fresh application also demonstrated that CRI `imageID` can identify the import
archive index while the pod requests its platform manifest. The first gate
rejected this valid runtime identity; the other three deployment scenarios passed.
The corrected observer reads that index from the selected kind node with a bounded
containerd command and accepts only a single manifest descriptor matching the
qualified digest. Foreign, multiple, nested or malformed descriptors fail closed;
direct manifest identities require no node inspection. Regression tests cover
both Deployments and the current successful seeder.

The compatibility `deploy.sh` reads the deployed Helm revision's qualified image
references and checks those exact registrations on every node. It no longer
requires the obsolete `:local` tags or a single CRI repository digest. Its removal
remains step 3.20; normal startup still uses Helmfile through `up.sh`.

Qualification used `/private/tmp/shardshop-317.oUnxgq` for temporary snapshots,
fixtures and logs; these are not maintained entry points. The original lab was
stopped while the owned `shardshop-verify-317-ounxgq` lab used a private kubeconfig.
The empty lab passed provisioning, migrations, seeding and all three existing
live deployment scenarios. After fixing the runtime-index observer, a native
Helmfile retry passed all four final scenarios. The retry preserved both
Deployment specifications, all 13 application/database pod UIDs, all four
Cluster/Database identities, ten PVC/PV bindings and allocator high-water mark
`2 → 2`, while running fresh no-op Flyway and idempotent seeder attempts. The
temporary cluster and its kubeconfig context were removed; retained nodes were
restored.

The production `down.sh` followed by `up.sh`, invoked from `/private/tmp`, then
passed **1,055 unit/startup tests**, **55 packaged routing checks**, all four Scout
gates, every lifecycle/release prerequisite and **all four final live scenarios**.
It printed the new success message and left one reader and two product replicas
running. All four original Cluster and Database UIDs, ten PVC/PV bindings and
allocator identity survived; its high-water mark advanced `81 → 85` across process
restarts. The legacy deployment script's extracted read-only lookup also verified
all three current Helm image references on all four nodes.

A temporary Skaffold build from the same reader recipe added only a qualification
label (`config 97a7627f…`, manifest `e6cd387d…`); all three Scout commands passed
with the reviewed VEX. The native image adapter delivered it to every node.
Helmfile deployed it together with product `shardshop.catalog.max-pool-size=9`;
the live ConfigMap showed `9` and all four acceptance scenarios passed. Restoring
the original image and configuration through Helmfile passed again.

A temporary ordering V1 checksum change then failed Flyway validation (database
`381577366`, fixture `1555494565`). Helmfile returned nonzero without syncing the
application or invoking final acceptance. The application revision, both
Deployment specifications, all 13 application/database pod UIDs and allocator
state were unchanged. Retrying the unmodified snapshot succeeded, without SQL
repair or rollback. A temporary seeder URL of `127.0.0.1:1` with a short Job
deadline failed its current revision's hook; final Maven acceptance was skipped.

A product readiness probe pointed at port `81` failed Helm's bounded rollout
wait (`Updated: 1/2`); final Maven acceptance was skipped. A separate reader
fixture requested `100/s` with five-second reports. Helm completed the release
and seeder, and the qualified-release, credentials/catalog and topology scenarios
passed, but the workload scenario timed out after 25 seconds waiting for a fresh
all-OK interval at the existing `180/s` floor. Helmfile returned nonzero despite
Helm status `deployed`, proving that the final gate controls startup success.

Focused verification passed **46 tests**: 16 release-observer regressions,
11 Helm rendering tests, four real-Helmfile orchestration tests with controlled
commands, and 15 existing deployment tests. The orchestration tests cover ordering,
final acceptance failure, failed application/seeder and failed migration. The live
failure fixtures above separately verified each cause against the retained lab.

Final restoration passed all four acceptance scenarios at application revision
`11`; migrations revision `9` and infrastructure revision `4` remained deployed.
Two product replicas and one reader are Ready. A fresh 30-second reader report
recorded **6,000 requests at 200.0/s, all OK**. Its catalog key-to-ID digest
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`
matches the original pre-step seeder. All four Cluster/Database identities and ten
PVC/PV bindings match the pre-step snapshot; allocator identity is unchanged and
its high-water mark advanced `78 → 90` across qualification restarts. Only the
four retained `shardshop` kind nodes remain. The two deliberately failed Jobs were
removed with UID/resourceVersion preconditions after recording their diagnostics;
unrelated Jobs and Helm history were preserved. Shell syntax and `git diff --check`
passed. Step 3.17 is Done.

**Correctness follow-up, 2026-10-14:** release acceptance now compares the complete
pod specification after normalizing known Kubernetes defaults, resource quantities
and the standard service-account projection and tolerations admitted to Pods.
It checks configuration volume bindings and mount paths as well as probes,
security settings, service accounts and command/environment overrides. An old
ConfigMap mounted by a current, Ready pod can no longer pass using the desired
checksum annotation and a separately correct ConfigMap. Unknown additions fail
the comparison; the service-account exception requires the exact standard volume
and mount and does not apply to the HTTP-only workloads.
Four chart-based regressions failed against the previous comparison and pass
after the fix. A further regression checks stale configuration at each of the
Deployment, ReplicaSet and Pod boundaries. All **123 shardshop-testing unit tests**
pass with `mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test`.
All four live `kind-release | kind-deployment` scenarios also pass against the
retained lab, using its deployed image references and the current chart snapshot;
the verification changed no cluster resources. To repeat that read-only check,
prepare `$snapshot` with the README snapshot runbook, then run:

```bash
helm get values shardshop-application --kube-context kind-shardshop --namespace shardshop \
  --output json | jq -e '{images: .images}' > "$snapshot/images.json"
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  '-Dshardshop.kind.tags=kind-release | kind-deployment' \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.cluster=shardshop
```

Commands used from the repository root unless stated otherwise:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dtest=KindReleaseTest,HelmReleaseTest,HelmfileGateTest,KindDeploymentTest test
# Normal startup runs the full ShardShop clean install, scans and live gates.
bash microservices/shardshop/scripts/up.sh
```

The retained stop/start used these absolute paths from `/private/tmp`:

```bash
bash /Users/nikita.lipatov/projects/GitHub/JavaCraft/microservices/shardshop/scripts/down.sh
bash /Users/nikita.lipatov/projects/GitHub/JavaCraft/microservices/shardshop/scripts/up.sh
```

Temporary qualification commands (fixture files are outside the repository):

```bash
q=/private/tmp/shardshop-317.oUnxgq
bash "$q/fresh-lab.sh"
KUBECONFIG="$q/fresh.kubeconfig" \
  SHARDSHOP_ROOT="$PWD/microservices/shardshop" \
  SHARDSHOP_SNAPSHOT="$q/fresh-retry-snapshot" SHARDSHOP_FRESH=false \
  helmfile -f "$q/fresh-retry-snapshot/helmfile.yaml.gotmpl" \
  -l stage=application sync --skip-deps --skip-needs
bash "$q/run-fixture.sh" changed-build name=shardshop-application pass
bash "$q/run-fixture.sh" bad-migration stage=application fail
bash "$q/run-fixture.sh" snapshot stage=application pass
bash "$q/run-fixture.sh" bad-seeder name=shardshop-application fail
bash "$q/run-fixture.sh" bad-readiness name=shardshop-application fail
bash "$q/run-fixture.sh" bad-acceptance name=shardshop-application fail
bash "$q/run-fixture.sh" snapshot name=shardshop-application pass
```


## Step 3.18

**2026-10-15:** `down.sh` now acquires the same exclusive lifecycle lock as
`up.sh`, stops only running ShardShop nodes with an explicit ten-second Docker
grace period, and verifies every control-plane/worker container is stopped. One
60-second deadline covers discovery, stopping and verification; its configurable
range is 1–3600 seconds, plus up to two seconds to terminate an unresponsive CLI.
Missing and already stopped labs succeed without a Docker mutation. Ordinary
failure and INT/TERM release the lock after active commands end. `up.sh` takes
its lock before preflights and retains it while a foreground command finishes
after interruption. SIGKILL/host failures still require the documented stale-lock
inspection. All lifecycle entry points explicitly select Docker as kind's provider.

The user also requested `cluster-destroy.sh`, an explicit exception to the previous
exactly-two-scripts target. It shares the lock and permanently removes the fixed
lab, attached volumes and its selected kubeconfig entries, including all in-cluster
configuration, databases, Secrets and allocator history. It rejects shared volumes,
custom host binds and unrelated kubeconfig references before deletion. A private,
daemon-bound volume inventory survives failed cleanup, allowing safe retries after
the nodes are gone. It never prunes Docker or removes shared networks/images,
repository configuration, external backups or unselected kubeconfig copies.

Thirty deterministic Bash-command fixture tests cover complete/partial/repeated/
missing shutdown, Docker failures and false success, hung discovery/stop, INT/TERM,
up/down lock contention and preflight cleanup; destructive cleanup, repeat/missing
clusters, host/shared-volume guards, kubeconfig ownership, removal verification and
retry after volume cleanup failure (including wrong-daemon and reattached-volume
rejection). The original `down.sh` fails the lock regression: it calls kind and
Docker while a competing lifecycle owner holds the lock. The revised scripts pass.
The first sandboxed test run could not use macOS process inspection; the successful
runs used the normal host execution permissions. An initial Bash 3.2 fixture syntax
error was corrected before qualification.

Live shutdown ran while the retained reader sustained about 200 all-OK requests/s.
All four nodes stopped; a second shutdown succeeded with identical node IDs and
volume mounts. Native destruction was tested separately against the owned,
one-node `shardshop-318-destroy` cluster while the retained lab was stopped. The
test used a private kubeconfig with an unrelated context, an in-cluster ConfigMap
and a file in the node's persistent `/var` volume. A temporary copy of the actual
destroy script changed only its fixed cluster name. Destruction and a repeated
invocation succeeded; the node, its volume and owned kubeconfig entries were gone,
the unrelated context survived, and the recovery inventory was removed. A matching
temporary copy of `down.sh` then passed the live missing-cluster check. Cleanup was
registered before cluster creation and verified on exit. No retained cluster data
was destroyed.

The complete `up.sh` recovery passed its ShardShop-only clean install: **1,090
unit/startup tests**, including **153 shardshop-testing tests**, and **55 packaged
routing checks**, followed by image/advisory qualification and every live phase
gate. All four final `kind-release | kind-deployment` scenarios passed. The four
CNPG Cluster and Database UIDs, ten PVC/PV bindings, four Docker node IDs/mounts,
and allocator registry/identity UIDs match the pre-stop snapshot. The allocator
high-water mark advanced **92 → 94**, as required by the two restarted product
JVMs. The reader retained catalog key-to-ID digest
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`
and reported **6,000 all-OK requests in 30 seconds (200/s)**. Two product replicas
and one reader are Ready; only the four retained ShardShop kind nodes remain.
The lifecycle lock was released. Shell syntax and `git diff --check` pass.
Step 3.18 is Done.

Commands run from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dtest=KindStopScriptTest,KindDestroyScriptTest test
# Original-script regression, expected to fail on the native calls under the lock:
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dshardshop.directory=/private/tmp/shardshop-318-baseline \
  -Dtest=KindStopScriptTest#existingLifecycleLockRejectsUpAndDownWithoutTouchingItsOwner test
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/down.sh
bash /private/tmp/shardshop-318-destroy/qualify.sh
TMPDIR=/private/tmp/shardshop-318-destroy/tmp bash /private/tmp/shardshop-318-destroy/down.sh
bash microservices/shardshop/scripts/up.sh
bash /private/tmp/shardshop-318-live/compare.sh
bash -n microservices/shardshop/scripts/up.sh microservices/shardshop/scripts/down.sh \
  microservices/shardshop/scripts/cluster-destroy.sh
git diff --check
```

The disposable qualification script used `kind create cluster --name
shardshop-318-destroy --kubeconfig /private/tmp/shardshop-318-destroy/kubeconfig
--wait 120s` with the repository's pinned node image, and native `kind delete
cluster` in its EXIT cleanup. Temporary qualification files and logs remain outside
the repository; they are evidence of this run, not maintained entry points.


<a id="step-319"></a>
## Step 3.19 — Offline Java buyer generation and explicit cluster creation

**2026-10-15:** Replaced the Python buyer generator with development-only
`BuyerDatasetGenerator` in the dataset module's test sources. The explicit Maven
`regenerate-buyers` execution writes only `target/generated-datasets/buyers-v1`;
ordinary builds never regenerate source fixtures. It uses the retained name and
address pools, frozen country order and unsigned SHA-256 coordinates to reproduce
all three original TSVs byte for byte, with no network access or entity IDs.
Malformed/incomplete pools, exhausted unique emails and output symlinks fail
explicitly. Provenance now records the Java source hash and invocation; the source
pools and checked-in buyer TSVs are unchanged, and the generator is absent from
the production JAR. All **38 dataset tests** pass. An explicit offline Maven run
also reproduced the three files before the Python script was removed.

The additional user-requested `cluster-create.sh` separates fresh creation from
application deployment. It validates/builds the current safety suites, creates
kind and the database/credential foundation, initializes allocator identity only
on that invocation's fresh cluster, and verifies readiness without migrations or
application workloads. Existing clusters are a no-op; partial creation never
implicitly authorizes resetting allocator history. `up.sh` now requires that
foundation, resumes retained nodes, builds/scans current images, reconciles,
migrates and deploys. As requested, every `up.sh` invocation adds a fresh shared
Helm pod-template annotation for product and reader, even for an unchanged build;
`--restart` is an equivalent explicit alias. Each restarted product JVM reserves
a new non-reusable generator slot.
All four lifecycle commands share the existing exclusive lock. The remaining
compatibility helpers stay until step 3.20.

The testing module initially passed **163 tests**, including create/startup boundary and
failure fixtures, shared-lock contention and restart-only chart/schema checks.
Initial fixture failures were corrected: the mock registry now uses a valid UUID,
and the hung-stop timeout allows discovery to reach the deliberately blocked
command before verifying that the watchdog reaps it. The final input-copy fixture
also passed its focused eight-test startup suite.

Fresh first-start qualification exposed a retained guard that assumed kubectl's
last-applied annotation: new Helm-created CNPG resources have API defaults but no
such annotation. The fix reads the current infrastructure Helm revision's stored
manifest, validates ownership/identity, and retains exact protected declaration
and live-drift checks. Completed failed revisions can be retried; pending/changing
revisions and missing/ambiguous history fail closed. A regression run against the
old comparison failed for the reported reason; the corrected **14-test safety
suite** and a live retained guard check passed. The disposable failed lab was
removed and the retained lab restored with its original catalog IDs before retry.

The successful fresh qualification used the owned four-node
`shardshop-319-retry` lab and a private kubeconfig, with cleanup registered before
creation. Only the fixed cluster/context and the kind/Helmfile context settings
were changed in temporary script/configuration copies outside the repository.
The retained lab was stopped to keep only one full database lab running in the
12 GB Docker VM. `cluster-create.sh` finished with four healthy CNPG databases,
ten Bound PVCs, no application Deployments or migration/seeder Jobs, and allocator
high-water **0**. Repeated creation made no changes. The subsequent `up.sh`
passed its **1,117 unit/startup tests** (including **38 dataset** and **168 testing
module tests**), **55 packaged routing checks**, all four image/advisory gates,
retained-state guards, migrations, seeding and all four final deployment scenarios.
The temporary cluster, node volumes and kubeconfig context were removed. A live
startup attempt after that deletion correctly failed before the build and directed
the caller to `cluster-create.sh`.

Commands run from the repository root (temporary qualification helpers are not
maintained project entry points):

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml clean verify
mvn -o -B -ntp -f microservices/shardshop/shardshop-workload/shardshop-datasets/pom.xml \
  test-compile exec:exec@regenerate-buyers
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing verify
# The original comparison failed this regression; the fixed comparison passes:
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dtest=KindSafetyTest#helmOwnedInfrastructureAcceptsApiDefaultsUsingItsStoredDeclaration test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Dtest=KindSafetyTest test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.tags=kind-retention \
  -Dshardshop.kind.snapshot=/private/tmp/shardshop-319-guard/snapshot
bash /private/tmp/shardshop-319-retry/qualify.sh
```

The owned qualification wrapper runs the real `down.sh`, then temporary copies
of `cluster-create.sh` (twice) and `up.sh` for `shardshop-319-retry`. After deleting
that lab and verifying its cleanup, it runs the repository's ordinary `up.sh`
twice against `kind-shardshop`, without `--restart`. Each lifecycle build invokes
`mvn -B -ntp -f microservices/shardshop/pom.xml clean install -DinstallAtEnd=true`.
Native startup after deletion was checked separately with the temporary copy and
private kubeconfig; its expected error directed the caller to create the cluster.

Retained recovery and the subsequent unchanged-build run both passed every phase
and all four final acceptance scenarios. Product kept the same image; reader moved
from `sha256:530cdf60fc3346e2034611b120548cf2f43352dbe5ed93dc5c024c99d261dda1`
to `sha256:7450c1c46bf0d1837a464f8850d428ee78e91870674248b5c1502cbc824cb49f`.
The unchanged rerun replaced all **three application pod UIDs**, as requested.
The four node IDs/mounts, four CNPG Cluster and Database UIDs, ten PVC/PV bindings
and allocator registry/identity UIDs match the pre-stop snapshot. High-water
advanced **96 → 100 → 102** across recovery/deployment and the unchanged restart,
without rollback or slot reuse. The reader retained catalog key-to-ID digest
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`
and reported **6,000 all-OK requests in 30 seconds (200/s)**. Two product replicas
and one reader are Ready; only the original four kind nodes remain, and both
qualification and ordinary lifecycle locks were released.

A final offline generation reproduced all three TSVs byte for byte; source pools
and source resources have no Git diff, and the production dataset JAR contains
no generator class. `git diff --check` passes. Step 3.19 is Done.

### Lifecycle responsibility refactor after step 3.19

**2026-10-15:** At the user's request, infrastructure provisioning now belongs
exclusively to `cluster-create.sh`: namespace, missing credentials, operator and
database release reconciliation, and fresh-only allocator initialization. An
existing lab is guarded and reconciled instead of being a no-op; retained
allocator identity and admission protection must pass before provisioning.
`up.sh` builds/scans/loads current application images, migrates, deploys and
restarts application pods on every invocation. It can resume retained nodes and
observe readiness but never creates credentials/namespaces or syncs infrastructure.
The migration release retains its infrastructure readiness gate before SQL.

`scripts/lib/lifecycle.sh` shares the invocation lock, signal cleanup, snapshots,
rendering, Maven observers, memory check, node resume and DNS checks. The two
entry points now contain **105** and **62** lines respectively, with **123** shared
helper lines, replacing the previous **221 + 140** lines. The user's explicit
helper request supersedes the earlier plan restriction on sourced shell helpers;
the helper is not another lifecycle entry point.

Creation's `shardshop.infrastructureOnly=true` build selects only the observer
and common test-helper dependencies, without application reactor-order POMs.
Default and integration builds retain their original dependencies. Creation also
caches the two inherited parent POMs using nonrecursive `install:install-file`
calls from ShardShop; neither ancestor reactor is built. An isolated Maven cache
with no JavaCraft artifacts exposed the missing common test JAR and inherited POM
metadata, and the corrected scoped install plus standalone `kind-config` gate
passed with no application artifacts present. The scoped build passes **195 common
tests and 171 testing-module tests**. Boundary regression tests reject the old
infrastructure-writing `up.sh` and existing-cluster no-op behavior; fixture tests
also cover retained guards, credential preservation, restart tokens, shared locking
and interruption. A fixture's positional-argument parsing was corrected after
adding the shared kubectl timeout; the complete scoped build then passed.

The first live retained reconciliation passed without changing the three
application pod identities, credentials, allocator state or database/PVC identity.
Fresh creation and repeated creation then passed on the disposable
`shardshop-split-retry` lab: four database clusters, ten PVCs, no application
Deployments or Jobs and allocator high-water mark zero. Reconciliation preserved
the protected identities. Its first application startup passed all four final
acceptance scenarios without changing either infrastructure Helm revision.

The first disposable attempt exhausted its bounded DNS-probe deadline while one
worker was still pulling the pinned PostgreSQL image. Cleanup removed that lab and
restored the retained application; the second fresh attempt passed with the same
timeouts. A later retained startup failed safely at image inspection: Skaffold had
deleted the newly rebuilt reader image while building a subsequent artifact.
Docker events showed successful tagging followed by deletion, and the pinned
[Skaffold 2.25.0 implementation](https://github.com/GoogleContainerTools/skaffold/blob/v2.25.0/pkg/skaffold/config/options.go)
enables asynchronous pruning when artifact caching is disabled. Deterministic
image IDs and the fixed image creation timestamp allow that cleanup to select an
image reused by the current build. `up.sh` now explicitly enables artifact caching
with an empty invocation-local cache file. Every artifact still builds from current
Maven outputs, Docker layers remain reusable, and Skaffold pruning stays disabled.
The restart regression first failed against the old flag, then verifies unique,
initially absent cache files and their cleanup across repeated startup calls.

The final corrected `up.sh` passed **1,120 unit tests, 55 routing startup tests**,
all four image builds and advisory gates, and all four final live acceptance
scenarios. The private artifact cache reported a miss and built every artifact.
All three application pods were replaced, while both infrastructure Helm revisions,
the original four node identities and volume attachments, all ten PVC identities,
credential hashes, and retained allocator identity were preserved. The allocator
high-water mark advanced normally. The reader retained dataset digest
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`.
Two product replicas and one reader are Ready, all ten PVCs are Bound, disposable
labs and their volumes/configuration are removed, and the lifecycle lock is released.
Shell syntax checks and `git diff --check` pass.

Commands run from the repository root:

```bash
# Boundary regression before the implementation change:
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml -Dtest=KindStartupScriptTest test
# Scoped build, also run offline with an isolated repository lacking JavaCraft artifacts:
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true clean install -DinstallAtEnd=true
bash /private/tmp/shardshop-lifecycle-refactor/qualify.sh
bash /private/tmp/shardshop-lifecycle-refactor/retry/qualify.sh
bash /private/tmp/shardshop-lifecycle-refactor/retry/verify-final.sh
```

The temporary qualification wrapper registers cleanup before creating its owned
`shardshop-split-check` or `shardshop-split-retry` lab, uses a private kubeconfig and stops the retained lab
for Docker VM capacity. Temporary copies change only cluster identity/context and
input paths. It runs the real creation/startup workflows and restores the retained
lab, comparing protected identities, credential hashes, application pod identities
and infrastructure Helm revisions. The wrapper and its logs are local evidence,
not maintained project entry points.

## Step 3.20

**2026-10-15:** Removed `deploy.sh`, `migrate.sh`, `render.sh` and `shards.sh`.
The only maintained executable entry points under ShardShop are
`scripts/cluster-create.sh`, `scripts/cluster-destroy.sh`, `scripts/up.sh` and
`scripts/down.sh`. Creation and startup retain their non-executable sourced
implementation helper, `scripts/lib/lifecycle.sh`, as requested in the preceding
lifecycle refactor. No replacement wrapper or task runner was introduced.

README and architecture now describe native Helm previews, creation followed by
application startup, every-run application restarts, retained shutdown/resumption
and explicit destruction. Active compatibility-helper and Python prerequisites
were removed. The recovery runbook distinguishes retryable startup failures from
lost storage or allocator history; coordinated backup/restore remains steps
6.3–6.4. Chart comments, the Testcontainers rendering comment, version policy and
artifact-lock ownership/tag metadata follow the supported workflow. Historical
completed milestones and earlier CHANGELOG evidence remain unchanged.

The scoped Testcontainers build passed **998 unit/startup tests and 42 Cucumber
scenarios**, including allocator admission/TLS failures, HTTP contracts, persistent
idempotency, seeding/reader behavior and PostgreSQL interruption/recovery:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

Lifecycle inventory inspection confirms four executable scripts and the one
non-executable shared helper, with no other shell files under ShardShop outside
generated targets. No active references to retired script names remain outside
historical plan/changelog entries. Shell syntax checks and `git diff --check` pass.

The standalone default kind suite passed **three scenarios**, and explicit
`kind-drill` passed **both database and workload drills** on the retained lab.
Before/after comparisons preserved node and volume identities, database/PVC and
allocator identities, credentials and the catalog key-to-ID digest; the drill
lock was removed after recovery. These runs reused the current compiled observer
classes and installed dependencies from startup, without repeating a Maven build:

```bash
# $snapshot is prepared with the README's native Helm snapshot commands.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify \
  -Dit.test=KindIT -Dshardshop.kind.snapshot="$snapshot"
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify \
  -Dit.test=KindIT -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-drill
```

Two clean-bootstrap attempts exposed a cold-start deadline defect: all four DNS
probe Pods remained `Pending / ContainerCreating` during image pulls when the
150-second observer wait expired. The Pod's 120-second deadline included image
startup. A direct pull of the exact pinned PostgreSQL image also took several
minutes before completing. Both owned disposable labs were removed through
`cluster-destroy.sh`, and full `up.sh` runs restored the retained lab successfully.

The probe now bounds DNS execution itself to 120 seconds with the pinned image's
GNU `timeout`, and its overall Pod deadline allows 15 additional minutes for
cold startup. Native wait/request/process deadlines are 1050/1060/1070 seconds,
covering Pod termination grace. Every node must still reach `Succeeded` with the
same pinned image. Failure diagnostics include image waiting messages and process
termination details. The cached pinned image passed network-free success and
timeout smoke checks (exit codes 0 and 124).
The corrected fresh-cluster DNS gate passed on all four nodes in **431.3 seconds**,
including cold pulls; the previous 150-second wait could not cover this startup.

All **27 focused Helm/readiness tests** pass. The three new regressions first
failed against the previous implementation for the shared startup deadline,
insufficient observer wait and missing failure details, then passed after the fix:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml \
  -Dshardshop.infrastructureOnly=true -Dtest=HelmReleaseTest,KindReadinessTest test
```

The corrected explicit lifecycle passed on the disposable `shardshop-step320`
lab. Each full `up.sh` build passed **1,123 unit tests and 55 routing startup
tests**, image advisory gates, and all **four final acceptance scenarios**:

- Creation produced the database foundation and allocator high-water zero, with
  no application Deployments or Jobs. First `up.sh` migrated, seeded and started
  the applications without changing either infrastructure Helm revision.
- A harmless product resource added only to the temporary source copy changed the
  deployed product digest from `20b663099303b1aac7d77226f7740f76ef0bc4bb04fbfcf19b39213b78fda1f6`
  to `1c1562438bce8808a1f2a4d8998744b9e8824b91c5d613776dd5f0b5bea73cee`.
  The next unchanged build retained every image digest and replaced all three
  application pod identities. Both runs preserved the seeded catalog.
- `down.sh` stopped all four node containers; repeated shutdown succeeded.
  Subsequent `up.sh` preserved node/volume identities, all ten PVC bindings,
  database and allocator identities, credential hashes, catalog key-to-ID digest
  and both infrastructure Helm revisions. Allocator history advanced normally.
- Destruction removed the owned nodes, their attached volumes and kubeconfig
  context. Repeated destruction and shutdown of the missing cluster succeeded;
  temporary lifecycle locks and destruction records were removed.

The qualification ran the maintained workflows in this order:

```bash
bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/up.sh
# Add the harmless resource in the disposable source copy, then qualify both cases:
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/cluster-destroy.sh
bash microservices/shardshop/scripts/cluster-destroy.sh
bash microservices/shardshop/scripts/down.sh
```

The local evidence driver was invoked as
`bash /private/tmp/shardshop-320/qualify-final.sh`. It uses a temporary source
copy plus entry-point copies whose only adaptations are cluster identity/context
and input paths, with a private kubeconfig and temporary directory. It stops the
original lab for Docker VM capacity, captures protected identities before and
after each case, and registers cleanup/restoration before creating the owned lab.
The temporary driver and logs are local evidence, not maintained project scripts.

The final full `up.sh` restored the original `shardshop` lab and passed all build,
advisory and acceptance gates. Before/after comparisons preserved its original
four nodes and volume attachments, all ten PVC identities/bindings, database and
allocator identities, credential hashes and infrastructure Helm revisions. Its
catalog key-to-ID digest remains
`97af5c774026fb993e04a7b80e3c74bc2ad6c8c6ff7ccc6b27ffe56a7f57ca88`.
Two product replicas and one reader are Ready, all ten PVCs are Bound, and both
qualification and restoration completed successfully with lifecycle locks released.
Step 3.20 is complete.

## Step 4.1

**2026-10-16:** order serves buyer POST/GET, durable allocation POST and order
PUT/GET. The provider stores region-scoped creation keys and normalized immutable
payloads, recovers identical retries after restart, and owns buyer/order/saga/
logical-command IDs. Ordering V2 adds person/contact/address fields and item
unit-cost snapshots without changing V1. Legacy unknown values remain NULL;
incomplete historical buyer profiles fail closed until explicitly reconciled.

Acceptance uses two short, bounded, locked primary transactions around catalog
queries. The first releases its transaction, advisory lock and connection before
catalog IO; the second repeats quarantine, allocation and fingerprint checks
before using the lookup outcome. Exact catalog-derived totals and ordered item
snapshots commit with one PENDING_STOCK saga. The request changes neither stock
nor outbox state. Strict HTTP decoding and error precedence match the OpenAPI,
including a relative Location equal to statusUrl and saved terminal responses.

Validation on Maven 3.9.16 and OpenJDK 25:

- **177 order unit/provider tests** and **27 packaged startup checks** pass.
  Tests cover exact numeric normalization/fingerprint/overflow, ID generation
  failure, unknown commit recovery, reversed catalog lookup completion order,
  both locked checks, strict wire validation, and bounded JDBC failure handling.
- **174 shardshop-testing unit tests** pass, including chart/migration inventory
  checks; V2 is automatically included by the existing migration packaging.
- All **seven OrderApi.feature PostgreSQL scenarios** pass using the packaged
  product and order JVMs, verify-full TLS, real owner migrations and runtime roles.
  Concurrent buyer/allocation/order retries preserve one identity, snapshots and
  saga; restarts recover persisted mappings; injected saga-insert failure rolls
  back the complete acceptance. A blocked catalog query holds no order
  transaction or advisory lock, and a new allocation succeeds with a one-connection
  order pool. Concurrent creation and quarantine both override its timeout during
  the final locked recheck. V1-to-V2 migration preserves historical data.
- All **five existing catalog migration/role scenarios** pass after the shared
  PostgreSQL fixture gains ordering roles/schema. Both disposable PostgreSQL
  containers are removed by the test JVM's shutdown cleanup. The retained kind
  lab is unchanged.
- The dependency/SBOM audit passes. All 126 packaged runtime JARs match the product
  runtime byte for byte, and OSV reports no findings for the 123 third-party Maven
  coordinates. No version or image pins changed. The audit retains Maven's
  existing blocked HTTP snapshot-metadata warning; the SBOM validates successfully.

Commands executed, using the existing packaged product and reactor library
artifacts after the scoped dependency checks:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -Paudit verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify -Dcucumber.filter.tags=@order-api
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration \
  '-Dcucumber.filter.name=^(Flyway|The constraints keep|Profit credits keep|Each role has).*' \
  failsafe:integration-test@integration-tests failsafe:verify@integration-tests
```

For a fresh checkout, add `-am` to the first two commands as the
[runbook](shardshop-order/RUNBOOK.md#buyer-and-order-provider) shows. The order runtime
is HTTP/1.1: the provider test's HTTP/2-preferring client negotiates HTTP/1.1,
while HTTP/2 qualification remains step 6.5. The HTTP limit is ten seconds,
covering the two three-second database phases and a two-to-three-second shared
catalog deadline. `--check-startup` remains available for bounded startup checks.
Stock reservation, broker/ledger processing, producer load and Kubernetes order
deployment remain their separately planned steps.

**Validation fixes (2026-10-16).** An independent validation found five problems.
The regression tests failed before the fixes for the reported reasons:

1. **503 at low concurrency.** Each copied JDBC store ran database phases on a
   platform executor with `shards × max-pool-size` threads and no queue, thus each
   further phase got an immediate 503. A probe with the lab regions, pool size 4 and
   healthy PostgreSQL showed 12 successful concurrent phases, and two of four
   concurrent 6-item PUTs failed with `CATALOG_UNAVAILABLE` or
   `ORDER_STORE_UNAVAILABLE`. The scenario helper retried every 503 and hid this.
   The shared engine now runs each phase on its own virtual thread, and a burst waits
   at most one second for a pooled connection. The helper never retries, and a new
   scenario sends eight concurrent acceptances and 16 status reads through
   one-connection pools. Before the fix, it and the two older concurrency steps failed
   because of 503 responses. After the fix, the same probe got no 503 for up to 64
   concurrent allocations, reads or 1- and 6-item PUTs. The slowest of 64 concurrent
   6-item PUTs took 467 ms, and a 1,000-item PUT took 1.3–1.4 s.
2. **Generator slots on invalid settings.** The order service producer injected the
   generator before it checked the catalog deadline, the catalog concurrency and the
   ID candidate budget. Product did the same for its candidate budget. On Kubernetes,
   each crash-loop restart would use a lifetime slot. The stores and the checked
   service settings now start at an earlier priority than the services. New packaged
   startup rows set a registry UID without a CA file and expect the error of the
   setting. Before the fix, three order rows and one product row showed the
   reservation error instead.
3. **Untested advisory locks.** With all three locks removed, all unit tests and
   scenarios passed. The in-memory store of `OrderServiceTest` now requires the lock
   before the first read of each write. A new scenario holds each lock with SQL:
   buyer creation, allocation and acceptance wait for it, and an order lock held past
   the phase budget gives 503 within four seconds and writes nothing. With the locks
   removed, 19 of 24 `OrderServiceTest` tests and this scenario fail.
4. **Copied code.** The phase engine `DatabasePhases`, `Endpoint`, the TLS and driver
   defaults of `JdbcUrls`, strict request decoding (`JsonRequests`) and the nonblank
   text rule moved from product and order into `shardshop-common`, with no new
   dependency. By the user's decision, common manages no connections: the
   applications keep their pools, endpoints and credentials. The HTTP deadline
   filters stay in the applications, because they need Vert.x, JAX-RS and CDI wiring.
   The copies differed in one rule: after a failed rollback, a domain failure now
   stays unchanged in order too, as in product, because no commit occurred. The
   TLS configuration errors no longer name the service.
5. **Old step numbers.** This entry and `R__catalog_grants.sql` use the new numbers.
   The changed repeatable file applies the catalog grants again at the next `up.sh`.

Validation after the fixes, on Maven 3.9.16 and OpenJDK 25:

- **Common:** 258 unit tests (63 new). **Product:** 235 unit tests and 30 packaged
  startup checks. **Order:** 165 unit/provider tests and 32 packaged startup checks.
  The generic store tests moved from product and order to common.
- **shardshop-testing:** 174 unit tests and all **51 Cucumber scenarios**, including
  the nine order scenarios, in one `-Pintegration` run. The only warning is the
  accepted Testcontainers Ryuk line.
- The runtime JAR sets of product and order did not change, and all 142 order JARs
  stay byte-identical to the product JARs. The audit profile did not run again,
  because no dependency changed.

```bash
mvn -o -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-product -am verify
mvn -o -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify
```

## Step 4.2

**2026-10-16:** order now runs a background stock coordinator after HTTP
acceptance. It claims versioned, expiring database leases and reserves each
seller shard's items in product-ID order using the existing bounded database
phase engine. Reservation insertion and conditional decrement share one
`READ COMMITTED` transaction; unchanged retries never decrement twice.

All reservations must succeed before a buyer-shard transaction moves the order
and saga to `PENDING_LEDGER`, reserves the command/result transport identities
and inserts the schema-valid `RecordOrder` outbox. An out-of-stock decision
commits `CANCELLED` before release. Release restores only active reservations,
and completion is durable after every seller shard finishes. Catalog V3 adds
permanent per-order guards, including closed guards for shards with no earlier
reservation, to fence delayed reserve calls after compensation. Ordering V3
adds lease ownership and independent stock retry fields. V1/V2 remain unchanged;
there are no new SQL functions, triggers or library dependencies.

Unavailable shards and unknown outcomes remain pending with durable exponential
backoff capped at thirty seconds. Restarts resume work after lease expiry. Each
catalog phase follows a lease renewal and holds no order transaction, connection
or advisory lock. New settings are checked before generator allocation, and
`--check-startup` performs no stock polling. The order API scenarios explicitly
disable the coordinator to retain their isolated acceptance checks.

Validation on Maven 3.9.16 and OpenJDK 25:

- **Order:** 208 unit/provider tests and 35 packaged startup checks pass. JDBC
  tests cover unknown reserve/ledger commit outcomes and atomic outbox rollback;
  coordinator tests cover cancellation ordering, stale ownership and retry bounds.
- **Testing module:** all 174 unit tests pass. Five catalog migration scenarios,
  nine existing order API scenarios and seven stock scenarios pass across the
  initial integration run and the final focused run. Other scenarios were
  excluded by the explicit tag filters.
- Two actual coordinator JVMs race 16 orders for four units of stock. Exactly
  four reach `PENDING_LEDGER`; twelve cancel with `OUT_OF_STOCK` and compensate
  earlier reservations. Cross-region success retains one envelope and reservation
  per item through retries and restarts. A catalog outage preserves partial work.
- Injected product CHECK constraints fail the Java decrement after reservation
  insertion and fail restoration after the release update, proving both pairs
  roll back together. Restart completes compensation after recovery. An active
  lease is skipped at an observed database barrier; explicitly expiring it allows
  one takeover from version 7 to 8. Closed guards survive compensation/restart,
  including shards without a reservation. Runtime guard permissions and denied
  reservation `TRUNCATE` pass. Every stock scenario checks the stock invariant.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify '-Dcucumber.filter.tags=@stock-reservation or @order-api or @catalog-migration'
# Recompile the final added scenarios and reuse the verified packaged applications:
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration test-compile failsafe:integration-test failsafe:verify '-Dcucumber.filter.tags=@stock-reservation or @catalog-migration'
```

Apply both V3 migration streams before starting the new worker against a retained
database. These checks use disposable PostgreSQL databases; they do not change
the retained kind lab. Broker delivery, ledger results and order deployment remain
the subsequent milestone steps.


## Step 4.3

**2026-10-16:** RabbitMQ 4.3.6 now runs as a single persistent StatefulSet in the
infrastructure release. The chart imports three durable direct exchanges, four
single-member quorum queues and the three complete policies from architecture
section 6. Source queues have native failed-delivery retry and at-least-once
parking; parking queues have no expiry, onward DLX or automatic consumer. All
queues have reject-publish capacity limits. Configuration, credentials and data
use separate mounts; the broker runs under an arbitrary non-root UID with a
read-only root filesystem and dropped capabilities.

`cluster-create.sh` generates the broker's role credentials and cookie once,
retains them on reruns, and stops if retained broker storage has lost its Secret.
The live infrastructure gate checks the desired image/configuration checksum,
Ready StatefulSet and Pod, Bound PVC, feature flag, effective policies, exact
queue arguments, exchanges and bindings. The projected-volume regression test
proves that startup excludes Kubernetes metadata directories from definition
imports. Version, artifact hash and advisory records are in
[VERSIONS.md](VERSIONS.md#step-43-rabbitmq-qualification) and the lock.

Validation:

- All **192 testing-module unit tests** pass, including seven broker chart tests,
  nine live-gate fixture tests and credential retention/recovery guards. The scoped
  creation build also passes the shared common module tests.
- All **six broker scenarios** pass on native ARM64 with RabbitMQ **4.3.6**,
  Erlang **27.3.4.19** and the test-only socket client **5.37.0**. The broker runs
  under UID **1000780001**, absent from the image's passwd file. Guest access and
  unauthorized topology, publishing, consuming and management access are denied.
- Both source queues deliver five failed retries at approximately 1/2/3/4/5 seconds;
  the sixth rejection parks with `delivery_limit`. A nack preserves failure count
  zero. Both queues retain unacknowledged persistent messages and parked messages
  across container replacement on the same volume; malformed-message rejection
  parks with `rejected`.
- Source and parking queues accept 1001 messages before negative confirmation,
  demonstrating the documented quorum overshoot of the 1000-message threshold.
  A full parking queue leaves the dead letter retained at the source with broker
  alarms clear. After capacity returns, the native **180-second** internal retry
  forwards it without loss. The test allows a bounded 210-second recovery window.
- A final focused topology/authorization run passes after adding an explicit
  five-second AMQP RPC timeout. Isolated broker containers and volumes are removed.
- Two actual `cluster-create.sh` runs pass on retained `kind-shardshop`, first
  with UID 1000780001 and then with the repository default UID 10001. Both pods
  become Ready without restarts; the PVC identity/binding and a hash of the
  credential Secret's identity/data remain equal. All ten pre-existing database
  PVC identities/bindings, product/reader Deployment templates/replicas and
  allocator identities/data remain equal. The lab remains running.
- The module audit generates its effective POM, resolved graph, plugin inventory
  and a **93-component SBOM**; direct Central checksums and client OSV review pass.
  Broker image advisories and the unused `gosu` assessment are recorded in VERSIONS.

Executed commands (from the repository root):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true test
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml \
  -Dshardshop.infrastructureOnly=true -Pintegration -Dtest=RabbitMqChartTest \
  -Dcucumber.filter.tags=@rabbitmq verify
# Final focused check after the RPC timeout adjustment:
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml \
  -Dshardshop.infrastructureOnly=true -Pintegration -Dtest=RabbitMqChartTest \
  -Dcucumber.filter.tags=@rabbitmq \
  '-Dcucumber.filter.name=Effective topology, feature flags, runtime and least privilege are ready' verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dshardshop.infrastructureOnly=true -Paudit \
  help:effective-pom@effective-pom-report dependency:tree@dependency-tree-report \
  dependency:resolve-plugins@plugin-dependencies-report cyclonedx:makeBom@application-sbom
# Temporary copy of kind-values.yaml, adding rabbitmq.runAsUser: 1000780001:
SHARDSHOP_ENV_VALUES=/tmp/shardshop-rabbitmq-arbitrary-uid-values.yaml \
  bash microservices/shardshop/scripts/cluster-create.sh
bash microservices/shardshop/scripts/cluster-create.sh
```

Each live creation executes the scoped `clean install` plus kind safety, DNS,
allocator, database and broker gates. Source and broker integration checks remain
independent of the unimplemented application consumers/CDC steps. The one-broker
lab provides persistence, not high availability.

<a id="lifecycle-script-reduction"></a>
## Lifecycle script reduction after step 4.3

**2026-10-18:** At the user's request, `down.sh` and `cluster-destroy.sh` keep only
the shared lifecycle lock and the one command that does the work. Both scripts now
source `scripts/lib/lifecycle.sh` and call `lifecycle_init`, as `cluster-create.sh`
and `up.sh` do. The lifecycle Bash code goes from 564 to 354 lines.

- `down.sh` (111 → 18 lines) gets the nodes from `kind get nodes` and stops them
  with one `docker stop --time 10` call. Docker stops the nodes in parallel, does not
  change a stopped node and returns an error if a node does not stop. The script
  deadline, `SHARDSHOP_SHUTDOWN_TIMEOUT_SECONDS`, the background command control and
  the node inspections before and after the stop are removed. If Docker Desktop does
  not respond, `down.sh` waits until Ctrl+C.
- `cluster-destroy.sh` (129 → 15 lines) runs `kind delete cluster --name shardshop`.
  kind removes the node containers with `docker rm -f -v`, which also removes their
  anonymous `/var` volumes. Then kind removes its kubeconfig entries. The removed
  parts are the volume inventory for the Docker daemon, the host-mount, shared-volume
  and kubeconfig-reference guards, and the checks after the deletion.
  `infra/kind.yaml` adds no host mounts, and Docker keeps a volume that another
  container uses.
- `up.sh` no longer accepts `--restart`. Every run already restarted the
  application pods, thus the alias had no effect.
- `KindLifecycleLockTest` (5 tests) replaces `KindStopScriptTest` (16 tests) and
  `KindDestroyScriptTest` (14 tests). It keeps the lock test for all four entry
  points, the INT/TERM tests of `up.sh` and the failure test of `down.sh`. The Java
  test code is 431 lines shorter.

Validation:

- Before a `clean` build, the compiled classes of the two deleted tests ran against
  the new scripts, and 21 of their 30 tests failed. Thus these tests checked only the
  removed logic.
- After `clean`, all **167 testing-module unit tests** pass (197 before). Their
  surefire time goes from 68.1 s to 47.7 s. Bash 3.2 and Bash 5.3 accept the syntax
  of the five script files.
- The new `down.sh` stopped the four retained nodes in 11 seconds. Docker stopped
  them with SIGKILL after the grace period (exit 137), as with the earlier script.
  A second run made no change. An argument gives the usage error.
- While the retained lab was stopped, copies of the new `cluster-destroy.sh` and
  `down.sh` ran against a disposable one-node kind cluster. The copies changed only
  the cluster name to `shardshop-destroy-test`. The cluster used the pinned node
  image and a private kubeconfig with an unrelated context. The node had only the
  anonymous `/var` volume and the read-only `/lib/modules` bind. The deletion
  removed the container, the volume and all kind kubeconfig entries, and the
  unrelated context stayed. A second deletion and `down.sh` for the missing cluster
  ended with success. Each run released the lock.
- `up.sh` passed the ShardShop `clean install` with all module tests and the
  `kind-config` gate. Then the Docker Scout gate stopped it with exit 2, before any
  cluster change. CVE-2026-97032 (high, Go `stdlib` before 1.26.9) in the Pebble
  binary of the `ubuntu/jre` base image has no reviewed VEX statement. The VEX
  document covers only the ten earlier Go CVEs. This gate failure is independent
  of the script reduction, and it blocks `up.sh` until the advisory is reviewed.
- `cluster-create.sh` then resumed the stopped lab in 140 seconds. All its gates
  passed, including DNS on every node, the allocator and the database and broker
  topology. The node container IDs and mounts, PVC bindings, CNPG Cluster and
  Database UIDs, Secret UIDs and versions, and the allocator registry and identity
  are equal to the state before the stop. Only the infrastructure Helm history
  changed: Helm added revision 11 and removed revision 1. The two restarted product
  JVMs moved `highWaterMark` from 129 to 131. Product (2/2), the reader (1/1) and
  RabbitMQ (1/1) are ready with the images of the previous deployment.
- At the user's request, version 4 of `infra/images/application/vex/jre-pebble.openvex.json`
  adds CVE-2026-97032 with the products and justification of the ten earlier
  statements. OSV lists it as GO-2026-6617, an HTTP/2 server crash in `net/http`,
  fixed in Go 1.26.9 and 1.27.2. The vulnerable code is in `/usr/bin/pebble`, the
  only Go binary of the images, and pebble never runs. Scout now reports 2 critical,
  9 high and 2 medium advisories for this package. Until 2026-10-13 it reported
  2 critical, 8 high and 3 unrated. With version 4, the gate passes for the current
  Flyway, product, seeder and reader builds. VERSIONS.md and the lock record the
  new counts.
- The second `up.sh` passed in 487 seconds: all 1,405 module tests, all kind gates,
  the advisory gate, the delivery of the four images to every node and the Helmfile
  phase gates. Flyway kept the reviewed pin `sha256:96efa775…`. This first `up.sh`
  after steps 4.1–4.3 applied the pending catalog V3 and ordering V2 and V3
  migrations, with their repeatable migrations, on all three shards. All of them
  succeeded, and ledger had no pending migration. Product (2/2) and the reader (1/1)
  run the new digests, which the lock records. In the retained state, only the Helm
  release history changed. The two new product JVMs moved `highWaterMark` from 131
  to 133.

Commands run from the repository root:

```bash
bash -n microservices/shardshop/scripts/up.sh microservices/shardshop/scripts/down.sh \
  microservices/shardshop/scripts/cluster-create.sh \
  microservices/shardshop/scripts/cluster-destroy.sh microservices/shardshop/scripts/lib/lifecycle.sh
mvn -B -ntp -o -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dshardshop.infrastructureOnly=true clean test
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/cluster-create.sh
# After version 4 of the VEX document, for each current Flyway, product, seeder and reader tag:
docker scout cves --exit-code --only-severity critical,high --format only-packages \
  --only-vuln-packages --vex-location microservices/shardshop/infra/images/application/vex \
  --vex-author '^ShardShop image review$' --ignore-suppressed local://shardshop-<image>:<tag>
bash microservices/shardshop/scripts/up.sh
```

The disposable qualification script ran between the second `down.sh` and the first `up.sh`.
It used `kind create cluster --name shardshop-destroy-test --image <pinned node
image> --wait 120s` with a private `KUBECONFIG`, and native `kind delete cluster` in
its EXIT cleanup. Its files and logs remain outside the repository.

## Step 4.4

**2026-10-18:** Order embeds Debezium **3.7.0.Final** with one independently
supervised PostgreSQL reader per shard. Each reader uses the provisioned outbox
publication, a retained failover-capable slot, separate replication credentials,
and the shard-local JDBC offset store. An application-role session advisory lock
prevents overlapping readers from writing the same offsets. Queues, snapshot
fetches, network/SQL calls and retries are bounded.

The initial snapshot publishes pending outboxes; streaming publishes committed
inserts in source order. Published snapshot rows, status changes, deletes,
tombstones and control records do not become commands. The relay preserves the
stored `RecordOrder` envelope and publishes it persistently with mandatory routing.
Only a positive confirm without a return permits the local publication-status
transaction, and only that transaction's commit permits the source checkpoint.
Ordinary failures restart their shard reader with capped exponential backoff.

Ordering **V4** adds permanent initialization history, inserted atomically with
the first durable checkpoint. The runtime can insert but cannot update or delete
that history. Missing or inconsistent slots, offsets or required WAL stop the
reader for explicit reconciliation, including simultaneous slot/offset loss.
The normal order lifecycle starts and closes CDC alongside stock reservation;
`--check-startup` starts neither worker.

The order dependency audit produces **304 SBOM components**. All **20** Debezium,
Kafka and LZ4 JAR checksums match Maven Central, and OSV reports no findings for
the **202** resolved third-party runtime JAR coordinates. Order explicitly
supplies Debezium's provided storage-common dependency and overrides inherited
LZ4 1.11.1 with **1.12.0** to address its published advisories.
[Qualification and sources](VERSIONS.md#step-44-embedded-cdc-dependencies).

Validation:

- **229 order unit/provider tests** and **35 packaged startup checks** pass.
  Batch tests cover publication/status/checkpoint ordering and failures; publisher
  tests cover returns, negative confirms, timeouts and unusable connection cleanup.
  The insert-with-publication-metadata regression failed before its correction
  and passes afterward: only snapshots may skip already-published rows.
- All **seven CDC scenarios** pass against the packaged order JVM with PostgreSQL
  **18.6** and RabbitMQ **4.3.6**. They verify pending snapshot and committed WAL
  delivery on every shard, exact schema-valid persistent envelopes, ignored
  rollback/metadata events, durable restart without resnapshot, shard failure
  isolation, mandatory-return checkpoint blocking and automatic reconnection,
  and fail-closed behavior after slot, offset or combined loss. The combined-loss
  scenario also verifies the runtime cannot modify or delete reader history.
- The existing **nine order API** and **seven stock reservation** scenarios pass
  against the updated migration and logical-replication database fixture. Both
  scoped integration runs also pass **167 testing-module unit checks**.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@cdc-relay'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@order-api or @stock-reservation'
```

Apply ordering V4 before enabling CDC against a retained database. These checks
use disposable PostgreSQL and RabbitMQ containers. Order's kind deployment and
Secret mounts remain step 4.9; crash injection and synchronized-standby failover
remain steps 5.5 and 6.2.

### Step 4.4 validation fixes

**2026-10-18:** A validation of the step found five problems, and the user asked
to fix all of them.

1. Debezium had no heartbeat. An empty first snapshot saved no offset and no reader
   history. Thus a normal restart latched "CDC recovery required" on every shard
   without a command. An idle outbox never confirmed WAL: after about 100 MB of
   other writes, the slot still retained all of it, and the lab's
   `max_slot_wal_keep_size` (512 MB) would invalidate such a slot. Each reader now
   sends a heartbeat every 10 seconds. Its action query
   `SELECT pg_logical_emit_message(true, 'shardshop-cdc-heartbeat', '')` gives
   Debezium a commit position to save and confirm. Heartbeat records and `m`
   messages are checkpointed but never published.
2. The acceptance cell had lost "The relay checks of scenario 6 pass", and the suite
   did not test several relay checks. PLAN row 4.4 states the requirement again.
   Nine scenarios are new: inserts during the initial snapshot, an idle restart, a
   first start that fails before its first checkpoint, idle WAL release, a dropped
   walsender, a second order process, lost WAL, a slot ahead of its offset, and an
   accepted order whose stored `RecordOrder` the relay publishes unchanged.
3. The relay logs named neither the cause of a retry nor the reason of a recovery
   stop. A retry now logs its delay and a bounded single-line cause chain. A
   recovery stop logs its reason, for example
   `CDC recovery required for shard-a: replication slot was invalidated (wal_removed)`.
4. Without CDC credentials, the relay started with empty passwords and retried
   forever. With CDC enabled, startup now fails before the generator reservation
   when a routing shard has no CDC password or the broker has no password. The
   message names the property, never a value.
5. A permanent stop depended on words in exception messages. The supervisor now
   retries every failure and decides from durable state before each start. That
   check also stops a reader when the slot is ahead of the stored resume position,
   the condition that `trust_offset` rejects. A slot without an offset and without
   reader history belongs to a first start that never saved a checkpoint. The
   reader drops that slot with the replication login, because Debezium cannot take
   an exported snapshot from an existing slot. Debezium then creates a new slot with
   a consistent exported snapshot.

Validation:

- Before the fixes, the new tests ran against a copy of the step 4.4 code. Four
  unit tests failed for the reported reasons: no heartbeat setting, an `m` record
  stopped the batch, and two wiring tests accepted absent credentials. The two new
  startup rows started with exit code 0. Ten of the sixteen CDC scenarios failed:
  the idle restart (no offset after the empty snapshot), idle WAL release (WAL not
  confirmed within 45 seconds), and eight scenarios whose log line had no cause or
  reason. The snapshot-handoff scenario first failed on a test buyer ID collision.
  The harness now uses each buyer ID once.
- After the fixes, **236 order unit/provider tests** and **38 packaged startup
  checks** pass. The full `-pl shardshop-testing -am -Pintegration clean verify`
  passes **80 scenarios** in 1,223 seconds, with no WARN lines. The logs show one
  slot drop of an uncheckpointed first start, and each recovery stop names its
  reason.
- Scratch probes before the fixes also showed that a 25-second lock of the offset
  table and a terminated walsender recover without a recovery stop. Lost WAL stops
  the reader. These cases are now repository scenarios.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am clean install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

The pre-fix runs used a scratch copy of the step 4.4 code with the new tests. The
two unit test classes that need the new API were left out of that copy.

## Step 4.5

**2026-10-18:** Ledger now consumes `ledger.record-order` and commits immutable
decisions before acknowledging deliveries. Strict validation checks canonical
IDs, exact request fingerprints, snapshot arithmetic, integral quantities, Unicode
names and UTC timestamps. A 1 MiB limit rejects oversized bodies before parsing.
The default currency allowlist records USD; another valid currency saves a
permanent `UNSUPPORTED_CURRENCY` rejection without a ledger entry.

Sorted transaction advisory locks serialize order, saga, command and transport
identities. A decision, optional entry, permanent binding, inbox and pending
result outbox commit together. Identical deliveries rearm the exact saved result
and increment its permanent publication attempt, including after inbox/outbox
cleanup. A reconciliation command binds its new order-reserved transport pair to
the saved decision without reevaluating the decision or policy. Conflicting identities or payloads are durably quarantined;
repeated identical conflicts increment their delivery count.

Malformed deliveries are rejected without requeue; database failures use
`basic.reject(requeue=true)` to consume the broker's bounded retry budget.
The consumer reconnects after connection or channel shutdown. RabbitMQ owns its
callback executor, and the supervisor closes the connection once before exit,
preventing shutdown callbacks from reaching a terminated shared executor. Configuration
requires dedicated database and broker credentials; `--check-startup` validates
configuration and exits without external IO.

Ledger V2 stores each original command envelope permanently on `ledger_result_ids`
so same-ID timestamp and payload conflicts remain detectable after transport
cleanup. V1 is unchanged. V2 backfills from retained inboxes and intentionally
fails if a legacy binding has already lost its original command; reconcile that
data before upgrading.

The scoped ledger build passes **33 tests**, including regressions that first
failed for `24:00` timestamp normalization, oversized bodies, channel-only
shutdown and concurrent connection aborts. The audit generates a **193-component SBOM**; OSV returns no findings
for **81 third-party runtime JAR coordinates**, and five RabbitMQ, PostgreSQL JDBC
and Agroal artifacts match Maven Central checksums. Dependency evidence is in
`VERSIONS.md` and `versions.lock.yaml`.

The testing module passes **167 unit tests**. Across scoped PostgreSQL 18.6 and
RabbitMQ 4.3.6 runs, **11 packaged consumer scenarios** and **two V1→V2 migration
checks** pass: recording, immutable rejection, replay after cleanup and policy
changes, reconciliation, conflict quarantine, malformed-command parking,
concurrent consumers, rollback/recovery, startup checks and reconnect. After the
last lifecycle change, startup/clean shutdown and forced-disconnect recovery were
rerun against the final binary. Normal shutdown has no connection-driver warning
or rejected executor task.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/shardshop-ledger/pom.xml -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@ledger-consumer'
```

Result publication remains step 4.6 and application deployment to kind remains
step 4.9. The consumer generates no IDs and reads only its own database.

### Step 4.5 validation fixes

**2026-10-18:** A validation of the step found seven problems, and the user asked
to fix all of them.

1. The default database URL named `ledger-rw`, but the `ledger-db` cluster serves
   `ledger-db-rw`. With that default, the consumer started, and then each command
   failed six times and went to the parking queue. The default and the README
   example now name `ledger-db-rw`. A unit test compares the default with the URL
   of the chart's ledger migration Job.
2. The testing reactor did not build the ledger package. Thus
   `-pl shardshop-testing -am` tested the ledger package that was on disk, also
   when that package was old. `shardshop-testing` now declares the ledger POM, the
   same as the other packaged applications.
3. Six rules had no test that failed when the rule was removed. New unit tests
   cover the item currency, unique seller/product pairs and the `occurredAt` clamp
   for a ledger clock that is behind the saved decision. New scenario steps cover
   a changed snapshot on a new transport pair, a duplicate after publication and
   the five identity locks.
4. The consumer logs gave no cause. A restart now logs its delay and its cause. A
   failed transaction logs the database error. The JDBC URL sets
   `logServerErrorDetail=false`, thus no row values get into the log. A parked
   command logs the rule that it does not obey, and a quarantined command logs its
   message ID, order ID and reason. A second `DatabasePhases.run` gives the failure
   to a background caller. `Failures.describe` moved from the order CDC relay to
   `shardshop-common`, thus order and ledger write the same cause text.
5. The PLAN row had no date, result sentence or Evidence link, and this entry was
   a subsection of step 4.4. Both now obey PLAN instruction 5.
6. The V1→V2 upgrade checks were a JUnit class in the fixture package. They are now
   the two `@ledger-migration` scenarios of `LedgerMigration.feature`. The failure
   scenario also shows that a failed V2 leaves the V1 schema and history.
7. Text corrections: the OSV query covers 81 third-party coordinates, because one
   of the 82 runtime JARs is `shardshop-common`. The scenario Javadoc names
   scenarios 5, 7 and 10, and the step 2.6 README section names V2 and
   `command_envelope`. The ledger also disables the Quarkus-managed datasource,
   the same as product and order.

Validation:

- Before the fixes, the new tests ran against a copy of the step 4.5 code. Five
  unit tests failed for the reported reasons: the default URL, and four parser
  tests whose messages named no rule. Four of the fourteen ledger scenarios failed,
  because their log lines had no cause, no rule or no IDs. The new behavior checks
  passed, because the code already obeyed these rules.
- In a scratch copy of the fixed code, each of the six earlier mutants fails a
  new test. Two V2 mutants, without the backfill and without `NOT NULL`, each fail
  one migration scenario.
- After the fixes, the ledger passes **39 unit tests**. Order passes **235 unit and
  provider tests**, one fewer than before, because its `describe` test moved to
  `shardshop-common`. Order also passes **38 startup checks**, and product passes
  **235 unit tests** and **30 startup checks**.
  The **12 consumer scenarios** and **2 migration scenarios** pass. The full
  `-pl shardshop-testing -am -Pintegration clean verify` passes **94 scenarios** in
  1,209 seconds. Its only WARN line is the accepted Ryuk line. The ledger runtime
  dependencies did not change, thus the SBOM and OSV evidence above stays valid.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-ledger,shardshop-order,shardshop-product -am clean install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

The pre-fix runs used a scratch copy of the step 4.5 code with the new tests. The
tests that need the new API were left out of that copy: `FailuresTest`, the new
`DatabasePhasesTest` case, `LedgerServiceTest` and `LedgerCommandConsumerTest`.

## Step 4.6

**2026-10-18:** Ledger now publishes committed result outboxes to
`order.saga-results` with routing key `order.ledger-results`. It sends the exact
saved result envelope as persistent JSON, with mandatory routing and the
order-reserved result ID. Only a positive broker confirm without a return permits
the separate database transaction to mark that result published.

The mark checks both `message_id` and the sent `publication_attempt`, so an older
confirm cannot clear a replay that increments the permanent counter and rearms
the outbox. Failed publishes and uncertain mark commits retry from durable state;
they can deliver the same result again without changing its ID, timestamps,
decision or envelope. No database transaction spans broker IO. A single worker
reads bounded batches (100 by default) and publishes one result at a time, with
finite database/send/confirm deadlines and reconnect backoff from 500 ms to
30 seconds. `--check-startup` starts neither worker; disabling the publisher
leaves command consumption available.

No migration or dependency change is required. The audit still produces a
**193-component SBOM** using the step 4.5 dependency graph. The publisher leaves
confirmed outbox rows for the later retention step; order result consumption
remains step 4.7 and kind application deployment remains step 4.9.

Validation:

- **48 ledger unit/contract tests** pass, including positive/negative/missing
  confirms, mandatory returns, a blocked socket write, disabled workers, a stale
  mark, and replay after an uncertain database mark.
- **Six packaged publisher scenarios** pass on PostgreSQL 18.6 and RabbitMQ
  4.3.6. They verify recorded and rejected envelopes/properties, restart and
  startup probes, duplicate replay after cleanup and a policy change,
  reconciliation, missing-route recovery, and reconnect after both ledger broker
  connections are closed. A held outbox row lock forces a confirmed old attempt
  to wait while its permanent/outbox attempt advances; the newer row remains
  pending until its own successful publication, without a database timeout.
- **12 existing ledger consumer scenarios** pass with the publisher disabled,
  preserving the command consumer's pending-result checks.
- **167 testing-module unit tests** and **two ledger migration scenarios** pass.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-ledger test
mvn -B -ntp -f microservices/shardshop/shardshop-ledger/pom.xml -Paudit -DskipTests install
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml -Pintegration \
  -Dtest=KindSafetyTest '-Dcucumber.filter.tags=@ledger-publisher' verify
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml -Pintegration \
  -Dtest=KindSafetyTest '-Dcucumber.filter.tags=@ledger-consumer' verify
mvn -B -ntp -f microservices/shardshop/shardshop-testing/pom.xml -Pintegration \
  '-Dcucumber.filter.tags=@ledger-migration' verify
```

### Step 4.6 validation fixes

**2026-10-18:** A validation of the step found five problems, and the user asked
to fix all of them. For the first problem, the user selected one shared publisher
class.

1. The ledger result publisher was a copy of the order command publisher, and the
   two copies were already different. Both relays now use
   `dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher` in
   `shardshop-common`. It keeps the normal close, the single abort, the 10-second
   heartbeat and the named threads of the ledger copy, and the error texts of both
   copies, for example `RabbitMQ returned unroutable command publication`. Common
   declares the RabbitMQ client as an optional dependency. Thus only order and
   ledger, which declare the client themselves, contain it. ARCHITECTURE now
   permits this shared publisher in common.
2. Two documented behaviors had no test. `LedgerWiringTest` now starts both
   workers with `shardshop.ledger.enabled=false`, and no worker may connect to a
   local broker port or read the outbox. The full-batch test of
   `LedgerResultRelayTest` now uses a 30-second poll interval and requires the next
   read within 2 seconds.
3. The publisher log did not always show the state. Each new connection now logs
   one INFO line, for example `RabbitMQ result publisher connected to
   order.saga-results`. A connection that the broker closed while it was idle is
   replaced before the next publication. Before, that publication failed one time,
   with a WARN line and a 500 ms backoff. A timeout cause now names its phase, for
   example `RabbitMQ result publisher connection timed out`. The client's WARN
   `An unexpected connection driver error occurred` for the socket of a failed or
   aborted stream is now a DEBUG line, because the publication reports the failure
   itself.
4. Two document errors: ARCHITECTURE still called the relay a future relay, and
   the README commands of this step had no `-am`.
5. Two Testcontainers of an earlier session, PostgreSQL and RabbitMQ, ran next to
   the lab from 2026-10-17 23:16 UTC. Ryuk is off, thus a stopped test JVM left
   them. They and their two volumes were removed after a check that no process
   used their ports.

Validation:

- Before the fixes, the new tests ran against a copy of the step 4.6 code. Three
  ledger unit tests failed for the reported reasons: two timeout messages were
  `null`, and the next publication after an idle close failed with
  `AlreadyClosedException`. The stronger reconnect scenario failed, because the log
  had a publisher retry line. The two new behavior tests passed, because the code
  already obeyed these rules. Each of them failed against the mutant that it
  covers: without `config.enabled()` in the relay wiring, and with a wait after
  each full batch.
- After the fixes, common passes **271 unit tests**, with 11 for
  `ConfirmedPublisher`. The ledger passes **44 unit tests**, and order passes
  **228 unit and provider tests** and **38 startup checks**. The two publisher test
  classes of order and ledger became `ConfirmedPublisherTest`. The full
  `-pl shardshop-testing -am -Pintegration clean verify` passes **100 scenarios**
  in 1,383 seconds, with the 6 publisher and 15 CDC scenarios. Its only WARN line
  is the accepted Ryuk line.
- Live probes with the packaged ledger pass on disposable PostgreSQL 18.6 and
  RabbitMQ 4.3.6: a full result queue, a memory alarm, a frozen broker, a SIGKILL
  between the confirm and the mark, two ledger processes, and a locked outbox
  table. With the frozen broker, the log had no client driver WARN line, where it
  had three before the fixes. The timeout causes named their phases.
- The runtime sets of all 14 reactor modules did not change, except the own list
  of common. The audit SBOM of common has 48 components, 34 before. Order and
  ledger keep 304 and 193. OSV returns no findings for the 14 added coordinates.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-ledger,shardshop-order -am -Paudit verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

The pre-fix runs used a scratch copy of the step 4.6 code with the new tests. In
that copy, the publisher regression tests used the old ledger publisher class,
because `ConfirmedPublisher` did not exist there.

## Step 4.7

**2026-10-19:** Order consumes `order.ledger-results`, validates the ledger-owned
contract, routes by buyer ID and serializes application with order creation and
quarantine. Only matching reserved results can confirm or cancel an order and its
saga. The consumer acknowledges after the local transaction commits. Missing
orders and conflicting identities/outcomes are quarantined durably; malformed
IDs go directly to parking, while database outages use the broker's bounded
delayed retry policy. Rejections enable the existing stock-release coordinator.

Ordering V5 retains the first accepted decision and each accepted result envelope
through transport cleanup. Same-ID payload/timestamp changes remain conflicts;
matching results using newly reserved reconciliation IDs are harmless. Automatic
reconciliation enqueueing remains step 5.1.

Confirmation atomically creates per-item profit intents from snapshotted price,
cost and quantity. Separate bounded seller-shard transactions insert an immutable
credit identity and update the currency balance only for a new credit. The home
intent is marked applied after that commit, so retries, unknown commit responses
and competing workers cannot double-credit. Profit is eventual after
`CONFIRMED`; negative margins and independent currency balances are preserved.
Unknown legacy costs cannot become zero margin. Durable retries use backoff and
jitter; permanent credit conflicts, missing catalog references and numeric
overflow block the intent for investigation.

Catalog V4 and the repeatable grants permit only the required balance/credit
writes. Existing versioned migrations remain unchanged. No dependency, compiler
release, SQL function or trigger was added. The audit still reports **304 order**
and **193 ledger** SBOM components. The consumer reuses CDC's broker credentials,
and both new workers remain inactive during `--check-startup`.

Validation:

- **260 order unit/provider tests**, **41 packaged order startup checks** and
  **44 ledger unit/contract tests** pass, along with the selected common, ID and
  sharding dependency checks.
- **Nine new packaged result/profit scenarios** pass on PostgreSQL and RabbitMQ,
  with **167 testing-module unit tests**. They cover recorded and rejected
  results, signed cross-shard profit, duplicates and new reconciliation result
  IDs, conflicts after inbox/outbox cleanup, orphan creation blocking, invalid-ID
  parking, an unavailable home primary, catalog recovery, atomic rollback when
  an intent insert fails, unknown historical cost quarantine, and the runtime
  profit grants. The recovery case stops
  a worker after a seller credit commits while its home mark is locked; two
  replacement workers complete the intent without a second credit or increment.
- The first order-only unit run found an outdated installed common artifact
  without `ConfirmedPublisher`. Rebuilding the selected reactor dependencies
  with `-am` resolved that local-cache mismatch.
- **22 existing order API, stock and catalog migration scenarios** pass. The
  first regression run found three stale catalog expectations for the migration
  list, profit grants and migration-copy fixture. Updating those expectations
  for V4 and the intended 4.7 permissions made the complete selected run pass;
  unrelated product and immutable-credit write restrictions remain verified.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-ledger -am -Pintegration,audit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@order-results'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@order-results' \
  '-Dcucumber.filter.name=Seller credit survives an interrupted home mark and competing workers'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@order-results' \
  '-Dcucumber.filter.name=Unknown historical item cost prevents a recorded result from confirming an order'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@order-api or @stock-reservation or @catalog-migration'
```

### Step 4.7 validation fixes

**2026-10-19:** A validation of the step found three problems, and the user asked
to fix all of them.

1. Result application did not serialize with order creation. The consumer locked
   the raw order ID, but creation locks the SHA-256 key of `order\0\0<orderId>`.
   In a probe with four connections for each shard, an orphan result committed
   while creation held its lock. Then the order and an unresolved orphan with the
   same ID both existed. An identical retry got `409 ORDER_RECONCILIATION_REQUIRED`,
   and the stock worker skipped the saga. The consumer now takes
   `OrderService.orderLock`, the lock of creation. In a control copy with this key,
   the result went to `ordering.conflicting_results`, no orphan stayed, and the
   retry got 202.
2. The order result consumer was a copy of the ledger command consumer, and the
   two copies were already different. The order copy logged only the class name of
   a failure, and its malformed and quarantine warnings had no rule or reason.
   Both modules now use `dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer`.
   `LedgerCommandConsumer` and `OrderResultConsumer` only map their outcomes to a
   broker settlement. The shared loop logs the cause chain, the rule and the
   reason. It also adds 25% jitter to the reconnect backoff of the ledger. The
   publisher and both consumers use the new `BrokerSettings` record. The ledger
   log texts did not change.
3. The profit worker log lines did not help an operator. `System.Logger` formatted
   the order ID with digit grouping, for example `105,614,554,681,012,225`. A
   seller outage named the home shard, and no line gave a cause. The lines now
   name the shard that failed and the cause, and IDs have no grouping.
   `ProfitFailure` keeps its cause for the log.

Validation:

- Before the fixes, the new tests ran against a copy of the step 4.7 code. Four
  unit tests and the new scenario failed for the reported reasons:
  - Two `ResultServiceTest` cases expected the creation key and got the raw ID.
  - The consumer log test got `requested broker retry: ResultFailure`, without a
    cause, and a malformed line without its rule.
  - The profit log test got `order 105,614,554,681,012,225` and the home shard `a`
    instead of the seller shard `b`.
  - The scenario "Orphan quarantine waits for the creation lock of its order ID"
    found an orphan while a test transaction held the creation lock.
- After the fixes, common passes **279 unit tests**, with 8 for `QueueConsumer`.
  Order passes **260 unit and provider tests**, and ledger passes **42 unit
  tests**. The two consumer test classes now test only the outcome mapping. The
  ledger restart scenario accepts the jittered first delay of 375–625 ms.
- The full `-pl shardshop-testing -am -Pintegration clean verify` ran **110
  scenarios** in 1,770 seconds, with the ten result/profit scenarios. Two scenarios
  failed for environment reasons. The HTTP port of one order start was already in
  use, and the reader reached only 156 requests/s while the kind lab used most of
  the Docker VM. A rerun of `OrderApi.feature` and `ProductReader.feature` passed
  all 11 scenarios. The only other WARN line is the accepted Ryuk line.
- Probes with the packaged order on a copy of the fixed code: an orphan result
  waited for a held creation lock. In the creation race, the result went to the
  conflict table and the retry got 202. The log named `shard-c` for a seller
  outage, gave order IDs without grouping, and gave the cause chains and the rule
  of a malformed result. A reconciliation result of the real ledger, with a new
  reserved result ID and the same `decidedAt`, was accepted without a conflict.
- The validation also ran the lab's Flyway path in a disposable container: the
  pinned PostgreSQL image, the lab roles and the `shardshop-flyway` image, with the
  lab's applied streams first. Catalog V4, ordering V4 and V5 and both grant files
  applied, a second migrate did nothing, and validate passed. The runtime
  statements of the consumer and the worker ran as `order_app`, and 13 forbidden
  statements failed. On the lab, `shardshop-order` can read
  `order.ledger-results`, whose policy has delivery limit 5 and a dead-letter
  queue. The lab did not change; its next `up.sh` applies the new migrations.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl :shardshop-common,:shardshop-ledger,:shardshop-order -am clean test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false \
  '-Dit.test=CucumberIT,ShardRoutingStartupIT#startsWithAnExternalRoutingSnapshot' \
  -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true \
  '-Dcucumber.features=classpath:features/OrderApi.feature,classpath:features/ProductReader.feature'
```

The pre-fix runs used a scratch copy of the step 4.7 code with the new tests. In
that copy, the consumer log test used the old order consumer class, and the lock
unit test calculated the creation key itself. `QueueConsumer` and
`OrderService.orderLock` did not exist there.

## Step 4.8

**2026-10-19:** The order producer now runs continuously over HTTP. It verifies
the complete catalog before creating or recovering all 3,000 buyers. Catalog
resolution retries missing/unavailable data with one-to-fifteen-second backoff
and jitter within a five-minute budget. Invalid responses fail immediately.

Each configured run starts at ordinal zero, selects a buyer deterministically,
recovers its durable order allocation and sends the unchanged service-issued ID.
The existing currency hash chooses USD/EUR. Each order has three items, one from
each seller region, with deterministic quantities from one to three. Selection
uses definition keys and fixed run coordinates, never mutable stock/profits.
Retries and process restarts preserve allocation coordinates and item payloads.
Terminal polling, allocation and PUT share one complete deadline; the producer
advances only after confirmation or cancellation. Shutdown cancels outstanding
work, and `--check-startup` performs no HTTP calls.

The shared bounded JSON client adds allocation POST and status-aware PUT without
changing existing callers. Successful PUTs validate both HTTP status and order
state. No dependency, compiler target, database migration, SQL function or trigger
was added. All six runtime SBOM component counts remain unchanged: product 258,
order 304, ledger 193, seeder 147, reader 165 and producer 147. Testing registers
the producer's POM as a reactor-order dependency without importing application
classes. Deployment remains step 4.9.

The explicit tradeoff is restart catch-up: earlier ordinals are replayed before
new work, without a workload-owned progress store. Keep the run name, dataset
versions and selection settings fixed; use a new configured name for independent
work. Sustained runs do not replenish stock.

Validation:

- The six-application audit/install passes **1,313 unit/provider tests**, including
  **113 producer** and **285 common** tests, plus **71 packaged startup checks**
  (30 product and 41 order). Tests cover deterministic selection and the fixed
  currency vectors, exact IDs/payloads through lost responses, invalid response
  correlation, retry bounds, whole-phase timeouts, shutdown and startup gating.
- Initial new-test compilation/setup failures (exception construction, import
  placement, the test configuration's duration converter and mock restubbing)
  were corrected before the complete passing audit. Review also added strict
  pending/terminal HTTP status matching and verified the fix.
- The packaged producer scenario passes against PostgreSQL and the real product
  and order APIs. A test proxy hides the first committed buyer, allocation and PUT
  responses; retries recover the same IDs and send identical keys/payload bytes.
  The scenario verifies all 3,000 buyers, three-region USD/EUR orders, pending
  replay after a JVM restart, and advancement only after terminal status. It
  controls terminal database transitions to isolate producer behavior from the
  already-tested ledger/result workers. **167 testing-module unit tests** also
  pass. A sandboxed attempt could not inspect macOS processes; rerunning with
  local process and Docker access resolved that environment restriction.
- All **three existing seeder/reader integration scenarios** pass after extending
  their shared workload fixture. The final producer unit rerun passes all 113
  tests. Normal producer logs have no warnings or errors, owned test containers
  and packaged JVMs were cleaned up, and `git diff --check` passes.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-order-producer -am test
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger,shardshop-workload/shardshop-product-seeder,shardshop-workload/shardshop-product-reader,shardshop-workload/shardshop-order-producer \
  -am -Pintegration,audit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@order-producer'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.features=classpath:features/ProductSeeder.feature,classpath:features/ProductReader.feature'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-order-producer test
```

### Step 4.8 validation fixes

**2026-10-19:** A validation of the step found three problems, and the user asked
to fix all of them.

1. After a restart, the producer waited the complete order interval after each
   replayed ordinal. In a probe with the default interval of 1 second, a restart
   replayed 15 finished orders in 15.6 seconds before its first new order. Thus a
   restart after hours of load sent no new orders for most of the earlier run
   time. An order that was terminal before its PUT now gets no order interval.
   The same probe recovered 15 finished orders in less than one second. The log
   reports `Order producer recovered 15 finished orders before ordinal 15`, and
   the progress lines count recovered orders separately.
2. The failure line did not identify the stuck order, and an invalid setting
   logged its error twice. The line now names the ordinal, buyer, order and last
   status, for example `Order completion deadline exceeded (ordinal 0, buyer
   105685002747863041, order 105685037959045120, last status PENDING_STOCK)`. The
   `ShutdownEvent` observer moved from the application bean to the wiring, which
   stops only a producer that it created. Before, the observer created the failed
   beans again during shutdown and logged the second error.
3. The planner checked returned descriptors against the default dataset versions,
   not against the configured versions. It now uses the datasets that resolved the
   descriptors. Only `catalog-v1` and `buyers-v1` exist, thus this could not fail
   yet.

Validation:

- Before the fixes, the new tests ran against a copy of the step 4.8 code. The
  deadline log test got `Order completion deadline exceeded` without an ordinal,
  and the new scenario "An invalid setting stops the producer at startup with one
  error line" found two errors. The pacing and dataset tests need the new API, thus
  they ran against copies of the fixed code with one fix removed: an unconditional
  pause failed the pacing test, and default dataset versions failed the dataset
  test.
- After the fixes, the producer passes **116 unit tests**, and common passes
  **285**. Both producer scenarios pass.
- The full `-pl shardshop-testing -am -Pintegration clean verify` ran **112
  scenarios** in 2,423 seconds, while the kind lab and a VPN process loaded the
  machine (load average up to 17). Four scenarios failed for environment reasons:
  three got HTTP 503 from the three-second database deadline of product or order,
  and one order start found its HTTP port in use. A rerun of the
  `@order-producer`, `@order-results` and `@order-api` features passed all 21
  scenarios. The only other WARN line is the accepted Ryuk line.
- The validation ran the packaged producer against the lab through a port-forward
  to the product Service. It resolved the seeded catalog, 150 sellers and 300
  products, without a wait. Then it stopped at buyer creation, because order is
  not deployed before step 4.9. The lab did not change.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-order-producer -am clean test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false \
  '-Dit.test=CucumberIT,ShardRoutingStartupIT#startsWithAnExternalRoutingSnapshot' \
  -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true \
  '-Dcucumber.filter.tags=@order-producer or @order-results or @order-api'
```

The pre-fix runs used a scratch copy of the step 4.8 code with the new tests. In
that copy, the deadline log test used the old workload API and required only the
ordinal, because the old API could not report the order.

## Step 4.9

2026-10-20: added the order, ledger and order-producer artifacts to Skaffold and
mounted their runtime configuration and public CA certificates in the existing Helm
application release. Their passwords are `secretKeyRef` environment variables. Order uses one `Recreate` replica with the
existing allocator service account; ledger and producer have no Kubernetes token.
The kind producer uses the stable `kind-saga-v1` run identity, 10% EUR selection and
the ledger's USD allowlist. Startup extends the existing build/scan/load pipeline to seven images and preserves
Helmfile ordering; no lifecycle entry point was added.

The release observer now verifies all six application images, all five Deployment
revisions and runtime digests, all three service configuration ConfigMaps, both API
Services and the current seeder references. The default saga gate observes current
producer startup and fresh confirmed/rejected orders, permanent ledger decisions,
per-item stock/profit effects, and durable CDC offsets. Explicit `kind-saga-drill`
scenarios exercise HTTP retries/conflicts, duplicate commands/results, permanent
rejection after a temporary EUR policy and scoped transport cleanup, two orders for
the same stock, partial release, and order replacement on another worker. Recovery restores producer
replicas, ledger environment and order placement. Shared `ConfirmedPublisher` is a
test-scope dependency; no external library version or application Java code changed.

Validation:

- The six-application build passed 1,316 unit tests and 71 packaged startup tests.
- The focused deployment suite passed 66 tests. The full ShardShop build inside
  `up.sh` passed 1,495 unit tests and the same 71 packaged startup tests.
- Strict application Helm lint, infrastructure/application rendering and Skaffold
  diagnosis passed. Shell syntax and `git diff --check` passed.
- The first sandboxed Maven attempt could not bind local HTTP test servers
  (`Operation not permitted`); the host-access rerun passed. The fixture tests also
  need host process inspection. Docker Desktop was initially stopped; after starting
  it, all four retained ShardShop nodes were confirmed stopped before deployment.
- The first startup and a direct Scout retry stopped before cluster mutation when
  GHCR truncated the Java database download (`unexpected EOF`). The same current
  schema-1 OCI layer was downloaded in bounded byte ranges, verified against
  `sha256:9d9bcc8c3932f3133aabd61991d231571c5887b932951e1bf387d4197474d3d8`,
  and passed SQLite `quick_check`. Its unchanged metadata is dated October 20
  with the next update October 23. The verified files populated Scout's Java cache
  using the [documented manual-cache layout](https://trivy.dev/docs/dev/advanced/self-hosting/#java-db-adaptations);
  scanner versions, VEX policy and the startup scan gate were unchanged.
- The first live release exposed two observer assumptions. The order status
  proxy forwarded kubectl's `timeout` query parameter to the API, which correctly
  rejects unexpected query parameters. The status check now disables that client
  query parameter while retaining the outer 30-second command bound; its new
  regression failed without the fix and all eight saga observer tests passed
  afterward. The topology observer also counted the newly active Debezium logical
  sender as a third physical standby, despite both physical replicas streaming
  with quorum. The live failure was reproduced against the exact observation query.
  The corrected query excludes only senders backed by logical slots and still
  rejects unexpected physical or unslotted senders. Readiness, topology and database
  fencing checks share that predicate; their 27 focused tests passed.
- The corrected live topology scenario passed on all three shards. The combined
  `kind-release | kind-deployment` gate then passed all five scenarios in 80.52
  seconds, including fresh producer confirmations/rejections and exact stock/profit
  effects. The explicit `kind-saga-drill` passed both scenarios in 93.02 seconds:
  replay and two orders for the same stock, followed by CDC resumption on a different worker.
  Each drill restored its temporary deployment settings.
- After updating all physical-connection observations, the combined
  `kind-infrastructure | kind-drill` selection passed all six scenarios in 5:06:
  database and RabbitMQ readiness, both saga drills, database quorum disruption,
  and repeated seeding/product JVM replacement. The initial standalone snapshot
  had lost YAML document separators; regenerating it with native Helm rendering
  fixed that test-input error before this complete run.
- The final `up.sh` completed successfully on the retained lab: 1,496 unit tests
  and 71 packaged startup tests passed without failures or skips, all seven images
  passed the unchanged high/critical scan gate, and application release 23 passed
  all five final acceptance scenarios in 87.89 seconds. The restarted producer
  recovered 913 completed orders from the stable run before creating fresh orders.
- `down.sh` succeeded after validation; Docker inspection confirmed all four
  retained nodes stopped, restoring the original lab running state. Final
  `git diff --check` passed. No dependency versions or application Java sources changed.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger,shardshop-workload/shardshop-product-seeder,shardshop-workload/shardshop-product-reader,shardshop-workload/shardshop-order-producer \
  -am clean verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test \
  -Dtest=KindSagaTest,KindWorkloadPolicyTest,KindStartupScriptTest,HelmReleaseTest,KindReleaseTest
bash -n microservices/shardshop/scripts/up.sh
git diff --check
bash microservices/shardshop/scripts/up.sh
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test \
  -Dtest=KindSagaTest#orderProxyDoesNotForwardKubectlTimeoutAsAnApplicationQueryParameter
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test -Dtest=KindSagaTest
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test -Dtest=KindTopologyTest
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test \
  -Dtest=KindTopologyTest,KindReadinessTest,KindDatabaseDrillTest
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.snapshot=/tmp/shardshop-49-snapshot.IuG16g \
  -Dshardshop.kind.tags=kind-topology
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.snapshot=/tmp/shardshop-49-snapshot.IuG16g \
  '-Dshardshop.kind.tags=kind-release | kind-deployment'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.snapshot=/tmp/shardshop-49-snapshot.IuG16g \
  -Dshardshop.kind.tags=kind-saga-drill
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.snapshot=/tmp/shardshop-49-snapshot.IuG16g \
  '-Dshardshop.kind.tags=kind-infrastructure | kind-drill'
bash microservices/shardshop/scripts/down.sh
docker ps -a --filter label=io.x-k8s.kind.cluster=shardshop --format '{{.Names}}: {{.Status}}'
```

### Step 4.9 validation fixes

**2026-10-20:** A validation of the step found seven problems. The user selected the
policy for problem 2; the other fixes follow the review.

1. A saga drill that stops without recovery (SIGKILL, host crash or failed
   recovery) keeps the `shardshop-kind-drill` lock and its temporary changes. A
   Helm 4.3.0 client-side upgrade (`--server-side=false`, the Helmfile setting)
   restores `replicas`, but it keeps an added environment variable and an added
   `nodeSelector`. Thus the next `up.sh` restarted the producer while the ledger
   still accepted EUR, and EUR orders became `RECORDED` permanently. The
   application release `presync` hook now also runs the new `kind-drills-idle`
   scenario. It stops the release while the lock exists, before Helm changes a
   resource.
2. The default `kind-saga` gate examined producer orders. Confirmed USD orders use
   the seeded stock permanently: approximately 16 h of producer run time empties
   the 50,000 USD units of a region. Each producer restart also replays the full
   run at approximately 178 ordinals/s inside the 180-second gate window. The user
   decided: "up.sh should not depend on the CONFIRMED status in testing of orders
   it does not create - shardshop-testing should be independent from workload
   scenarios". The user then selected a gate-owned buyer, seller and products.
   Through local port forwards, the gate now creates a US seller with one USD and
   one EUR product (1,000,000 units each) and an EU buyer under fixed
   `kind-saga-gate.*` creation keys. Later runs recover the same IDs. Each run
   submits one USD order, which must become `CONFIRMED`, and one EUR order, which
   the ledger must reject with `UNSUPPORTED_CURRENCY`. The gate checks their
   ledger, stock, profit and HTTP effects and the shard invariants. The step 3.9
   catalog check counts the 150 sellers and 300 products of `catalog-v1` and
   accepts only the gate seller and its two products in addition. The saga drill
   selects only `catalog-v1` products, thus it does not use the gate stock. The
   drill and the gate share one port-forward helper. The API-server proxy read of
   the old gate and its regression test were removed, because no code uses them.
3. The documents and the drill scenario said "stock races" and "competing stock
   reservations". The kind lab has one order replica with one `StockWorker`
   thread, and `StockCoordinator.processNext` handles one saga. Thus the two drill
   orders reserve stock in sequence. PLAN, README, ARCHITECTURE, this entry and
   `SagaDrills.feature` now say "two orders for the same stock" and refer to step
   4.2 for competing coordinators.
4. The schema accepted `${` in `orderProducer.runName`, but SmallRye Config expands
   it: `kind-${x}` fails with SRCFG00011 and `kind-${x:y}` silently becomes
   `kind-y`. A probe with the producer classpath also showed that `$$` and `\$`
   become `$`. Thus the schema and the workload policy now reject every `$` in the
   run name.
5. `KindSaga.terminalOrders` compared the Mac clock (`Instant.now()`) with
   `order_allocations.created_at` from the PostgreSQL clock. The new gate (problem
   2) identifies its orders by the IDs that the allocation API returns, thus it
   compares no clocks.
6. The schema accepted any producer rejection share and any ledger allowlist, but
   the gate needed fresh USD and EUR producer orders. The gate now creates its own
   orders, thus the rejection share does not change its result. The schema now
   requires an allowlist that contains USD and does not contain EUR, because the
   gate and the drill need a confirmed USD order and a rejected EUR order.
7. README and this entry said that the passwords are mounted; they are
   `secretKeyRef` environment variables. The note "Recheck these budgets ..." in
   `values.yaml` had no reference. It now records the measured lab peaks: order
   358 MiB of 1 GiB, ledger 158 MiB of 512 MiB and producer 139 MiB of 384 MiB.

Validation:

- With the fixes temporarily reverted, the six new or changed tests failed for the
  expected reasons: the application release synced with a retained lock (two
  `HelmfileGateTest` cases), `requireIdle` accepted a running drill, the policy
  accepted `kind-$$x`, and Helm accepted `kind-${x}` and the `USD,EUR` allowlist.
  The restored files were byte-identical with their copies.
- The focused suite (`HelmfileGateTest`, `KindDrillGuardTest`, `HelmReleaseTest`,
  `KindWorkloadPolicyTest`, `KindDeploymentTest`, `KindSagaTest`) passed 66 tests.
  The complete `shardshop-testing` unit run passed 185 tests with no WARN lines.
- `helm lint --strict` passed for the default and the application render.
  `helm template` accepted `USD`, `USD,GBP` and `GBP,USD` and rejected `USD,EUR`,
  `EUR,USD`, `EUR`, `GBP`, `EURO,USD`, `USD,USDX`, `usd` and `USD,`.
  `git diff --check` passed.
- Not run, as instructed: `up.sh` and the live `kind-deployment` and
  `kind-saga-drill` suites. Thus the new gate, the new `kind-drills-idle` scenario
  and the changed catalog count have no live evidence yet.

Commands from the repository root:

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25-open
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test \
  -Dtest=HelmfileGateTest,KindDrillGuardTest,HelmReleaseTest,KindWorkloadPolicyTest,KindDeploymentTest,KindSagaTest
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test
helm lint --strict microservices/shardshop/infra/helm/shardshop \
  -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml
helm lint --strict microservices/shardshop/infra/helm/shardshop \
  -f microservices/shardshop/infra/helm/shardshop/kind-values.yaml -f microservices/shardshop/infra/shards.yaml \
  -f "$images" --set-string render=application-release
git diff --check
```

`$images` is a values file with digest-pinned `images` for the six applications.

## Step 4.10

**2026-10-20:** product serves the read-only `/products/` page in its existing
application image. The US/EU/ASIA selector uses the seller's home region. Each
row shows product and seller identities, initial and current stock, unit price
with currency, and exact signed earnings from applied confirmed-sale credits.
Previous/Next browse 25 products at a time; region changes reload the first page
and Refresh reloads the current page. Loading, empty and error states clear stale
results.

`GET /api/v1/products` lists arbitrary created products across every shard of
the selected region. Numeric-ID keyset pagination limits each shard read to
the requested page plus one row, shares one database deadline, and rejects
partial regional results or fallback from strict replica reads. IDs and money
remain strings, including earnings larger than one stored credit's precision.
Catalog V5 adds the product/currency credit index; the repeatable grants give
`product_app`, through `catalog_writer`, read access only to the three credit
columns needed for aggregation. Existing stock and credits survive migration.
README documents access, semantics and the focused acceptance command.

Validation:

- The scoped reactor passes 1,536 unit/provider tests, including 269 product
  tests, and 71 packaged startup checks. The focused integration run passes
  all 15 selected scenarios: six catalog migrations, five product-browser
  scenarios, two order-result flows and two producer scenarios. It verifies
  both US shards, other-region isolation, all pagination limits, large and
  negative earnings, empty pages, invalid queries, unavailable shards/replicas,
  packaged assets, reservations, cancellation and duplicate confirmed credits.
- The initial complete run passed 109 of 118 integration scenarios. Eight
  failures identified the credit grant's wrong recipient or its fixture; one
  exposed an existing producer assertion racing the retry after a hidden
  committed response. The grant now targets the product login's group role,
  and the producer assertion waits within its existing deadline for observed
  retries. All nine failures pass in the focused rerun; its other 103 scenarios
  are intentionally filtered out.
- The nullable cursor exposed a null traversal bug in the shared contract test
  helper. The added regression failed with the original NPE before the fix and
  passed afterward. The product contract example also uses the home region
  encoded by its seller ID.
- JavaScript syntax and temporary DOM checks pass for stale-response rejection,
  exact decimal/ID rendering, safe text, pagination, refresh, empty and malformed
  responses. Browser checks with a controlled fixture also verify large and
  negative amounts, literal HTML-like names, empty/error states and recovery.
  The deployed lab page passes US/EU/ASIA selection, Previous/Next and Refresh;
  it displays live stock reductions and confirmed earnings.
- The first `up.sh` run built and qualified the images, applied migrations and
  deployed the page. Four of five final acceptance scenarios passed; the saga
  gate's seller POST failed in Quarkus's HTTP/2 body handling. Its client now
  explicitly uses HTTP/1.1, matching the workload clients, without changing
  the assertions or provider behavior.
- The second `up.sh` run passes the source build, existing image security gates,
  migrations, release prechecks and all five final live acceptance scenarios.
  The product image digest matches the browser-verified image. The same saga
  scenario that failed before the client fix now confirms its own USD order and
  cancels its own EUR order with the expected ledger, stock and profit effects.
  The lab was restored to its original stopped state with `down.sh`; no data
  was removed. `git diff --check` passes.

Commands from the repository root:

```bash
mvn -B -ntp -pl microservices/shardshop/shardshop-core/common -am test \
  -Dtest=ContractDocumentTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-testing -am -Pintegration,audit verify
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-testing -am -Pintegration,audit verify \
  '-Dcucumber.filter.tags=@catalog-migration or @product-browser or @order-producer'
node --check microservices/shardshop/shardshop-product/src/main/resources/META-INF/resources/products/products.js
node /tmp/shardshop-products-ui-check.cjs
bash microservices/shardshop/scripts/up.sh
kubectl --context kind-shardshop -n shardshop port-forward service/shardshop-product 18080:8080
bash microservices/shardshop/scripts/down.sh
git diff --check
```

The `/tmp` script is a temporary DOM verification harness. Browser acceptance
used the documented page URL through the loopback-only port-forward.

Product-page pagination follow-up:

- The controls are centered below the products in the order Previous, Page N,
  Next. URLs now include region and page, for example
  `/products/?region=EU&page=3`, and restore that selection on reload, a copied
  link, or browser Back/Forward. A fresh link reconstructs preceding cursors
  within one eight-second deadline; navigation reuses known cursors. Pages past
  the end normalize to the last available page.
- All 26 dependency-free JavaScript tests pass, covering URL restoration,
  navigation, refresh, invalid queries, catalog changes, timeout handling,
  stale responses and exact text rendering. The copied-page regression fails
  against the original script and passes with the change. JavaScript syntax
  and whitespace checks also pass.
- The follow-up's scoped Maven verification could not start because automatic
  approval review timed out twice. A new visual browser check was also blocked
  before execution by the approval service returning HTTP 403. The earlier
  deployed-page checks above predate this follow-up.

```bash
node --test microservices/shardshop/shardshop-product/src/test/js/products.test.cjs
node --check microservices/shardshop/shardshop-product/src/main/resources/META-INF/resources/products/products.js
git diff --check
# Requested but blocked before execution:
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am verify
```

Local product access follow-up:

After successful deployment and acceptance, `up.sh` releases its temporary
snapshot and lifecycle lock, resets its signal handlers, and replaces itself
with the foreground product Service port-forward on localhost port 18080.
Ctrl+C stops forwarding while workloads remain running. A forwarding failure
returns kubectl's exit status without undoing deployment. Both READMEs explain
access, shutdown and reconnecting without another build.

Validation passes all 190 testing-module tests, including 18 startup-script
tests, and 286 common tests. The new cases verify successful acceptance before
forwarding, cleanup before the tunnel starts, deployment and forwarding failures,
INT/TERM handling, and `down.sh` while forwarding is active. These use the actual
lifecycle scripts with isolated command fixtures; a full live deployment was
not rerun. The initial sandboxed run denied child-process inspection; the same
suite passes with that permission, and no fixture processes remain.

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true test
bash -n microservices/shardshop/scripts/up.sh
git diff --check
```

## Step 4.11

**2026-10-21:** order serves the read-only `/orders/` page from its application
image. Buyer home region, optional canonical product ID and creation-date
`ASC`/`DESC` sorting combine with First/Previous/Next/Last navigation. The URL
retains filters, sort and page for reloads, copied links and browser history.
Rows show exact order/buyer/product IDs, UTC microsecond timestamps, status and
cancellation reason, total/currency and item quantities. Refresh only reads;
loading, empty and error states clear stale rows. Assets use `no-store`.

`GET /api/v1/orders` reads every primary in the selected buyer region under one
shared three-second database budget. Product matching uses immutable order items
without restricting seller regions or duplicating orders. Bounded shard pages
are merged by creation time and numeric order ID in the selected direction;
paired timestamp/ID cursors preserve equal-time and microsecond ordering.
Ordering V6 adds date/ID and product/ID indexes, preserving earlier migrations,
orders and runtime grants. A required shard failure fails the whole page.

`up.sh` releases the deployment lock before supervising both localhost tunnels:
product on 18080 and order on 18081. It prints both page URLs after listener
readiness, streams diagnostics, and stops its owned forwards on exit, Ctrl+C
or a forwarding failure without stopping workloads. The new order README follows
the product guide, and both guides describe the shared access lifecycle.

Validation:

- The selected reactor passes 1,592 unit/provider tests, including 307 order
  tests and 19 startup-script tests, plus 71 packaged startup checks. All 21
  selected PostgreSQL/RabbitMQ integration scenarios pass; 101 unrelated
  scenarios are intentionally filtered out. New scenarios cover four shards
  (two US), service-created orders outside the datasets, cross-region products,
  all statuses, tied timestamps and microsecond differences, both directions
  with/without filtering, complete item lists, cursor boundaries, defaults,
  invalid queries, refresh without writes, unavailable-shard failure and
  packaged uncached assets. Existing order acceptance, migration, stock/profit
  and product-browser checks pass in the same run.
- The first reactor run caught a new OpenAPI example with generator-zero bits.
  Replacing it with the existing qualified order example ID fixes the contract
  audit; all assertions remain intact and the rerun passes.
- All 10 dependency-free Node behavior tests pass, covering exact rendering,
  literal HTML-like text, legacy orders without items, filters, sort, all page
  controls, copied URLs/history, invalid IDs, superseded responses, empty/error
  recovery and the complete eight-second browser deadline. Maven executes this
  harness when Node is present; the explicit invocation also passes with no skips.
- Shell syntax and whitespace checks pass. Lifecycle fixtures verify both
  listener URLs, startup/runtime forwarding failures, unexpected clean exits,
  interruption cleanup and the released deployment lock.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-listing or @order-browser or @order-api or @product-browser'
node --check microservices/shardshop/shardshop-order/src/main/resources/META-INF/resources/orders/orders.js
node microservices/shardshop/shardshop-testing/src/test/resources/browser/order-page.test.cjs \
  microservices/shardshop/shardshop-order/src/main/resources/META-INF/resources/orders/orders.js
bash -n microservices/shardshop/scripts/up.sh microservices/shardshop/scripts/lib/lifecycle.sh
git diff --check
```

Live acceptance:

- `up.sh` passes its clean source build, existing image gates, retained-node and
  DNS checks, ordering V6 migrations, application rollout and all five final
  live release/deployment scenarios. Both page listeners become ready and the
  script prints the product and order URLs after releasing its deployment lock.
- The deployed browser verifies US/EU/ASIA, default DESC and ASC for all orders,
  both directions with a product filter, clearing the filter, Next, filtered
  page-2 reload restoration and Refresh. Every visible filtered row contains
  the requested product; displayed dates follow the selected direction and
  preserve UTC microseconds. Product remains available at the same time.
- Ctrl+C exits `up.sh` with status 130 and removes both localhost listeners.
  All five application Deployments remain at their desired ready replica counts.
  `down.sh` then restores the four retained kind nodes to their original stopped
  state without removing data. No live page tunnels remain.

```bash
bash microservices/shardshop/scripts/up.sh
kubectl --context kind-shardshop -n shardshop get deployments \
  -o custom-columns=NAME:.metadata.name,READY:.status.readyReplicas,DESIRED:.spec.replicas
lsof -nP -iTCP:18080 -iTCP:18081 -sTCP:LISTEN
# Press Ctrl+C in the up.sh terminal; repeat the listener and Deployment checks.
bash microservices/shardshop/scripts/down.sh
docker ps -a --filter label=io.x-k8s.kind.cluster=shardshop --format '{{.Names}} {{.Status}}'
git diff --check
```

### Order browser names, search and column sorting

**2026-10-21:** the Created column header now toggles newest/oldest ordering,
with newest first by default and accessible sort direction. Region, product
search, Apply/Clear and Refresh share a control baseline; labels and helper text
sit consistently above and below. Buyer full names and saved product names
replace their IDs in the table. Names wrap in the available width, with narrower
Status/Total columns. Hover, focus or click opens selectable details and separate
Copy name/Copy ID actions; Escape and outside clicks dismiss the details. The
popover respects the visible viewport, including scrollbar space, and reports
clipboard failures without claiming success.

The listing API returns `buyerName` and item `productName` alongside their IDs.
The single search input accepts a canonical numeric ID or a case-insensitive
literal part of a product name. The optional `productName` API parameter cannot
be combined with `productId`; names allow 1–200 Unicode scalar values after
trimming separator characters and reject controls or malformed Unicode. Bound
SQL matches saved item names without catalog calls or wildcard interpretation.
Orders remain unique and retain all items; region, direction and paired cursors
continue to apply before global pagination. No schema migration is needed.

Validation:

- The initial scoped reactor passes 1,610 unit/provider checks, including 325
  order checks, and 71 packaged startup checks. Twenty of 21 selected integration
  scenarios pass, including all updated listing and browser scenarios. One
  existing saved-order retry in the catalog-outage scenario returns
  `503 ORDER_STORE_UNAVAILABLE` instead of `202`. The failing PUT does not enter
  the listing code; its retained logs do not identify the underlying database
  cause. The unchanged scenario passes on the focused rerun, alongside all three
  listing scenarios and the packaged browser scenario: five passed, 117 filtered.
- All 19 final Node behavior tests and the JavaScript syntax check pass. The
  updated harness fails against the original script for missing names, name
  filters, header sorting and popovers. Additional regressions reproduce and
  fix Unicode trimming differences and popover clipping beside scrollbars.
- A real browser using a localhost preview with synthetic data verifies aligned
  41px controls, newest/oldest ordering, sorting resets to page 1, partial-name
  and exact-ID searches, reload restoration, buyer/product details, actual
  clipboard contents, Escape dismissal and visible popover bounds.
- A final package build succeeds and all three packaged browser assets match
  their source bytes. The running kind lab was not redeployed for this follow-up;
  use the existing `up.sh` workflow to deploy these changes.

Commands from the repository root:

```bash
# Initial broad check: one existing outage-recovery scenario failed as described above.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-listing or @order-browser or @order-api or @product-browser'
# Refresh the final UI assets after Java verification; tests above already covered the Java changes.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am -DskipTests package
# Final browser harness, packaged resources, listing and failed-scenario rerun: passed.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  -Dtest=OrderBrowserScriptTest \
  '-Dcucumber.filter.tags=@order-listing or @order-browser or @order-api' \
  '-Dcucumber.filter.name=.*(Validation errors|Product filters|Empty regions|A failed shard|The packaged order).*'
node --check microservices/shardshop/shardshop-order/src/main/resources/META-INF/resources/orders/orders.js
node microservices/shardshop/shardshop-testing/src/test/resources/browser/order-page.test.cjs \
  microservices/shardshop/shardshop-order/src/main/resources/META-INF/resources/orders/orders.js
git diff --check
```

## Step 4.12

**2026-10-21:** the shared currency policy restricts new product prices and costs
by immutable seller country: US/USD; EU/EUR or GBP; CN/CNY, VN/VND, KR/KRW and
JP/JPY within ASIA. Product validates arbitrary API entities independently of
workload definitions. Buyer location does not restrict purchases; each order
keeps one product currency. New prices, costs, totals and ledger commands use exact
currency precision. JPY, KRW and VND require whole units; fixed-two-decimal storage
and transport remain unchanged. Both pages display native precision without
rounding historical fractional money.

Catalog V6 backfills country only from matching catalog-v1 key/company/region
provenance and exposes an operator report for unresolved or noncompliant rows.
Legacy products remain readable, with explicit eligibility metadata, and are
blocked from new purchases when they fail the policy. Ordering V7 preserves
existing rows as policy version 1; new orders explicitly save version 2. The stock
worker emits the saved version. Ledger accepts both envelopes and preserves
saved decisions, including historical fractional currency and rejection replay.
Identical accepted-order retries use their saved snapshots before catalog checks.

Catalog-v2 keeps old keys intact and adds policy-compliant definitions for 151
sellers and 201 products. Its supplemental Vietnam seller is explicitly synthetic.
The seeder, reader and producer verify country and eligibility metadata. Normal
producer load selects all seven currencies and groups same-currency products,
independently of buyer location. A version/mode namespace keeps old workload
allocations intact. Normal ledger and Helm settings allow all seven currencies;
rejection drills temporarily exclude EUR and restore the ledger before resuming
the producer. The first retained deployment brings the v2-capable ledger online
before resuming the paused order rollout.

Validation:

- The final clean source reactor passes 1,665 unit/provider checks and 71 packaged
  startup checks. The product page's 36 Node tests and order page's 20 Node tests
  pass, including native currency precision and unchanged legacy fractional text.
- The broad PostgreSQL/RabbitMQ run executes 125 scenarios: 113 pass initially.
  It exposes strict workload decoders still expecting the old product response,
  stale contract and terminal-status expectations, and outage fixtures that put
  order storage on the catalog shard being disabled. These are corrected and
  covered by the focused rerun. A transient product creation deadline and an
  unchanged broker dead-letter retry timeout are also rerun without relaxing
  deadlines or assertions.
- The focused currency scenarios confirm all seven currencies across buyer
  regions with exact totals/profits, forbid new noncompliant catalog purchases,
  preserve mixed-currency rejection, and replay identical accepted orders after
  seller-country loss. A saved schema-v1 JPY order preserves its fractional
  `39.90` total and `14.90` profit through restart and duplicate delivery.
- The focused rerun passes 41 of 42 selected scenarios, with 83 filtered out.
  Its only failure is the unchanged RabbitMQ capacity/backpressure scenario,
  which again times out waiting 210 seconds for a dead-letter retry. This
  remaining broker test failure is not described as a passing full suite.
- The first retained deployment exposes a remaining USD/EUR-only guard in the
  producer's HTTP submission path. Six regression cases fail before the fix;
  the guard now uses the shared currency policy. All 140 producer unit tests
  pass, followed by all three packaged producer scenarios, including accepted
  HTTP submissions in every supported currency and deterministic restart.
- The final `up.sh` run qualifies every image and passes all five deployment
  acceptance scenarios on the retained lab (application revision 35). The saved
  CNY allocation from the failed deployment becomes confirmed without a reset;
  live queries find confirmed policy-v2 orders in all seven currencies and the
  expected 201 catalog-v2 products. All nine retained sample hashes (the oldest
  ten orders, products and profit credits on each shard) match their pre-upgrade
  values. Local forwarding initially encounters occupied ports from stale
  tunnels after rollout; after those stale tunnels exit, the existing
  `page_access` helper restores both localhost pages without another deployment.
- Both retained-lab saga drills pass: duplicate decisions/rejection replay,
  stock competition and atomic rollback, then CDC recovery on another worker.
  Cleanup restores the normal seven-currency ledger before restarting the
  producer. All application replicas are ready and no rollout is left paused.
- Live browser checks cover product and order views in US, EU and ASIA. New
  prices show USD, EUR/GBP and country-specific ASIA currencies; JPY/KRW/VND
  prices, earnings and order totals display whole units. Historical incompatible
  products retain their original amounts and show the legacy notice. The US
  order view includes confirmed purchases in all seven currencies. Both local
  page tunnels and the retained lab remain running.

Commands from the repository root:

```bash
# Broad regression run: 113/125 scenarios passed before the corrections above.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify
# Final source/unit checks and focused integration rerun: 41/42 selected scenarios pass.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@contracts or @order-api or @currency-policy or @order-producer or @product-seeder or @product-reader or @product-api or @rabbitmq'
# Producer submission regression and final packaged HTTP verification: passed.
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-workload/shardshop-order-producer -am package
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  -Dtest=KindWorkloadPolicyTest -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false \
  '-Dcucumber.filter.tags=@order-producer'
# Retained deployment and its acceptance gates: passed; see forwarding note above.
bash microservices/shardshop/scripts/up.sh
# Retained saga drills: both scenarios passed. Prepare "$snapshot" as the README
# verification snapshot section describes.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-saga-drill
# Restore only page access after stale tunnels release their ports (run in Bash).
source microservices/shardshop/scripts/lib/lifecycle.sh
context=kind-shardshop
page_access
node --test microservices/shardshop/shardshop-product/src/test/js/products.test.cjs
node microservices/shardshop/shardshop-testing/src/test/resources/browser/order-page.test.cjs \
  microservices/shardshop/shardshop-order/src/main/resources/META-INF/resources/orders/orders.js
git diff --check
```

### Step 4.12 validation fixes

**2026-10-21:** A validation of the step found seven problems, and the user asked
to fix all of them with the recommended options.

1. The normal producer gave each currency one seventh of the orders, but catalog-v2
   has one VND product and four KRW products. On the lab, the VND product lost
   approximately 460 units each hour and had 402 of 1,000 units after approximately
   80 minutes. Balanced traffic now starts one order with each product in every
   window of 201 ordinals, in a run-specific order, and takes that product's currency.
   Up to two following products of the same currency complete the order. Thus each
   product is in at most three orders of a window, and at the lab rate 1,000 units
   last approximately 20 hours. The allocation mode is `balanced`, thus the retained
   `:catalog-v2:all` orders stay unchanged. The lab producer was stopped from 14:09
   to 14:50 UTC to keep 402 VND units until this fix was deployed.
2. The `up.sh` saga gate stopped the producer, waited for all sagas and restarted the
   ledger two times with an out-of-band allowlist in each deployment. The gate now
   checks that the deployed allowlist is every supported currency, and the normal
   policy must confirm its USD and EUR orders. The gate changes no deployment or
   workload. The explicit saga drill keeps the scoped EUR rejection and replay.
3. The catalog count gate accepted any number of unknown rows. It now counts
   catalog-v2, the gate fixture, the retained catalog-v1 and the retained step 4.9
   gate fixture separately. Any other row fails the gate.
4. The evidence had no complete integration run after the corrections, and the
   producer command needed locally installed SNAPSHOT JARs. The full suite now passes
   (below). The producer command has `-am`, and the drill command names the README
   snapshot instead of a temporary path.
5. Copies of the currency policy had no test that detects a difference. A catalog
   migration scenario gives the V6 report a seller for every ISO country in each
   region, with products in all supported currencies, and compares each reason with
   `CurrencyPolicy`. Unit tests compare the zero-minor-unit lists of both pages, the
   RecordOrder version 2 enums, the ledger default and the Helm default and schema
   with `CurrencyPolicy`. The drill builds its scoped allowlists from `CurrencyPolicy`.
6. VERSIONS.md records the new runtime dependency of `shardshop-datasets` on
   `shardshop-common`. The `SellerOk` example gives the French seller EUR and GBP
   balances.
7. The one parked command in `ledger.record-order.dlq` is a duplicate. Its USD order
   was created at 12:11 UTC, before the 4.12 rollout. The command failed six
   deliveries at 12:12 UTC, when database and broker pods restarted; the relay
   published it again at 12:16:18 UTC, and the ledger recorded it. The parked copy
   stays for an operator.

Validation:

- Before the fix, the new planner test failed for the reported reason: the VND
  product was in 28 of 201 orders of one window. The earlier count rule accepted 544
  unknown rows in its own unit test.
- Mutants in scratch copies: CH in the EU list of `CurrencyPolicy` fails the report
  scenario (6 rows differ). CHF in the supported list fails the ledger default, the
  RecordOrder schema and the Helm guards. VND removed from the page lists fails both
  page guards.
- After the fixes, the source reactor passes **1,659 unit/provider tests** (the 11
  cases of the removed seven-way choice are gone, 5 tests are new) and **71 startup
  checks**. The full `-pl shardshop-testing -am -Pintegration clean verify` passes
  **127 scenarios** in 33 minutes, including the RabbitMQ capacity scenario and the
  new report scenario. The only WARN line is the accepted Ryuk line. The capacity
  scenario also passed in 191 seconds in a full run before the fixes; the earlier
  timeouts happened while the Mac slept several times between 15:04 and 16:38 local
  time. The corrected producer command passes its three scenarios in 3 minutes.
- `up.sh` deployed application revision 36. The release, catalog, topology and saga
  gates passed. The saga gate confirmed its USD and EUR orders, and the ledger and
  producer had only their rollout ReplicaSets. The reader gate failed: during a
  stall of the whole Docker VM after the rollout, one product pod deadlocked (see the
  open problem below), and 1–5 reader requests in each interval exceeded the 5-second
  deadline. After that pod was replaced, all four deployment scenarios passed, and
  both saga drills passed in 2 minutes 24 seconds and restored the lab.
- In its first 546 balanced ordinals, the lab producer used every product: 8–24
  units each, and 2 units of the VND product. All orders were confirmed.

Problem found during this verification, not changed in that pass (see the later
[source fix](#http-deadline-lock-inversion-fix)): the product
`CatalogDeadline` timer holds the request state monitor while it calls
`connection().close()`. At the same time, a worker thread that ends the response holds
the Vert.x connection monitor and runs the end handler, which needs the state
monitor. A SIGQUIT thread dump showed this Java-level deadlock; one event loop stayed
blocked for more than 10 minutes. `OrderDeadline` has the same code. Both are from
steps 3.6 and 4.1.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-order-producer -am test \
  -Dtest=OrderPlannerTest -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
bash microservices/shardshop/scripts/up.sh
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot"
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify -Dit.test=KindIT \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-saga-drill
```

## HTTP deadline lock-inversion fix

**2026-10-21:** fixed the preexisting `CatalogDeadline` and `OrderDeadline`
lock inversion exposed by the VM stall during step 4.12 verification. The saved
SIGQUIT dump shows the product timer holding request state while waiting for
`Http1xServerConnection`; a response worker holds that connection while invoking
an end handler that waits for request state. The pinned Vert.x 4.5.34 sources
also synchronize response status reads on the connection.

Both filters now hold their state monitor only to finish a request or decide
whether the writer or timeout owns its response. Transport reads, status/body
writes, connection closing and timer cancellation occur outside that monitor.
The timeout still returns the existing 503 before serialization or closes a
response already claimed by the writer. Completed or expired requests suppress
late serialization. The four-second product and ten-second order deadlines,
error contracts and business transaction behavior are unchanged.

Validation:

- Before the fix, three coordinated product regressions reproduce the lock
  cycle. Five order regressions reproduce it at response status reads, response
  ending, connection closing and timer cancellation; a sixth catches late
  serialization after completion. Timed test locks break the cycle on failure
  so the test JVM does not retain deadlocked threads.
- After the fix, all 25 product and nine order deadline tests pass, including
  timeout/writer ownership and blocked-serialization coverage. The selected
  reactor passes 619 product/order unit/provider tests, 501 core dependency
  tests and all 71 packaged routing/startup checks.
- The packaged HTTP suite passes 192 testing-module unit checks and 24 of 25
  selected integration scenarios, including deadline and recovery scenarios.
  The pooled-connection concurrency scenario initially fails during fixture
  setup: creating a product returns `503 CATALOG_UNAVAILABLE` with the store's
  unknown-outcome message, before the concurrency assertion runs. Its unchanged
  focused rerun passes. Provider logs contain no warning/error explaining that
  setup failure, so its cause remains undetermined; the initial run is not
  recorded as wholly passing.

This source fix has not been redeployed to the running lab. The deployment and
saga results in the preceding section belong to the earlier application revision.

Commands from the repository root:

```bash
# Red regressions against the original implementations: expected failures above.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am test \
  -Dtest=CatalogDeadlineTest -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am test \
  -Dtest=OrderDeadlineTest -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false
# Final module and packaged startup verification: passed.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product,shardshop-order -am verify
# Packaged HTTP integration: 24 passed; one fixture setup failed as described above.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  '-Dcucumber.filter.tags=@product-api or @order-api'
# Unchanged focused rerun using the already-built providers and test classes: passed.
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration \
  failsafe:integration-test failsafe:verify '-Dcucumber.filter.tags=@order-api' \
  '-Dcucumber.filter.name=Concurrent requests wait for a pooled connection instead of returning 503'
```

## Local webpage port cleanup

**2026-10-21:** webpage access now belongs to the separate, argument-free
`scripts/webpages.sh`. It clears listeners on local ports 18080 and 18081,
then starts and supervises the product and order tunnels and prints both URLs.
It uses `lsof` to identify listening PIDs, sends TERM, waits up to five seconds,
then sends KILL to any remaining listeners. Both ports must be released within
two further seconds; inspection or cleanup failure stops the script. Processes
outside those listener results are untouched.

`up.sh` accepts no arguments and returns after deployment and acceptance,
releasing its lock and snapshot. It leaves local listeners alone and prints the
command for opening the webpages. `webpages.sh` requires only `kubectl` and
`lsof`, without building, deploying or taking the deployment lock. Ctrl+C closes
both tunnels owned by that invocation and leaves the lab running.

Validation: all **199 testing-module tests** pass, covering argument rejection,
successful deployment without port inspection or forwarding, existing listeners
left untouched by deployment, TERM/KILL cleanup before webpage forwarding,
unlisted-process preservation, and tunnel failure/interruption cleanup. Bash
syntax and diff checks pass. Two successive live `webpages.sh` invocations
replaced the first pair of listeners; both page URLs returned HTTP 200 before
and after replacement. Ctrl+C removed both test tunnels, with no application
redeployment.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing test
bash -n microservices/shardshop/scripts/up.sh microservices/shardshop/scripts/webpages.sh microservices/shardshop/scripts/lib/lifecycle.sh
```

Live access was checked by running `./webpages.sh` from
`microservices/shardshop/scripts` twice, requesting `/products/` on port 18080
and `/orders/` on port 18081 with `curl --fail --silent --max-time 10`, and
interrupting the second invocation before checking both ports were released.

## Regional order currency distribution

**2026-10-21:** normal producer traffic now follows buyer-region preferences:
US orders are 90% USD; EU orders are 80% EUR and 10% GBP; ASIA orders are 60% CNY
and 10% each KRW, JPY and VND. The remaining 10% in each region is split equally
between the other two regions. Foreign EU purchases use EUR:GBP at 4:1, following
the requested ASIA example. Foreign ASIA purchases preserve CNY:KRW:JPY:VND at
6:1:1:1: 3⅓% CNY and 5⁄9% each KRW, JPY and VND of US/EU orders.

A deterministic weighted schedule spreads the choices through cycles of 900
orders per buyer region, interleaving all three regions in the full dataset.
Products rotate within each currency across regions and cycle boundaries.
The default allocation namespace is now `catalog-v2:regional-v1`, so the new
buyer/currency/item selections cannot conflict with retained `catalog-v2:balanced`
orders. Explicit USD/EUR test modes preserve their selections and allocation
namespaces. Product currency rules and purchase eligibility are unchanged.

Validation: **134 producer tests**, **328 dependency tests** and **199 testing-module
tests** pass. All **three selected `@order-producer` packaged HTTP/PostgreSQL
scenarios** pass; the other 124 integration scenarios are excluded by the tag.
Coverage includes exact counts over repeated cycles, home preference in short
samples, every currency for every buyer region, rotation through all products,
reconstruction from reordered lookup results, cycle boundaries, `Long.MAX_VALUE`,
subset buyer fixtures, HTTP retries and restart recovery. The normal HTTP scenario
checks 120 accepted orders across all three buyer regions and all seven currencies.
`git diff --check` passes.

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-order-producer -am verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify '-Dcucumber.filter.tags=@order-producer'
```

This change has not been deployed to the running lab. The next `scripts/up.sh`
build and rollout starts the versioned stream; historical orders remain unchanged.

## Step 4.13

**2026-10-22:** the order producer, the product reader and the product seeder each
serve a dedicated control webpage (Workload Order Producer at
`/workload-order-producer/`, Workload Product Reader at `/workload-product-reader/` and
Workload Product Seeder at `/workload-product-seeder/`) and a workload-owned control/status API under
`/api/v1/<workload>/control`. The new `shardshop-core/workload-control` library holds
the shared adjustable pacer, the control state, the REST resource, the error
mappers, the [contract](shardshop-core/workload-control/src/main/resources/contracts/openapi.yaml)
and one page script and stylesheet. A Maven Enforcer rule keeps it, like the
datasets, inside the workload modules. The user selected the extension of the
custom step 3.8 pacer over Guava `RateLimiter` and Bucket4j.

- **Controls:** a numeric target rate with −/+ steps, Apply, Pause and Resume
  change only the receiving process at once. Units: new order starts/s (producer,
  default 0.5, maximum 100), product read starts/s across all workers (reader,
  200, 10,000) and outgoing HTTP requests/s including creation, verification and
  retries (seeder, 100, 1,000). The server rejects non-finite, negative, too precise
  or too large rates and malformed bodies with `400` and keeps the previous
  setting; each accepted update is atomic and increments a revision.
- **Pacing:** one schedule per process; a change or Resume wakes waiting callers
  and never sends missed starts. Zero stops new work like Pause. Pause drains the
  operation in flight within its existing deadline and retry budget; the producer
  observes its current order until it is terminal. The producer replay after a
  restart is not paced, but Pause holds it. The producer's `order-interval` setting
  is replaced by `order-rate`; the default keeps the earlier ~0.5 orders/s.
- **Status:** state (`WAITING`, `IDLE`, `RUNNING`, `PAUSING`, `PAUSED`, `COMPLETED`,
  `FAILED`), effective target, achieved rate of the last 10 seconds, operations in
  flight, and backend requests, retries and errors by purpose. The producer
  separates startup, allocation, submission and polling. A concurrency notice
  shows the reachable rate when the work in flight cannot reach the target.
- **Seeder modes:** the bootstrap Job is unchanged and serves no HTTP
  (`quarkus.http.host-enabled=false`). The new `shardshop-product-seeder-control`
  Deployment runs the same image with `SHARDSHOP_SEEDER_INTERACTIVE=true` and
  starts one finite run at a time on request (`409 RUN_ACTIVE` otherwise).
- **Deployment and access:** ClusterIP Services and TCP readiness probes for the
  reader, the producer and the interactive seeder; `KindRelease` now also accepts
  the three Services and the new Deployment, and the workload policy accepts the
  new rate and mode settings. `webpages.sh` opens five tunnels (18080–18084) and
  prints all URLs. The settings live in the process: each `up.sh` restores the
  defaults.

Validation:

- Module tests pass: 63 in `shardshop-workload-control` (pacer timing with a
  controlled clock, wake-up of reserved starts, zero, interruption, state machine,
  validation, 64 concurrent updates, request parsing and the contract against the
  written JSON), reader 84, producer 141 and seeder 61. The 95 selected
  `shardshop-testing` unit tests pass, including 26 startup-script, 21 release,
  20 Helm and 10 workload-policy tests.
- All 12 Node checks of the shared page script pass against the three shipped
  pages. Three temporary script mutations (four decimals accepted, out-of-order
  status rendered, unapplied input replaced by a poll) each failed exactly the
  check that guards them.
- `WorkloadControl.feature` passes its three PostgreSQL scenarios in 119 s:
  reader 50→200→10 reads/s, Pause, Resume without a burst, zero, nine invalid
  bodies, 20 concurrent updates and restart defaults; producer pacing at 2 and
  1 orders/s, the one-order limit at 50, Pause with an order in flight, consecutive
  ordinals and isolation from the reader; interactive seeder at 50 requests/s,
  Pause, `409`, two complete runs with 1,056 requests each, the stored digest and
  an unchanged catalog. Every control response matches the contract. The first
  seeder draft expected 200 requests/s; one request in flight with ~7 ms product
  creations reaches only ~140/s, which the scenario now asserts as the limit.
- The existing reader, seeder, producer and product/order page features pass
  (18 scenarios, 342 s).

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/workload-control -am test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-workload/shardshop-product-reader,shardshop-workload/shardshop-order-producer,shardshop-workload/shardshop-product-seeder test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@workload-control'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@product-reader or @product-seeder or @order-producer or @product-browser or @order-browser'
node microservices/shardshop/shardshop-testing/src/test/resources/browser/workload-control-page.test.cjs \
  microservices/shardshop/shardshop-core/workload-control/src/main/resources/META-INF/resources/workload-control/control.js \
  microservices/shardshop/shardshop-workload/shardshop-order-producer/src/main/resources/META-INF/resources/workload-order-producer/index.html \
  microservices/shardshop/shardshop-workload/shardshop-product-reader/src/main/resources/META-INF/resources/workload-product-reader/index.html \
  microservices/shardshop/shardshop-workload/shardshop-product-seeder/src/main/resources/META-INF/resources/workload-product-seeder/index.html
bash -n microservices/shardshop/scripts/up.sh microservices/shardshop/scripts/webpages.sh microservices/shardshop/scripts/lib/lifecycle.sh
git diff --check
```

Live acceptance:

- `up.sh` resumed the stopped lab and passed in 14 minutes: the clean build with
  1,838 module tests, the image gates, the lifecycle gates, migrations, application
  release revision 2 and all five final acceptance scenarios. All six Deployments,
  including `shardshop-product-seeder-control`, became ready.
- `webpages.sh` printed the five URLs. In the browser, each control page showed
  its live state; the page rejected `12.3456` without a request.
- Producer, measured as new allocations in the shard databases: 0.500/s at the
  default; 0.816/s at target 2, where the page showed the one-order limit (1.33 s
  per order through the live saga, at most 0.75/s); `PAUSED` with 0 allocations in
  30 s; 0.487/s after the page restored 0.5.
- Reader, measured with product's `/q/metrics` request counters: 203/s at the
  default, 506/s at 500, 0 in 20 s while paused, 202/s after the page restored 200.
- Interactive seeder: a run at 20 requests/s, Pause at operation 910 of 1,056 with
  no seeder request at product for 16 s, Resume at 10/s (5.1 product lookups/s at
  product), then `COMPLETED` with 1,056 requests and no error. Product answered all
  151 seller and 201 product POSTs with `200` and none with `201`; the run digest
  equals the bootstrap Job digest `9ef74a31…`. The page restored the default 100.
- Each revision counted only its own updates (producer 4, reader 4, seeder 5).
  Working sets: reader 207 MiB, producer 179 MiB, interactive seeder 163 MiB.
- SIGTERM to `webpages.sh` removed all five tunnels and listeners; the workloads
  continued. (A background job of the tool shell ignores SIGINT at entry; the
  script tests cover Ctrl+C.) `down.sh` returned the four retained nodes to their
  earlier stopped state.

```bash
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/webpages.sh
curl -s http://127.0.0.1:18083/api/v1/product-reader/control
bash microservices/shardshop/scripts/down.sh
```

## Step 4.14

**2026-10-23:** implemented concurrent order writes and a separate saga process.
The final live deployment and capacity evidence is recorded below.

- **Producer:** allocation and PUT share a bounded submission window (default 4,
  maximum 12); an independent fair observer polls pending orders (default 2,
  maximum 12). The outstanding-order limit (default 100, maximum 10,000) applies
  backpressure before another submission starts. Dense deterministic ordinals,
  allocation identities, retry payloads and currency selection survive concurrency.
  Restart replays from zero with the same pacing and bounds, counts recovered
  orders separately and observes pending recoveries. At the default 0.5 starts/s,
  history recovery is slower than the earlier unpaced replay; this preserves the
  request budgets and prevents a restart burst. Pause drains submissions while
  observation continues. The default remains 0.5 starts/s.
- **Processes:** one order image selects `shardshop.order.process=http|saga`
  (default `http`). Two HTTP replicas use `RollingUpdate`; one saga replica uses
  `Recreate` and owns stock/profit workers, CDC publication and result consumption.
  Each JVM reserves its own generator slot. Helm, release observers and kind gates
  recognize the separate Deployment. The three saga drill scenarios cover business
  invariants, HTTP rollout preserving the saga pod and CDC active PIDs, and saga
  restart while writes continue.
- **Write path and status:** PUT's `Idempotent-Replay` response header classifies
  recovery without a GET probe. Order resolves each catalog product once and
  batches item inserts. Producer status and its page show concurrency, backlog,
  backpressure, recovered and terminal counts, and write/saga latency. Per-JVM
  `/internal/order/write-metrics` reports allocation and PUT request counts, errors
  and rolling latency; query both HTTP pods to assess the deployment.

Validation:

- The broad reactor verification passed 1,858 unit/startup checks and all 56
  selected packaged scenarios; the later producer run passed all four selected
  scenarios and ten policy tests. Logs: `/tmp/shardshop-414-integration.log` and
  `/tmp/shardshop-414-producer-integration.log`.
- The final `up.sh` run passed 1,863 unit/startup checks, image gates and all five
  acceptance scenarios. Log: `/tmp/shardshop-414-up-final.log`.
- Final CDC/process integration passed 17 selected scenarios plus 10 policy tests;
  log: `/tmp/shardshop-414-cdc-integration-final.log`.
- The order module passes all 350 unit tests with `mvn -pl microservices/shardshop/shardshop-order test`.
  The CDC shutdown regression fails before the fix in `/tmp/shardshop-cdc-regression-before.log`.

Commands from the repository root:

```bash
bash microservices/shardshop/scripts/up.sh
mvn -pl microservices/shardshop/shardshop-order test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-producer or @workload-control or @order-processes or @stock-reservation or @cdc-relay or @order-results or @order-api or @contracts'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  -Dtest=KindWorkloadPolicyTest '-Dcucumber.filter.tags=@order-producer'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pintegration verify \
  -Dtest=KindWorkloadPolicyTest '-Dcucumber.filter.tags=@cdc-relay or @order-processes'
```

Initial live acceptance and tuning:

- `up.sh` passed the clean build (1,859 unit/startup checks), image gates, retained
  cluster checks and all five final acceptance scenarios. A transient Docker Scout
  Java database download failed before cluster mutation; retry passed with the
  existing advisory gates unchanged.
- With the original profit batch of eight per shard and 250 ms poll, 20 starts/s
  achieved 20.06 new and confirmed orders/s, a final rolling write p95 of 44.69 ms,
  saga p95 of 1,776.54 ms, and 8–45 pending profit credits. At target 30, throughput
  was 29.76 new and 29.74 confirmed orders/s, but profit credits grew from 387 to
  4,223 in 130 s. Confirmed status alone therefore did not establish sustainable
  throughput. The chart now uses the existing serial profit worker with a bounded
  batch of 32 and a 100 ms poll; 23 Helm rendering tests and the configuration-only
  release's five acceptance checks pass.
- A later load run under heavy host contention exposed an existing CDC shutdown
  defect: after a database connection timeout, interrupting Debezium cleanup left
  its replication stream active and prevented shard-a from restarting. The new
  regression failed before the fix for that interruption. Shutdown now closes and
  joins the engine while retaining ownership; a latch wakes retry backoff without
  interrupting cleanup. All 350 order tests, including three shutdown regressions,
  pass. The failed producer bounded pending orders at 100 and enforced its deadline;
  a saga replacement drained those retained orders, and the producer restarted at
  its configured 0.5/s.

Final measured phases used a 20-second warmup and approximately ten-second
sampling. Interval rates use first/last cumulative counters; latency values are
rolling-window p95 at the last sample (1,024 new orders for the producer, 4,096
requests per HTTP endpoint/JVM). HTTP windows can include earlier phases and
replay. Early samples at 10/s exceeded 100 ms while earlier slow requests remained
in the producer window; the final window below covers the warmed run.

| target | interval | new/s | confirmed/s | cancelled/s | terminal/s | write p95 | saga p95 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10/s | 129.977 s | 9.7787 | 9.5171 | 0.3539 | 9.8710 | 91.420 ms | 1,789.633 ms |
| 30/s | 90.003 s | 29.4547 | 28.1103 | 1.5555 | 29.6658 | 93.732 ms | 2,158.506 ms |

Final per-pod HTTP rolling p95 (PUT/allocation) was 27.064/32.114 ms and
23.931/17.354 ms at 10/s, then 43.440/35.307 ms and 39.624/27.859 ms at 30/s.
Aggregate pending/profit/outbox first→last and min–max were 21→9, 3→3, 11→3
and 7–21, 0–9, 2–11 at 10/s; 46→36, 21→27, 22→28 and 36–84, 3–73, 14–28
at 30/s.

The saturation probe (main 30/s plus temporary local producer 10/s) achieved
22.67885 new/s, 21.47879 confirmed/s and 1.733415 cancelled/s; the window filled
at 200 and ended at 152, with saga latency reaching about 15 s. Thus 30/s is the
highest verified rate, not a universal maximum: host contention and finite stock
affect the result. The host had 12 Docker CPU, 12.65 GiB Docker memory, 16 GB
physical memory and about 19 GB swap after IntelliJ stopped.

Allocation-only burst capacity was 409.38/637.91/829.34 requests/s at concurrency
4/12/24, with 80/240/480 HTTP 201 responses and p95 15.250/29.984/44.938 ms.
A controlled pool-bound probe (four connections per shard, one-second acquisition
wait, 24 concurrent requests) held the allocation tables in transactions for
approximately 1.5 seconds and rolled them back. It returned 12 HTTP 201 and
12 HTTP 503 responses, verifying bounded pool acquisition rather than an immediate
rejection of every request beyond 12 concurrent clients.
The one-off probes were `python3 /tmp/shardshop-414-final-measure.py`,
`python3 /tmp/shardshop-414-saturation.py`, `python3 /tmp/shardshop-414-capacity.py`
and `python3 /tmp/shardshop-414-pool-bound.py`; helpers and JSON are ephemeral and
not versioned. Rates were restored to 0.5/s and the temporary producer drained and
stopped. Stock after the 10/s and 30/s phases included KRW 40 and VND 0, so these are finite-inventory
observations rather than unconstrained capacity claims. No SQL functions, triggers
or data reset were used.

All three kind recovery drills passed in 2 min 20 s: duplicate/rejection replay and
same-stock races preserve permanent effects; CDC resumes on another worker while
the HTTP replicas commit orders; and an HTTP rollout preserves the saga pod and
all active CDC slot PIDs. Log: `/tmp/shardshop-414-kind-drills.log`. The command used
the rendered snapshot of the current chart and kind values:

```bash
task_snapshot=$(cat /tmp/shardshop-414-kind-snapshot-path)
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -Pkind \
  -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify \
  -Dit.test=KindIT -Dshardshop.kind.tags=kind-saga-drill \
  -Dshardshop.kind.snapshot="$task_snapshot"
bash microservices/shardshop/scripts/webpages.sh
```

The final browser check displays the 30/s bound, submission and backlog fields,
backpressure, separate write/saga latency and recovered-order count. After the
drills, the producer is running at 0.5/s and replaying history; the reader remains
at 200/s and the interactive seeder idle at 100/s. All are unpaused. No replay
completion is claimed at the default rate. Task-owned tunnels and the temporary
load generator are stopped. `bash microservices/shardshop/scripts/down.sh` returned
the retained lab to its original stopped state; all four kind node containers are
stopped and ports 18080–18085/18181–18182 have no listeners. `git diff --check`
passes; no SQL file or applied migration changed.


## Step 4.15

**2026-10-23:** simplified the root architecture into four high-level areas:
system architecture, runtime topology, regional sharding and measured performance.
The system-design SVGs in the sibling `Anki/system design/` directory informed
the level of abstraction; diagrams remain editable **Mermaid in Markdown**, as
requested. No SVG asset is retained.

Deep contracts moved to 16 owning `ARCHITECTURE.md` files: product, order, ledger,
core and its four libraries, workloads and its four submodules, database,
infrastructure and testing. The overview and module guides provide navigation;
existing links and numerical section references now lead to the owning designs.
All eleven acceptance scenarios retain their requirements and stable scenario
anchors. Implemented behavior and future reconciliation, retention, failover,
restore and AWS requirements remain distinct. Operational commands stay in
runbooks and dated evidence stays here. No Java, deployment defaults, SQL or
applied migrations changed.

### Topology and measurement conditions

The current Helm shape is nine application pods, nine shared PostgreSQL pods,
one ledger PostgreSQL pod and one RabbitMQ pod: **20 in `shardshop`**. Live
inventory found all 36 running pods ready: those 20, one CNPG operator, 14 in
`kube-system` and one local-path provisioner. Five retained completed Job pods
were excluded. The kind lab has four node containers; these are not extra pods.

The stopped retained lab was resumed with the existing lifecycle helper and DNS
guard, without rebuilding or redeploying. The read test used the existing two
product HTTP replicas, primary reads, one reader with 16 workers/16 HTTP
connections and no product cache. The host has 16 GB physical memory; Docker has
12 CPUs and 13,580,972,032 bytes (12.65 GiB) memory. A host snapshot during the run
showed 20,669 MiB swap used. Kubernetes Metrics API was unavailable, so this run
makes no per-pod CPU-saturation claim.

The producer was paused and drained within the bounded wait: zero in-flight and
non-terminal orders, zero new orders; its preceding requests were historical
replay. The interactive seeder stayed idle. Startup/reconnect errors during node
resumption are outside the measurement windows. Product reads sampled the 201
catalog products uniformly: 50 US, 100 EU and 51 ASIA, so equal per-shard traffic
would be an incorrect assumption.

### Read capacity

The [measurement protocol](shardshop-testing/ARCHITECTURE.md#capacity-measurement)
was fixed before the probe: at least 30 seconds warmup, then three complete
30-second reports; each must deliver at least 95% of target, have only `OK`
outcomes, and client p95 below 100 ms. A phase is bounded to 150 seconds. Reports
ending before 60 seconds after a rate update were discarded to exclude mixed
windows. The bounded ladder stopped at the first failure and tested one midpoint.

| Target reads/s | Three observed windows, reads/s | Mean reads/s | Window p95, ms | Result |
|---:|---|---:|---|---|
| 500 | 489.0 / 485.0 / 487.0 | 487.02 | 6 / 14 / 8 | Pass |
| 1,000 | 950.7 / 965.8 / 972.9 | 963.16 | 10 / 7 / 6 | Pass |
| 2,000 | 1,901.3 / 1,841.1 / 1,831.3 | 1,857.90 | 5 / 9 / 9 | Fail: two windows below 1,900/s |
| 1,500 | 1,437.0 / 1,448.8 / 1,417.4 | 1,434.43 | 5 / 5 / 8 | Fail: final window below 1,425/s |

Every measured read in all four phases returned `OK`. Mean rates use request
counts divided by the summed reported interval seconds; rates and durations in
the logs are rounded. At the highest passing target, **86,684 successful reads
in 90 reported seconds** give **963.16/s**. Its per-window p99 values were
40/26/24 ms and maximum latencies 203/139/128 ms. Measured shard totals were:

| Shard | Requests | Requests/s |
|---|---:|---:|
| US / shard-a | 21,577 | 239.74 |
| EU / shard-b | 43,169 | 479.66 |
| ASIA / shard-c | 21,938 | 243.76 |

The passing point is a **verified lower bound**, not the product/database maximum.
The higher phases served more reads but did not supply the requested rate within
tolerance. Low p95 and no errors do not prove which host/generator/service resource
caused that undersupply. At target 2,000 one pool snapshot used all 16 connections;
other snapshots used one or three, and no pending pool request was captured.
These snapshots do not measure peak occupancy. The 10,000/s control bound was not
reached or qualified. A saturation maximum needs stronger independent load
generation and host headroom; peak reads and writes were not tested together.
The [raw interval evidence](shardshop-testing/evidence/2026-10-23-read-capacity.json)
retains successful and failing phases, regional counters, latency and pool values.

### Order capacity interpretation

The unchanged deployment reuses the final [step 4.14](#step-414) order evidence:
20-second warmup, 90–130-second holds, four submitters, two observers and a
100-order bound, with product reads targeting 200/s concurrently. Target 30/s
accepted 29.4547 new orders/s, confirmed 28.1103/s and cancelled 1.5555/s. Final
rolling write p95 was 93.732 ms, saga p95 2,158.506 ms; pending/profit/outbox ranges
were 36–84 / 3–73 / 14–28. Target 40/s filled the combined 200-order bound and
failed to sustain the requested pace. The serial stock worker, finite stock and
host contention limit interpretation of that saturation attempt. Confirmed and
cancelled rates are reported separately because cancellations skip ledger work.

The equal buyer-region schedule gives a **derived** 9.82 accepted new orders/s
per buyer shard. Allocation plus submission imply about 58.91 write API requests/s
total or 19.64/s per buyer shard. Neither is SQL writes/s or IOPS; stock and profit
also touch seller shards, and ledger work uses its separate database. The
allocation-only single-home-shard burst of 829.34 requests/s lasted less than a
second and is not full-order sustainable throughput.

### Validation and restoration

Commands from the repository root:

```bash
bash -c 'set -euo pipefail
source microservices/shardshop/scripts/lib/lifecycle.sh
lifecycle_init
snapshot_configuration
nodes=$(kind get nodes --name "$cluster_name")
resume_cluster
verify_dns'
bash microservices/shardshop/scripts/webpages.sh
python3 /tmp/shardshop-415-read-capacity.py
kubectl --context kind-shardshop get pods --all-namespaces -o json
python3 /tmp/shardshop-415-doc-links.py
git diff --check
```

The bounded Python probe is an ephemeral orchestrator of the existing control
HTTP endpoints and Java reader log reports; the versioned [runbook](shardshop-testing/RUNBOOK.md#capacity-measurement)
and protocol contain the reproducible settings, commands and acceptance rules.
The resume/DNS guard passed. The probe completed all four phases and restored
reader 200/s and producer 0.5/s, both unpaused; the idle seeder remained 100/s.
No new Maven build was required for this documentation-only change. Step 4.14
verification is reused as historical evidence, not claimed as rerun here.

Helm rendering also passed with the pinned operator chart and current kind values:

```bash
helm template cnpg microservices/shardshop/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz \
  --namespace cnpg-system --values microservices/shardshop/infra/helm/cnpg/values.yaml \
  > /tmp/shardshop-415-cnpg-render.yaml
helm template shardshop microservices/shardshop/infra/helm/shardshop --namespace shardshop \
  --values microservices/shardshop/infra/helm/shardshop/kind-values.yaml \
  --values microservices/shardshop/infra/shards.yaml \
  > /tmp/shardshop-415-infrastructure-render.yaml
helm template shardshop microservices/shardshop/infra/helm/shardshop --namespace shardshop \
  --values microservices/shardshop/infra/helm/shardshop/kind-values.yaml \
  --values microservices/shardshop/infra/shards.yaml \
  --values /tmp/shardshop-415-counting-values.yaml \
  > /tmp/shardshop-415-application-render.yaml
```

The temporary application counting values selected `render: applications` and
schema-valid placeholder image digests (64 zeroes) for all six application
images; nothing was deployed or pulled. The renders match the live counts:
nine application pods, ten PostgreSQL instances, one broker and one operator,
excluding system pods and transient Jobs.

Final documentation validation passed: all 593 local Markdown links resolve;
all original nonblank source ranges have an owning detailed document, and the
eleven acceptance scenario bodies match the source after removing added anchors.
Markdown fences are balanced and `git diff --check` passes. The root architecture
contains only four high-level areas plus navigation; the Mermaid overview was
reviewed for component counts and flow direction.

The task-owned `webpages.sh` received SIGTERM and removed its five tunnels.
`bash microservices/shardshop/scripts/down.sh` succeeded. `docker inspect` reports
all four retained `shardshop` nodes `exited`, and `lsof` confirms no listeners on
ports 18080–18084. The lab is back in its original stopped state.

### Step 4.15 documentation review

**2026-10-23:** a separate review validated the step 4.15 result and then
restructured all 17 architecture documents for readability. No Java, SQL, chart,
deployment default or lab state changed, and the stopped lab was not started.

The review confirmed the step 4.15 claims:

- The raw read evidence gives 86,684 successful reads in 90 reported seconds
  (963.16/s) and the regional rates US 239.74/s, EU 479.66/s and ASIA 243.76/s.
  The order rates match the final step 4.14 table.
- An offline Helm render with schema-valid placeholder digests gives nine
  application pods and 11 database and broker pods: 20 pods in `shardshop`.
- Every sentence of the former root architecture has an owning document. The
  moved role matrix now matches `R__catalog_grants.sql`, which grants
  `catalog_reserver` the profit writes that the former text denied.

The review found and fixed these defects:

- The database layer listed only catalog V1–V2, ordering V1 and ledger V1. A
  schema-version table now lists catalog V1–V6, ordering V1–V7 and ledger V1–V2.
- The order design showed one order process. Its process table and sequence
  diagrams now show the separate HTTP and saga processes of step 4.14.
- The seeder design described a `404` retry of verification reads as current
  behavior. The seeder retries only transport failures and `503`, thus the design
  now marks that retry as planned in step 6.1.
- The core design kept the currency policy in the order producer. `CurrencyPolicy`
  is in common; only the buyer-region currency mix is in the producer.
- Module documents kept the section numbers and "Module 1–4" labels of the former
  single document, skipped heading levels and carried step numbers in headings.
  PLAN section 6 referred to the removed "ARCHITECTURE.md sections 4, 5 and 6".
- The capacity runbook had `curl` commands with collapsed line continuations.

Each module document now starts with a navigation path, a summary, its ownership
and its status. Tables and lists replace long paragraphs for tool ownership, the
lab lifecycle, the deployment pipeline, ports, process roles, ledger tables,
endpoints, the Snowflake layout, the currency mix and scenario coverage. Planned
behavior has a **Status** or **Planned** note. The overview adds a component
table, an order-flow sequence diagram, a cross-shard diagram, a classification of
capacity figures and a map of all 16 detailed documents. Links to changed anchors
in PLAN, README and the module READMEs now use the new anchors.

Results: all 639 local ShardShop Markdown links and anchors resolve; the eleven
scenario bodies are byte-identical to step 4.15; all 13 Mermaid diagrams parse and
render with Mermaid 11.4.1; the fences are balanced and `git diff --check` passes.
The one-off link, sentence-ownership and Mermaid checks are ephemeral helpers and
are not versioned. The Helm renders used the step 4.15 commands above.

### Step 4.15 README and runbook split

**2026-10-23:** each README now contains only how to start the lab or the
application, and which monitors exist. All other README content moved into a
`RUNBOOK.md` beside it, with the owners of the architecture documents:
infrastructure, database, product, order, ledger, core and its libraries,
workloads, the three workload applications, datasets and verification. The moved
text keeps its wording, except that step history left it; this changelog keeps that
history. The three workload applications gained their own README.

The READMEs list these monitors: the five webpages, the workload status API,
product `/q/metrics`, order `/internal/order/write-metrics`, the log lines of each
application, the RabbitMQ management UI, cluster, migration and publication status,
and the test reports. The management UI command (a port forward to
`shardshop-rabbitmq` port 15672, login `shardshop-admin`) is new; it was not run,
because the lab stayed stopped. The repository `AGENTS.md` now tells agents to read
a module's `RUNBOOK.md` as well as its README.

All 720 local ShardShop Markdown links and anchors resolve, including the redirected
links in PLAN, CHANGELOG, VERSIONS and the architecture documents. A sentence
comparison with the former READMEs found no lost requirement: only step history,
former navigation lines, pointers to removed sections and repeated start steps were
removed. No Java, SQL, chart, deployment default or lab state changed.

## Step 5.1

**2026-10-24:** implemented durable ledger reconciliation in the existing order
saga process. HTTP replicas start no reconciliation worker. The implementation
uses the existing schema, grants, immutable order snapshots and permanent replay
identities; it requires no migration or additional pod.

The worker scans bounded pages from each primary and takes the order advisory
lock followed by the saga row lock. It rechecks the order and saga state, due time,
retry budget, quarantine and unpublished commands before creating a replay.
An unpublished command stays with CDC without consuming an attempt. Otherwise
one transaction advances the persisted counter and schedule, reserves new
transport/result IDs and inserts the command. Logical identity and the saved
snapshot remain unchanged. The initial and replay commands share their envelope
writer, including the original currency policy version.

The waits before the five automatic attempts are 60, 120, 240, 300 and 300 seconds.
Restarting workers or removing eligible published transport does not grant a
sixth attempt. Exhausted pending sagas produce an aggregate warning per shard,
repeated on count changes or after five minutes. Stock reservation and release
keep their existing independent retry schedule. Transport cleanup and manual
budget reset remain steps 5.2 and 5.3.

Each keyset pass holds its eligibility cutoff steady so newly overdue higher IDs
cannot indefinitely delay revisiting older sagas. Candidate failures do not block
later candidates or other shards. A failed pass remains unhealthy across page
boundaries; only a subsequent complete clean pass reports recovery. Failure logs
include the affected shard, candidate ID when available and the bounded cause.

The [order runbook](shardshop-order/RUNBOOK.md#durable-reconciliation) documents
configuration, inspection and verification. The overview and module architecture
documents distinguish implemented reconciliation from the remaining recovery work.

### Acceptance evidence

Injected-clock tests cover exact due-time boundaries, the five-attempt budget,
unpublished commands, state changes under locks, quarantine, restart behavior,
exhaustion warnings and scan progress. Persistence tests cover immutable envelope
parity and rollback when an outbox insert fails after the counter and permanent
identity writes. Startup checks keep the worker out of HTTP replicas and reject
invalid reconciliation settings before generator reservation.

Four packaged PostgreSQL/RabbitMQ scenarios exercise unpublished-command reuse,
competing coordinators through all five attempts and restart, and recovery of lost
`RECORDED` and `REJECTED` results. The cleanup fixture waits for both publication
and a durable CDC checkpoint past the insert before deleting published transport.
Permanent identities, the schedule and the budget remain. The lost-rejection
case changes the ledger policy before replay, proving that the stored decision
wins over reevaluation. Assertions cover ledger, stock and seller-profit effects.

### Validation and environment

Commands from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am verify
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@saga-reconciliation'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@order-results or @stock-reservation or @cdc-relay'
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order -am test \
  '-Dtest=ReconciliationCoordinatorTest#newDueHigherIdsDoNotExtendAnActivePassAndStarveAnOlderSaga' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false
python3 /tmp/shardshop-415-doc-links.py
git diff --check
docker ps -a --format '{{.Names}}\t{{.Status}}'
```

- The focused integration run passed all four reconciliation scenarios. The
  broader run passed all 40 selected CDC, ledger-result, reconciliation and stock
  scenarios; the other 95 scenarios were excluded by the tag filter. Its reactor
  also passed 1,813 unit/provider tests and 75 packaged startup checks.
- The scan-progress regression failed before the fixed pass cutoff and passed
  afterward. The broader integration run had already packaged the preceding
  version, so the final order `verify` was run after it completed: all 374 order
  unit/provider tests and 45 packaged startup checks passed, together with the
  selected dependency tests. No database, envelope or transport code changed
  after the broader run was packaged.
- An initial unit run exposed Mockito restubbing that consumed one extra generated
  ID in the test fixture; fixing the fixture preserved the ID-count assertion.
  The first focused-test invocation stopped in a dependency with no selected
  tests; the explicit selection flags above allowed the intended failing and
  passing regression runs. The final full order verification used neither flag.
- Local Markdown link/anchor validation and `git diff --check` passed. The link
  checker is an ephemeral documentation helper, not a Java behavior test or a new
  lifecycle entry point. All 65 milestone IDs remain; only step 5.1 changed status.

No kind deployment or capacity measurement was required for this application
step. Testcontainers removed its disposable containers. The final Docker
inventory matches the initial inventory: all four retained ShardShop nodes and
the unrelated containers remain stopped. The new code has not been deployed to
the retained lab.
