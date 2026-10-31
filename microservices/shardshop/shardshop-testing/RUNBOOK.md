# Verification runbook

[ShardShop](../README.md) › [Verification](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

Run every command from the repository root. Standalone kind suites require the
documented invocation snapshot. Default acceptance observes the existing deployed
lab. Disruptive drills require explicit selection, use the shared drill lock and
register recovery before any mutation. Keep every wait bounded.

## JavaScript page checks

Install Node.js and put `node` on `PATH`. The qualified version is in
[VERSIONS](../VERSIONS.md). The default Maven `test` phase runs
`OrderBrowserScriptTest`, `WorkloadControlScriptTest` and `ProductBrowserScriptTest`.
These execute the shipped JavaScript with deterministic browser stand-ins, including
`shardshop-product/src/test/js/products.test.cjs`. No browser, Docker or running lab
is required. Missing Node.js, a nonzero harness exit or a run longer than 20 seconds
fails the build instead of skipping checks.

Run all verification-module unit tests and their core dependencies:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true test
```

`shardshop.infrastructureOnly=true` omits the application packaging dependencies.
The page harnesses read the scripts in this checkout. For a focused browser run, add
`-Dtest=OrderBrowserScriptTest,WorkloadControlScriptTest,ProductBrowserScriptTest -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false`.
The two no-test flags accommodate upstream core modules without a selected browser
test. Check that all three named wrappers run and pass. A test run of only
`shardshop-product` does not execute its JavaScript harness. Include
`shardshop-testing` to validate product page behavior.

## Integration tests with Testcontainers

The test-only module `shardshop-testing` holds Cucumber scenarios. They run the
packaged product, seeder and reader against a real PostgreSQL and a real Kubernetes
API server, and they check the contracts across modules. Failsafe runs them with
`-Pintegration`. The module has no main classes and installs nothing. Its POM
dependencies on the three applications only make `-am` package the applications
first. The contract checks use `ContractDocument` from the test JAR of
`shardshop-common`. Docker must run, and `helm` must be on the `PATH`, because the
integration profile renders the generator objects of the chart:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

The scenarios take approximately four minutes with the kind lab stopped and
approximately six minutes while the lab runs. Cucumber writes its reports to
`shardshop-testing/target/cucumber-reports`. The application logs stay in
`shardshop-testing/target/scenario-runs`. Run the scenarios only through Maven, which
sets the image digests, the source directory and the path of the rendered chart
objects.

| Item | Test setup |
|---|---|
| PostgreSQL | One container of the pinned `tc.image.postgresql` for the run, bound to `127.0.0.1`. Each scenario creates its own databases and drops them at its end |
| Roles and migrations | The catalog roles, the bootstrap hardening and the schema `catalog` as on the lab. The Flyway Java API applies `database/shard/catalog` as `catalog_migrator`, as the migration Jobs do |
| TLS | `openssl` in the container makes a test CA and a server certificate for `localhost` only. Product connects with its `verify-full` default |
| Kubernetes API | For each generator scenario, one container of the pinned `tc.image.k3s` with `server --disable-agent` (no kubelet, no privileges, API port bound to `127.0.0.1`). Each scenario applies the rendered `generator-allocation.yaml` and creates the registry with the create-then-pin sequence of `cluster-create.sh`. For the host name check, product gets an OpenJDK hosts file (`jdk.net.hosts.file`) that points a name in the `.test` domain to `127.0.0.1`. The k3s certificate does not name it |
| Applications | The packaged `quarkus-app` folders, started as JVMs with an empty environment. Product gets a fixed test generator ID at each start, or reserves an ID in the k3s API server. It gets four shards (`shard-d` is a second US shard) and at most two connections for each endpoint |
| Logs | After each scenario, a hook stops product and fails the scenario if a product JVM logged a WARN or ERROR line. The generator scenarios check their expected startup failures separately: the log has only the fixed message, without the token or API response text |
| Cleanup | Testcontainers runs without its Ryuk reaper, which publishes a port on all host interfaces and runs privileged with the Docker socket. A JVM shutdown hook removes the container. Only a killed test JVM leaves it. Testcontainers logs one `Ryuk has been disabled` WARN line for each run |

| Feature | Cases |
|---|---|
| `ManualReconciliationReset*.feature` | Native `psql` with the restricted login, validation, lock races, bounded rollback, repeatable grants, recovery after five attempts. Tags `@manual-reconciliation-reset`, `@manual-reconciliation-reset-sql` |
| `TransportCleanup*.feature` and the tagged ledger publisher case | Cleanup after the operational window and durable CDC checkpoint, preservation of recent, unpublished, permanent and quarantined data, replay, concurrent rearm, index migrations. Tags `@transport-cleanup`, `@transport-cleanup-migration` |
| `Reconciliation.feature` | Unpublished-command reuse, the durable five-attempt budget, checkpointed cleanup, lost results regenerated from permanent ledger outcomes. Tag `@saga-reconciliation` |
| `CatalogMigration.feature` | Upgrade of legacy rows, country backfill, history validation of a rerun, object owners, no functions or triggers, constraints, profit-credit identities, role privileges, a changed grants file that Flyway reapplies |
| `ProductApi.feature` | Seller and product creation, retries and conflicts, stock and profits, concurrent creation, lost responses, invalid input, 32 KiB bodies, a held lock with a full pool, restarts, unavailable primaries or replicas |
| `ProductTransport.feature` | TLS sessions, an untrusted CA, a host name that the certificate does not name, pool recovery after a PostgreSQL restart |
| `ProductBrowser.feature` | Products across regional shards, keyset pagination, signed per-product earnings, invalid queries, unavailable shards and replicas, packaged browser assets. Tagged `OrderResults.feature` scenarios verify reservation, cancellation and credit updates |
| `ProductSeeder.feature`, `ProductReader.feature` | The cases of the [seeder runbook](../shardshop-workload/shardshop-product-seeder/README.md) and the [reader runbook](../shardshop-workload/shardshop-product-reader/README.md) |
| `Contracts.feature` | What connects the contracts of different modules: the examples agree with each other and follow the service-issued ID rules, and each row of catalog-v2 and buyers-v1 is a valid creation request |
| `GeneratorAllocation.feature` | A reservation at each start and restart, six concurrent starts, service account permissions, the retention policy, and the failures: another registry UID, an exhausted, missing or unreadable registry, a refused PATCH, an untrusted CA, an unnamed host, an API server that does not answer |

The expected bodies come from the product OpenAPI document. An error body has only a
code and a nonempty message. `CatalogApi` also checks that the document defines the
status for the operation and that the body matches the schema of that status. The
scenarios calculate routes, key homes and advisory locks again with the JDK, and they
read the dataset TSV files. On the lab, every `up.sh` invocation runs Flyway through
Helm hooks under the [migration rules](../database/RUNBOOK.md#database-schemas-and-migrations).

## Kind verification snapshot

Standalone kind checks require a snapshot of the rendered inputs from the running
lab. Prepare it once and retain it until all selected checks finish:

```bash
snapshot=$(mktemp -d)
cp -R microservices/shardshop/infra/helm/shardshop "$snapshot/chart"
cp microservices/shardshop/infra/kind.yaml "$snapshot/chart/kind.yaml"
cp "${SHARDSHOP_ENV_VALUES:-microservices/shardshop/infra/helm/shardshop/kind-values.yaml}" "$snapshot/environment.yaml"
cp "${SHARDSHOP_INVENTORY:-microservices/shardshop/infra/shards.yaml}" "$snapshot/inventory.yaml"
probe_nodes=$(kind get nodes --name shardshop)
for mode in inventory kind-configuration infrastructure dns-probes; do
    case "$mode" in
        inventory) output=inventory.json ;;
        kind-configuration) output=kind.json ;;
        infrastructure) output=infrastructure.yaml ;;
        dns-probes) output=dns-probes.yaml ;;
    esac
    helm template shardshop "$snapshot/chart" --namespace shardshop \
        --values "$snapshot/environment.yaml" --values "$snapshot/inventory.yaml" \
        --set-string "render=$mode" --set-literal "probe.nodes=${probe_nodes//$'\n'/,}" \
        --set-literal "probe.run=check-$(openssl rand -hex 6)" |
        if [[ "$output" == *.json ]]; then sed '/^# Source:/d; /^---$/d'; else cat; fi >"$snapshot/$output"
done
```

The required snapshot files are `chart/`, `environment.yaml`, `inventory.yaml`,
`inventory.json`, `kind.json` and `infrastructure.yaml`. `kind-dns` also needs
`dns-probes.yaml`. The inventory is read from the snapshot.

This is the one command form for all kind suites. Other sections give only the tags
and the properties:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=<tag expression>
```

Keep `-Dshardshop.kind.snapshot="$snapshot"` on every standalone invocation. The
suite does not create or reconcile the lab. Omit `shardshop.kind.tags` for the
default suite. The property uses JUnit tag expressions without Gherkin's leading
`@`. A selection that matches no scenarios fails. Add
`-Dshardshop.infrastructureOnly=true` when the sections below name it. Normal builds
and `-Pintegration` do not access the kind lab. Cucumber reports are under
`shardshop-testing/target/kind-reports/`.

The `kind` profile defaults to `kind-deployment`. It combines `kind-topology`,
`kind-workload`, `kind-saga` and `kind-observability`. It needs an already-migrated
and deployed lab with all nine running application pods. The saga scenario creates
two scoped orders for the gate's own buyer. The other scenarios only observe.
Run the default suite separately from the Testcontainers `integration` profile.

Disruptive scenarios require explicit selection. `kind-drill` combines
`kind-database-drill`, `kind-workload-drill`, `kind-saga-drill`, `kind-retry-drill`,
`kind-replica-read-drill` and `kind-failover-drill`. The lifecycle gates are also
selectable:
`'kind-config | kind-nodes | kind-retention | kind-allocator | kind-infrastructure'`
selects those checks together.

- `kind-dns` creates short-lived probe Pods from `dns-probes.yaml` of the snapshot,
  with cleanup on failure. Each probe has a 120-second DNS execution limit and a
  17-minute overall Pod deadline for cold image startup. Every node must resolve
  cluster DNS before provisioning continues.
- `kind-admission-bootstrap` is for fresh initialization only. It requires
  `-Dshardshop.kind.fresh=true`. Do not use it on a retained lab.
- `kind-migration-reservation`, `kind-migrations-idle`, `kind-publications` and
  `kind-drills-idle` are migration and release safety checks. Helmfile runs them
  before migrations or routing/application publication.
- `kind-drills-idle` stops the application release while the `shardshop-kind-drill`
  lock exists. The idle guard rejects active, pending or terminating Flyway Jobs,
  including legacy Jobs.
- Publication observation waits up to 180 seconds for every inventory Publication to
  be applied at its current generation.

The workload credential gate accepts only the documented reader, seeder and producer
HTTP settings with literal typed values. It rejects unknown environment variables,
JVM configuration overrides, command/argument overrides and executable hooks or
probes. Product URLs must contain no user information, query or fragment. Reader
images built before periodic digest reporting need a rebuild and deployment for
verification to survive startup-log rotation.

The normal `kind-deployment` gate requires product primary reads. It checks the
product and routing ConfigMaps, the Deployment template and the ready pods for
replica-profile settings, including environment and JVM property overrides.
Indirect profile/JVM settings and external configuration locations (including
routing-config overrides) fail the check. Both mounted ConfigMaps must contain a
nonempty `application.properties`. Unrelated environment and JVM settings stay
allowed. This validates the declared deployment configuration, not the resolved
configuration of the running JVM. The [strict replica-read drill](#strict-replica-read-drill)
captures the exact prior profile sources, registers restoration before changing them,
and verifies the restored Deployment and ready pods before it releases the shared
drill lock. Helm alone cannot remove a profile environment variable added outside
the release.

All drill families share the `shardshop-kind-drill` ConfigMap lock. Recovery
registers before each temporary mutation and runs in reverse order on failure or
normal completion, with a JVM termination hook for ordinary shutdown. Fence updates
atomically test the observed Cluster UID and resource version. A concurrent change
stops the mutation and does not overwrite the fence of another actor. If recovery
fails, the lock stays and the report names the remaining temporary changes.

After an unclean process termination:

1. Make sure that no drill runs.
2. Examine the report and the lock.
3. Restore the fenced instances and wait for database and product readiness.
4. Remove only the temporary Jobs, SQL objects and slots of the drill.
5. Release the lock.

After a saga drill, also restore two order HTTP replicas and one replica each of the
order saga and order producer Deployments. Remove the drill's
`SHARDSHOP_LEDGER_ALLOWED_CURRENCIES` variable from the ledger Deployment and the
drill's `nodeSelector` from the order saga Deployment. Helm does not remove these
out-of-band additions, thus `up.sh` stops at `kind-drills-idle` until you release the
lock. Never restore an older generator registry or lower `highWaterMark`.

After the last check, remove the local snapshot:

```bash
rm -rf -- "$snapshot"
```

## Retry and DLQ capacity drills

Two complementary suites exist. The first measures the delayed redeliveries
(five), the parking boundary (the sixth rejection) and the total parking time of the
pinned broker in disposable containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pintegration verify \
  -Dcucumber.filter.tags=@broker-retry-timing
```

The second runs the application recovery checks on the existing running lab. Prepare
the [kind snapshot](#kind-verification-snapshot). Use the tag `kind-retry-drill` with
`-Dshardshop.infrastructureOnly=true`.

`kind-retry-recovery` selects the shard-outage case. The drill pauses the producer
through its control API and drains prior work. It stops ledger consumption long
enough to prepare one published command, then fences all three instances of the
shard of the buyer. After ledger resumes, the unavailable shard causes result
delivery to exhaust the broker budget. The drill inspects the exact parked result and
its `delivery_limit` death reason, restores the shard, and waits for the automatic
saga reconciliation to regenerate the saved result. It verifies one ledger entry,
one stock reservation and one profit credit. Then it acknowledges only the inspected
parked diagnostic. It never republishes the parked result.

`kind-dlq-capacity` selects four cases: an unbound or a full DLQ for each of
`ledger.record-order` and `order.ledger-results`. They keep the broker and source
queues running, use temporary exact-queue policies with a small message-count limit,
and inject only marked malformed filler. Retained dead letters fill the source.
Negative publisher confirms block the real order CDC or the ledger result outbox.
After restoration, the same outbox publishes and the scoped order confirms. These are
queue-capacity checks, not a measurement of sustainable throughput. The normal
1,000-message/16 MiB limits stay unchanged after recovery.

- Use `kind-dlq-unbound` or `kind-dlq-full` to select only the two binding cases or
  the two capacity cases.
- Full-DLQ recovery removes the owned parked filler and restores the original parking
  capacity. It keeps the small source limit until the outbox progresses.
- The check preserves the native 180-second internal dead-letter confirmation timer
  of the broker. Individually timestamped messages can straddle its first retry tick.
  Thus full-DLQ outbox recovery allows 450 seconds for two ticks and application
  recovery.

The shared `KindDrillGuard` registers restoration before mutations. It restores
producer rate and pause settings, shard fencing, ledger replicas, bindings and
temporary policies. The producer JVM keeps running. A selected source or DLQ that
contains unrelated work is a failed precondition. Cleanup acknowledges only the owned
filler or the inspected result and never purges a shared queue. Scoped orders and
their business effects remain as evidence. Logs record counts, timings and IDs
without payloads or credentials. If restoration fails, keep `shardshop-kind-drill`,
examine the reported outstanding action, restore that state and verify readiness
before you remove the lock.

## Live deployment check

Prepare the [kind verification snapshot](#kind-verification-snapshot) from the inputs
of this lab. Then run the default deployment suite (no tag selection).

The lab must already run, with migrations and deployment done through `up.sh`, with
two Ready product pods and one running reader. The scenarios observe this deployment
and preserve the reader replicas. They check the deployed routing and database
topology, the workload credential restrictions, the saved IDs and rows for all 151
`catalog-v2` sellers and 201 products, and the resolution digest of the reader. Only
these other rows can exist: the two sellers and two products of the saga gate. On the
retained lab, the complete `catalog-v1` (150 sellers, 300 products) and the first
saga gate seller with its two products can also exist. Any other catalog row fails
the check. Within 120 seconds, the read counters of both Ready product pods must
increase. A fresh reader report interval must contain only `OK` reads at 180/s or
more. Use the tag `kind-workload` for the workload checks alone.

Repeated seeding and product pod replacement are explicit disruptive drills. Use the
tag `kind-workload-drill`. The drill runs repeated temporary seeder Jobs and compares
their digests and the saved seller and product IDs with the existing catalog. It
replaces one product pod. It requires a distinct generator ID that is greater than
the registry `highWaterMark` saved before the replacement and not greater than the
current high-water mark. After the replacement it repeats the two-pod traffic check
and requires an all-OK interval that starts after recovery. Temporary Jobs are
removed and the running reader keeps its replicas. The pod replacement consumes
generator allocations. Cleanup never rewinds the allocator state.

On the lab, the catalog is already complete, thus the reader does not wait there.
`CatalogWaitTest` of the reader module shows the wait: the reader sends no load while
an entity is missing, starts after the entity appears, and stops after the maximum
wait.

## Topology check and database drill

After the catalog and ordering migrations, prepare the
[kind verification snapshot](#kind-verification-snapshot). Run the nondisruptive
topology and routing check on every inventory primary with the tag `kind-topology`.
It verifies, for every shard:

- the writable primary and the streaming quorum standbys;
- the independent database lineage;
- the `-rw` and `-ro` Service endpoints;
- distinct Bound PVCs and backing volumes.

It compares the PostgreSQL SHA-256 arithmetic and the deployed seller and buyer
placement constraints with the shared golden fixtures that Java uses. The counts come
from the inventory of the snapshot. This check creates no SQL objects.

Run the reversible database drill explicitly in this disposable lab. Use the tag
`kind-database-drill`. The drill selects the first inventory shard by default. Select
another shard by inventory name, for example `-Dshardshop.kind.drillShard=shard-b`.
A probe row must reach both standbys, writes on standbys must fail, and the probe
schema must stay absent from peer shards. The drill requires the three instances per
shard of the lab. It checks failover-slot synchronization and WAL retention and
replaces one standby pod while it preserves the PVC. It temporarily fences one
standby, then both, to observe available and blocked quorum commits. Reads and writes
are disrupted during the drill. It restores the standbys and removes its temporary SQL
objects and slots without deletion of a PVC. The shared lock and failure recovery
rules in the [snapshot section](#kind-verification-snapshot) apply.

## Strict replica-read drill

Prepare the [kind verification snapshot](#kind-verification-snapshot). Select the
disruptive drill with the tag `kind-replica-read-drill` and, for example,
`-Dshardshop.kind.drillShard=shard-b`. The tag is also part of `kind-drill`. Without
`shardshop.kind.drillShard`, the drill selects the first inventory shard.

The drill requires the deployed primary-read gate, two ready product pods, the
existing HTTP workloads and three instances per shard. It saves and pauses the
producer, reader and interactive-seeder controls. Then it waits for in-flight work
and the nonterminal backlog of the producer to drain. It checks an acknowledged
product write with an immediate primary read before it changes profiles.

Before any profile mutation, it validates both product/routing ConfigMaps, the
Deployment template and the ready product pods with the primary-read gate. It
captures the exact ConfigMap data and the full product pod template, including
literal profile environment settings and JVM `-D` overrides in option variables,
commands and arguments. It registers recovery before it converts every applicable
source to `replica`. Indirect or external configuration fails the gate. JSON patches
test the resource UID, the resource version and the saved field, thus concurrent edits
prevent an overwrite. The snapshot evidence stores identities and hashes. Private
temporary patch files keep environment values out of command arguments.

The drill checks these behaviors:

- After a completed profile rollout, the reader runs at 200 requests/s. It must
  produce a fresh complete all-OK interval of at least 180/s with traffic on both
  product pods.
- Paused replay on both selected standbys gives a committed primary write that replica
  reads return as `404 PRODUCT_NOT_FOUND`. After replay resumes, the write must be
  readable.
- Fencing one standby must preserve reads and writes.
- Fencing both standbys must yield `503 READ_REPLICA_UNAVAILABLE` on each product pod
  in less than four seconds, before the five-second client deadline, while peer-shard
  reads succeed.
- A separate bounded `ACCESS EXCLUSIVE` lock on `catalog.products`, replayed onto both
  standbys, checks the same response contract for hung queries.
- During each outage, a fresh reader interval must contain both successful peer reads
  and replica unavailability, with no deadline, transport or other unexpected
  outcomes.
- The restored standbys must resume reads, followed by another clean 200/s reader
  observation.

Recovery removes the query block, restores replay and fencing and the exact original
profile, and waits for the Deployment and for the primary configuration of every ready
product pod. It removes only the product fixtures that the invocation owns, including
creations with a lost response. Then it restores the saved workload controls. The
final `kind-deployment` primary gate must pass before the shared lock is released.
A failed recovery keeps the lock under the
[shared recovery rules](#kind-verification-snapshot).

## Coordinated backup and isolated restore

The tags `kind-backup` and `kind-restore` are excluded from the default kind suite and
from `kind-drill`. Prepare the [kind snapshot](#kind-verification-snapshot), a running
source lab, Docker, OpenSSL and the packaged order and ledger outputs that the
target-only CDC verification needs. Generate the broker fixture of the integration
profile before the restore. The preflight refuses its absence before the target
changes:

```bash
helm template shardshop microservices/shardshop/infra/helm/shardshop \
  --namespace shardshop \
  --values microservices/shardshop/infra/helm/shardshop/kind-values.yaml \
  --values microservices/shardshop/infra/shards.yaml \
  --set-string render=infrastructure \
  --output-dir microservices/shardshop/shardshop-testing/target/chart
```

Set `shardshop.kind.cluster` to the name of the source cluster that matches the
snapshot. Choose absolute normalized paths outside the repository and every Maven
`target` directory:

- `shardshop.backup.directory`: a new exclusive backup directory. Do not precreate it.
- `shardshop.backup.allocator`: a separate latest-history file under an existing
  private parent, outside the backup directory. Preserve it independently across
  backups. The restore checks its monotonic high-water mark and allocator lineage.
- `shardshop.restore.directory`: a new exclusive private evidence directory, separate
  from the backup. Do not precreate it.
- `shardshop.restore.target`: a fresh Docker name that matches `shardshop-restore-`
  plus a DNS label, at most 63 characters in total. Existing containers, including
  stopped containers, are refused.

Use a mode `700` host parent and mode `600` artifact and allocator files. The bundle
contains the full business data and exported broker credential hashes. Protect it and
never commit it. Keep it outside kind and Maven cleanup. Assign `$backup`,
`$allocator`, `$restore` and `$restoreTarget`, then run the
[kind command form](#kind-verification-snapshot) with
`-Dshardshop.infrastructureOnly=true` and `-Dshardshop.kind.cluster=shardshop`:

| Tag | Additional properties |
|---|---|
| `kind-backup` | `-Dshardshop.backup.directory="$backup" -Dshardshop.backup.allocator="$allocator"` |
| `kind-restore` | The two backup properties, `-Dshardshop.restore.directory="$restore" -Dshardshop.restore.target="$restoreTarget"` |

The backup acquires the host lifecycle lock and the shared drill lock, pauses all
three workload controls and saves the deployment replicas. It stops the writers,
drains the saga and ledger work and every queue, including the DLQ parking queues,
then stops the remaining consumers. Nonempty dead letters refuse the capture, because
the baseline must have empty queues. It records four full dumps, the RabbitMQ
definitions, roles and ACLs, routing, region and schema versions, the lineage, the
allocator history and table fingerprints. It restores the source deployment, controls,
topology and identities before it seals the bundle. A recovery failure keeps the lock.

The restore verifies compatibility, integrity and the allocator history before it
changes the target. It never contacts the source lab. A new owned, loopback-only
PostgreSQL container holds four isolated databases with TLS and the restored roles
and ACLs. Row and schema fingerprints and the cross-shard checks must match. A
logical dump has the saved CDC offsets but no replication slot, thus the packaged CDC
must first refuse startup. Explicit target-only recovery resets that metadata and
resnapshots the retained commands to a fresh broker and ledger worker. Then it proves
streaming replay of the same command and result IDs without added ledger effects. No
traffic that emits IDs runs, and the source allocator is never rewritten. The restore
removes the target, and the private evidence stays separate from the bundle. This
workflow does not qualify point-in-time recovery or a production cutover.

After the final source gates, refresh the independent allocator history before you
stop the lab. Use the tag `kind-backup-allocator` with
`-Dshardshop.infrastructureOnly=true`, `-Dshardshop.kind.cluster=shardshop` and
`-Dshardshop.backup.allocator="$allocator"`. It needs no backup or restore directory.
It holds the host lifecycle lock and verifies deployment and saga readiness first. Do
not restart the source generators after this capture. If a restart occurs, refresh the
history before any restore use that produces IDs. This export is opt-in and excluded
from `kind-drill`.

Focused implementation checks (no live lab, no qualification of a live restore):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true \
  '-Dtest=Backup*Test,KindBackupRestoreTest,OwnedRestoreTargetTest,Restore*Test,KindDrillGuardTest,KindFailoverDrillTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false test
```

## Older-backup audit and selected repair

All five `kind-recovery-*` tags are opt-in and excluded from `kind-drill`. Use the
[backup bundle and private-artifact rules](#coordinated-backup-and-isolated-restore),
the latest independent allocator file and the invocation snapshot. The replay probes
of the drill need the packaged applications and the generated broker fixture.

Every operation creates a fresh owned isolated target, reads only the supplied backup
files and snapshot metadata, and makes no source database connection or write. Choose
a new mode-700 private output directory outside Maven `target`, separate from all
input bundles, the allocator file and the original record. Do not precreate the
output. Its existing parent must be private. Use a fresh `shardshop-restore-*` target
name.

Run the [kind command form](#kind-verification-snapshot) with
`-Dshardshop.infrastructureOnly=true`, one recovery tag below, and these common
properties:

```text
-Dshardshop.backup.directory="$backup" -Dshardshop.backup.allocator="$allocator"
-Dshardshop.restore.target="$restoreTarget" -Dshardshop.recovery.directory="$recovery"
```

- `kind-recovery-schema`: the common properties suffice. This focused native
  regression restores one captured ordering schema twice. It checks the owner and
  grants, the publication membership and all four original database fingerprints. It
  runs no application or broker processes.
- `kind-recovery-record`: also pass `-Dshardshop.recovery.order-id="$orderId"`. It
  exports the verified original rows to `$recovery/original-record` before it
  disposes of the target.
- `kind-recovery-audit`: also pass `-Dshardshop.recovery.ledger-backup="$ledgerBackup"`.
  This verified bundle must be the same age as the ordering bundle or later. Its
  ledger replaces the older ledger. Then the permanent outcomes are exported and
  audited against orders, fingerprints and stock reservations.
- `kind-recovery-repair`: pass the ledger-backup option plus
  `-Dshardshop.recovery.record="$originalRecord"` and
  `-Dshardshop.recovery.order-id="$orderId"`. Select exactly one canonical positive ID
  and its verified original-record directory. Foreign or conflicting records, missing
  original history or costs and unproved catalog effects stay unresolved. Pending
  authentic outcomes apply only when the retained catalog effects agree.
- `kind-recovery-drill`: the common properties suffice. It creates a causal older dump
  from three acknowledged witnesses and proves that later state existed. Then it
  restores the older dump, which loses two orders and mismatches the third
  fingerprint. The audit precedes late delivery. The drill checks quarantine, orphan
  reservations, late acknowledgement, HTTP `409`, refusal of conflicting or missing
  records and the recovery of one original order. Replay keeps the command and result
  IDs, the ledger cardinality and all catalog table fingerprints unchanged. Other
  incidents stay unresolved.

Audit and repair keep the full four-database review dumps, the reports and the
quarantine and repair state before they delete their owned target. The sealed review
bundle has `purpose: recovery-review-only` and `trafficAuthorized: false`. Protect its
complete data and credential material. Never commit it and never use it to authorize
a production cutover. No ordinary application traffic reaches the frozen target. Only
explicit isolated replay probes run.

## Synchronous failover drill

This drill is disruptive and opt-in, including through `kind-drill`. Prepare the
[kind verification snapshot](#kind-verification-snapshot) against the deployed
retained lab. Use the tag `kind-failover-drill` with
`-Dshardshop.infrastructureOnly=true` and, for example,
`-Dshardshop.kind.drillShard=shard-b`. Without `shardshop.kind.drillShard`, the drill
selects the first inventory shard. It requires the pinned CNPG operator image, three
instances on the selected shard, the failover quorum and the normal primary-profile
deployment and ledger policy.

`KindFailoverDrill` acquires the shared lock, saves and pauses the producer, reader
and interactive-seeder controls, and drains the in-flight work and the nonterminal
backlog. `KindFailoverWitness` creates a buyer on the selected shard and orders
through the HTTP API against a seeded peer-shard product. It keeps a confirmed anchor
and the original pending command through a planned switchover and a primary-failure
promotion. With each standby absent in turn, a ten-second observation must show CDC
blocked while a real HTTP order commits through the surviving synchronous quorum.
After recovery, the original order, saga, command and result IDs, the order data and
the outbox envelope must survive and complete exactly one business effect. The
synchronized logical slot must cover the saved durable checkpoint, the reader history
and offset partition must stay unchanged, and the streaming position must advance.

`KindFailoverCluster` uses the switchover status contract of CNPG. A primary failure
uses a pod deletion guarded by UID and resource version, with a finalizer that the
invocation owns. The drill checks for at most one writable primary and for the safe
rejoin of the former primary. In the unsafe-survivor phase, it fences one standby
before a synchronous witness commit, then holds the aligned standby and the primary
deleted. It verifies that the survivor lacks the acknowledged witness. For 180 seconds
plus the failover delay (at most 60 seconds), the survivor must stay read-only, must
never be nominated and must have no ready writable-service endpoint.

After a promotion, the drill reconciles the logical slots on the former primaries
before it declares the topology healthy or switches back. It retires a captured slot
only when all of these conditions are true:

- The authoritative server is the stable primary, and the former primary is a
  read-only standby with the original database lineage.
- The slot name, database and plugin match the captured identity.
- The local confirmed LSN is between the captured and the current authoritative
  positions.
- The slot is inactive, valid, permanent, a failover slot, and not synchronized.

Unknown logical slots cause a refusal. A conditional SQL statement checks the recovery
role, the slot identity, the flags and the exact LSN again at retirement. The slot of
the authoritative primary stays, and the native synchronization must restore the
synchronized copies on the demoted server.

An owned `failover_slot_*` logical slot, publication and probe schema exercise this
branch independently of the business slot, which the drill never consumes. If a probe
copy is missing, temporary or has a null confirmed LSN during resynchronization, the
drill primes only the probe, after it checks that both physical slots are active and
match `synchronized_standby_slots`. The verbose probe decode retries at most three
times, only for the SQLSTATE `55000` missing-`active_pid` warning that names an
expected physical slot. A final warning-free decode is mandatory. Other warnings and
nonzero exits are terminal. The decode has a twenty-second bound. Recovery drops only
the probe slot, waits for its absence on all three instances, and removes its
publication, schema and captured entry before the final switchback.

Fence updates use at most three conditional attempts within a forty-second bound.
Every reread checks the original cluster UID and that the fence is original or owned
by the invocation. The patch tests the original UID and the exact observed fence value.
Unrelated annotations stay. If the target state is already observed, the update accepts
a possibly lost successful response. A retry is allowed only when the observed
resource version changed. Unchanged command failures, foreign fence changes and
cluster replacement stop the operation.

The HTTP witness retries on the guard thread, with a separate 180-second budget for
the allocation POST and for the order PUT, and a 60-second bound for the committed
outbox. It keeps the allocation run and ordinal fixed and replays the same order PUT
ID and body. A retry is limited to `503` and transient transport failures. Initial
success requires allocation `201` and PUT `202`. A `200` replay is allowed only after
an uncertain request. Control restoration opens a fresh tunnel for each attempt and
reads back the saved rate and paused state, with at most three attempts within one
180-second budget. Only `503` and transient transport failures permit a retry.

Recovery removes only the finalizers of this invocation. It restores the original
fence state, the original primary and the saved controls. Before it releases the lock,
it checks the database lineage, instance membership, PVC and backing-volume
identities, streaming roles, deployment readiness and saga invariants. It deletes no
PVCs or PVs and removes the probe schema. The HTTP fixture buyers, allocations and
orders stay. A recovery failure keeps the lock under the
[shared recovery rules](#kind-verification-snapshot).

## Saga gate and drill

The producer owns catalog gating and buyer creation in Java. The default
`kind-deployment` selection includes `kind-saga`. This gate does not examine producer
orders. Through local port forwards to the product and order Services, it creates
valid US/USD and EU/EUR seller and product fixtures and its own buyer. The order shard
differs from each product shard. Each product starts with 1,000,000 units. The fixed
`kind-saga-gate-v2.*` creation keys make later runs recover the same IDs. The gate
first checks that the deployed ledger allowlist is every supported currency. Each run
submits one USD order and one EUR order, and the normal policy must confirm both. The
gate checks the ledger decisions, stock reservations, seller profit, HTTP status and
the stock and profit invariants. Each run uses one unit of each product and keeps its
two orders as evidence. The gate does not stop the producer or change the ledger.

Prepare the [verification snapshot](#kind-verification-snapshot). Run the saga drill
with the tag `kind-saga-drill`. It covers duplicate delivery, permanent rejection
replay after transport cleanup with a temporary EUR allowlist, two orders for the same
stock, CDC resumption after the saga moves to another worker, an HTTP rollout that
preserves the saga pod and the CDC active PIDs, and a saga restart while writes
continue.

The drill scenarios restore the temporary deployment settings afterward. The
business-invariant scenario pauses the producer. The rollout and restart scenarios
keep writes running. One saga replica owns bounded stock scans across buyer shards,
and the scan of each buyer shard stays sequential. The retained lab has one catalog
shard per region, thus its two-item stock-failure check verifies atomic rollback
within that shard: an oversized second item leaves both stocks unchanged, with no
reservation rows or profit credits. The PostgreSQL scenarios use two US shards for
cross-shard partial-reservation compensation and competing stock coordinators. The
drill keeps the business and allocator history, and new order JVM starts permanently
consume generator IDs. Do not run the kind suite and the Testcontainers suite against
shared build outputs at the same time.

## Crash recovery and observability

Disposable PostgreSQL and RabbitMQ containers give the exact process-crash
boundaries. Rebuild the changed packaged applications, then select the recovery suite
from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-ledger -am -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pintegration verify \
  '-Dcucumber.filter.tags=@crash-recovery or @cdc-relay or @order-processes'
```

The test JDK must include `jdk.jdi`. A test-only JDWP listener binds a random loopback
port in a packaged JVM. The harness installs a source boundary that it resolves from a
unique sequence of complete statements before startup resumes. It suspends the JVM
there, checks independent SQL and broker evidence, and kills it. Application images
and production settings have no debugger or crash switches. The barriers validate
unique statement anchors, methods and executable bytecode locations. Harmless line
shifts need no test edit. Missing or ambiguous statements fail before attachment, and
changed statements require an intentional anchor review.

These cases distinguish an uncommitted ledger decision from a committed decision whose
delivery is still unacknowledged. They also distinguish a broker-confirmed publication
from its database mark and durable CDC checkpoint. Recovery keeps the command and
result identity and the permanent outcomes. The integrated command and result cases
also commit new orders through the independent HTTP JVM while the consumer JVM is
suspended. The CDC cases cover each shard, snapshot handoff, rollback, metadata
changes, mandatory returns, connection loss, exclusive ownership and missing or
invalid durable state. Slot, offset and WAL destruction happens only in these
disposable databases. Promotion and synchronized-slot failover use the separate
[synchronous failover drill](#synchronous-failover-drill).

After deployment with `scripts/up.sh`, prepare the [kind snapshot](#kind-verification-snapshot).
Run the tag expression `'kind-saga-drill | kind-observability'` with
`-Dshardshop.infrastructureOnly=true`.

The HTTP rollout drill submits scoped orders at up to one per second through the
Kubernetes Service proxy, with a five-minute bound. A pod-bound port forward cannot
follow a replaced HTTP pod. The drill verifies commits before, during and after the
replacement, the unchanged saga pod UID and active slot PIDs, and complete stock,
ledger and profit effects for every accepted order. It restores the rollout annotation
through the drill guard. No new workload pod is necessary. `kind-observability` is
also part of the normal deployment gate. The [monitoring guide](../infra/README.md#monitoring)
names the endpoints and signals.

## Acceptance run on a disposable lab

This procedure verifies the [README](../README.md) and the owning runbooks on one
fresh owned lab. The retained lab keeps its data. Obey the
[disposable test clusters](../infra/RUNBOOK.md#disposable-test-clusters) rules: stop
the retained lab and record its state, make sure that at least 20 GiB of the Docker
disk is free, and register the cleanup before creation. Set `SHARDSHOP_TEST_CLUSTER`
and `SHARDSHOP_TEST_KUBECONFIG` for every call.

1. Run the [Testcontainers suite](#integration-tests-with-testcontainers) first, while
   no lab runs.
2. Run `cluster-create.sh`. On a new cluster it also runs `kind-admission-bootstrap`
   with `shardshop.kind.fresh=true`.
3. Run `up.sh`. It runs the unit, provider and startup tests, the image scan gates,
   the migrations and the release gates.
4. Run `webpages.sh`. Make sure that it prints the five URLs and that each page and
   workload control API answers. Then press Ctrl+C: all tunnels close, and the pods
   continue to run.
5. Prepare the [snapshot](#kind-verification-snapshot). Use the owned name in the
   copied `kind.yaml` and the owned nodes as DNS probe nodes.
6. Run each row of the table below in order. `up.sh` installed all modules, thus use
   the [kind command form](#kind-verification-snapshot) without `-am`, with
   `-Dshardshop.infrastructureOnly=true`, with the goals
   `test-compile failsafe:integration-test failsafe:verify` in place of `verify`,
   and with these properties for each tag: `-Dshardshop.kind.cluster="$SHARDSHOP_TEST_CLUSTER"`
   and `-Dshardshop.kind.kubeconfig="$SHARDSHOP_TEST_KUBECONFIG"`.

| Row | Tags and properties |
|---|---|
| Lifecycle gates | `kind-config \| kind-nodes \| kind-retention \| kind-allocator \| kind-infrastructure \| kind-dns \| kind-migration-reservation \| kind-migrations-idle \| kind-publications \| kind-drills-idle` |
| Backup and restore | Render the broker fixture, then run `kind-backup` and `kind-restore` with the [backup properties](#coordinated-backup-and-isolated-restore) |
| Older-backup recovery | `kind-recovery-schema`, then `kind-recovery-drill`, each with a new restore target and a new output directory |
| Drills | `kind-database-drill`, `kind-workload-drill`, `kind-saga-drill`, `kind-retry-drill`, `kind-replica-read-drill` and `kind-failover-drill`. Add `-Dshardshop.kind.drillShard=shard-b` to the database, replica-read and failover drills |
| Stop and resume | `down.sh`, then `up.sh`, which runs all release gates again |
| Final gates | `kind-deployment \| kind-config \| kind-nodes \| kind-retention \| kind-allocator \| kind-infrastructure \| kind-drills-idle`, then `kind-backup-allocator` |

7. Run `cluster-destroy.sh` for the cleanup. Make sure that no owned node, container or
   kubeconfig context remains. Then start the retained lab again with
   `cluster-create.sh`, which reconciles changed infrastructure images, and `up.sh`.

The sequence takes approximately 80 minutes from creation to cleanup. The retry drill
takes approximately 20 minutes, the failover drill approximately 12 minutes and each
`up.sh` 10 to 13 minutes.

If a step fails, keep the lab and examine it before the cleanup. A failed drill can
keep the `shardshop-kind-drill` lock, and later drills then stop.

## Capacity measurement

Read the [measurement protocol](ARCHITECTURE.md#capacity-measurement) first. Use an
already prepared lab. Follow the [lifecycle runbook](../infra/RUNBOOK.md#stop-and-resume-the-cluster)
to resume it if necessary, and record its prior running state. Reuse the deployed lab
without a rebuild of images or new migrations.

Run the control tunnels in a separate terminal:

```bash
bash microservices/shardshop/scripts/webpages.sh
```

Save the original controls before you change them, pause the producer, and poll its
status for at most 60 seconds until `inFlight` and `producer.nonTerminalBacklog` are
both zero. A nonzero backlog or a failed workload makes the phase invalid. Leave the
interactive seeder idle. Set `$capacity` to a private directory for the raw reports.

```bash
curl --fail --silent http://127.0.0.1:18082/api/v1/order-producer/control \
  > "$capacity/producer-before.json"
curl --fail --silent http://127.0.0.1:18083/api/v1/product-reader/control \
  > "$capacity/reader-before.json"
curl --fail --silent -X POST http://127.0.0.1:18082/api/v1/order-producer/control/pause
curl --fail --silent http://127.0.0.1:18082/api/v1/order-producer/control
```

Apply each target of the protocol, record the UTC time of the update, and poll the
reader logs every ten seconds for the three eligible reports. Stop each phase within
150 seconds. For example, the first target is:

```bash
curl --fail --silent -X PUT -H 'Content-Type: application/json' \
  -d '{"rate":500}' http://127.0.0.1:18083/api/v1/product-reader/control/rate
curl --fail --silent -X POST http://127.0.0.1:18083/api/v1/product-reader/control/resume
date -u '+%Y-%m-%dT%H:%M:%SZ'
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-product-reader \
  --timestamps --tail=60
```

Record the [pod inventory](../infra/README.md#pod-inventory), the hardware and the
control status with each phase. The `Product reads, interval` lines of the reader give
the requests, duration, outcomes, latency, regions and pool snapshots. Keep the raw
reports. For write measurements, use the
[order-capacity runbook](../shardshop-workload/shardshop-order-producer/README.md)
and the stricter backlog criteria of the protocol.

For writes, save monotonic timestamps around each producer control request. Check
both the elapsed observation time and the post-warmup latency-sample count as the
protocol specifies. Capture `/internal/order/write-metrics` from **each** order HTTP
pod together with `/q/metrics`. The former gives separate allocation and PUT p95
windows. The request sums and counts of the latter give averages. Do not treat those
per-pod windows as interval percentiles. Do not add their p95 values to reconstruct
the allocation-through-PUT p95 of the producer.

Always restore `targetRate` with the same `/control/rate` endpoint. Restore `paused`
with `/control/pause` or `/control/resume` from **each saved control**, also after
errors. Confirm the status with GET. Stop the `webpages.sh` process of the task with
Ctrl+C (SIGTERM also cleans up its tunnels). If the lab was initially stopped, finish
with:

```bash
bash microservices/shardshop/scripts/down.sh
```

Verify that all four retained kind nodes are stopped and that the tunnel ports of the
task have no listeners. Do not replace saved settings with defaults when you
reproduce the measurement in a lab with a different configuration.
