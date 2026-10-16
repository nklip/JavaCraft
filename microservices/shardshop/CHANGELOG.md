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
[lifecycle and launch runbook](README.md#generator-allocation-at-jvm-startup-steps-34-and-311).

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

**Commands:** [deployment runbook](README.md#product-deployment-and-seeding-gate-step-39).

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

**Commands:** [integration tests](README.md#integration-tests-with-testcontainers-step-310).

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
[generator runbook](README.md#generator-allocation-at-jvm-startup-steps-34-and-311).

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
[contract checks](README.md#contract-checks-step-312).

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
[runbook](README.md#buyer-and-order-acceptance-step-41) shows. The order runtime
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
