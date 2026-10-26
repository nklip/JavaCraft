# Verification runbook

[ShardShop](../README.md) › [Verification](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

Run every command from the repository root. Standalone kind suites require the
documented invocation snapshot. Default acceptance observes the existing deployed
lab; disruptive drills require explicit selection, use the shared drill lock and
register recovery before any mutation. Keep every wait bounded and do not claim
complete coverage for a planned scenario.

## JavaScript page checks

Install Node.js and put `node` on `PATH`; use the qualified version recorded in
[VERSIONS](../VERSIONS.md). The default Maven `test` phase runs
`OrderBrowserScriptTest`, `WorkloadControlScriptTest` and `ProductBrowserScriptTest`.
These execute the shipped JavaScript with deterministic browser stand-ins, including
`shardshop-product/src/test/js/products.test.cjs`. No browser, Docker or running lab
is required. Missing Node.js, a nonzero harness exit or a run exceeding 20 seconds
fails the build instead of skipping checks.

Run all verification-module unit tests and their core dependencies:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true test
```

`shardshop.infrastructureOnly=true` omits the application packaging dependencies;
the page harnesses read the scripts in this checkout. For a focused browser run:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true -Dtest=OrderBrowserScriptTest,WorkloadControlScriptTest,ProductBrowserScriptTest -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false test
```

The two no-test flags accommodate upstream core modules without a selected browser
test. Check that all three named wrappers run and pass. Testing only
`shardshop-product` does not execute its JavaScript harness; include
`shardshop-testing` when validating product page behavior.

## Retained foundation upgrade witness

The original 6.0 rehearsal used this procedure successfully. Its result is retained;
repeating foundation/CNPG work is outside the reduced 6.0 scope. For a separately
authorized future upgrade, use an owned `shardshop-test-*` cluster and private
kubeconfig under the [isolated-lab procedure](../infra/RUNBOOK.md#disposable-test-clusters).
Initialize it from the pre-5.3 revision, with real terminal orders and ledger
decisions, before applying current infrastructure. A newly created current lab
cannot satisfy this check: the before observer rejects existing operator roles.
Keep workloads paused and let outstanding business work settle before capturing
the baseline. Register owned-cluster cleanup before creation and preserve the
retained lab's prior state.

Build the current observer, then run these tags with the usual invocation
snapshot and the explicit owned-cluster properties:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing \
  -Dshardshop.infrastructureOnly=true -Pkind \
  test-compile failsafe:integration-test failsafe:verify \
  -Dshardshop.kind.snapshot="$snapshot" \
  -Dshardshop.kind.cluster="$SHARDSHOP_TEST_CLUSTER" \
  -Dshardshop.kind.kubeconfig="$SHARDSHOP_TEST_KUBECONFIG" \
  -Dshardshop.kind.upgrade.evidence="$evidence/before.json" \
  -Dshardshop.kind.tags=kind-upgrade-before
```

The evidence file is created exclusively with mode 600. It contains hashes of
retained rows and credentials, resource/database identities, routing and allocator
metadata; it never stores raw credentials or row payloads. Current `cluster-create.sh`
then `up.sh` must preserve that evidence. Re-run the observer with
`kind-upgrade-after` before the manual reset; it checks existing identities and
data, monotonic allocator state, exact restricted operator grants, and stock/profit
invariants. New acceptance orders are permitted; removing or changing original
permanent rows fails the check.

For the separate reset/replay proof, prepare one owned pending order with a
recorded ledger outcome and deliberately lost result. Supply its IDs to the
before tag using `shardshop.kind.upgrade.order`, `.saga` and `.home` (the latter
is an inventory shard, not a public API selector). The observer requires exactly
one original result binding and an exhausted five-attempt budget. After upgrade,
execute the [native operator reset](../shardshop-order/RUNBOOK.md#manual-reconciliation-reset)
and select `kind-upgrade-replay` with the same evidence file. It requires one replay,
two total command/result bindings and unchanged permanent ledger/stock/profit
effects. This live upgrade fixture does not replace the packaged tests that
exercise real retry-budget exhaustion and transport cleanup.

## Integration tests with Testcontainers

The test-only module `shardshop-testing` holds Cucumber scenarios that run the
packaged product, seeder and reader against a real PostgreSQL and a real Kubernetes
API server, and that check the contracts across modules. Failsafe runs them with
`-Pintegration`, as the `*-testing` modules of the other projects in this repository
do. The module has no main classes and installs nothing. Its POM dependencies on the
three applications have no transitive dependencies; they only make `-am` package the
applications first. The contract checks use `ContractDocument` from the test JAR of
`shardshop-common`, not application classes. Docker must run,
and `helm` must be on the `PATH` (tested with 4.3.0), because the integration
profile renders the generator objects of the chart:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```

On 2026-10-13, the 42 scenarios took approximately four minutes with the
kind lab stopped. While the lab runs, they take approximately six minutes. Cucumber
writes its
reports to `shardshop-testing/target/cucumber-reports`, and the application logs
stay in `shardshop-testing/target/scenario-runs`. Run them only through Maven,
which sets the image digests, the source directory and the path of the rendered chart
objects.

| Item | Test setup |
|---|---|
| PostgreSQL | One container of the pinned `tc.image.postgresql` (18.6) for the run, with its port bound to `127.0.0.1`. Each scenario creates its own databases and drops them at its end |
| Roles and databases | The catalog roles and memberships of the CNPG `DatabaseRole`s, the bootstrap hardening, and the schema `catalog` of `catalog_owner`, as on the lab |
| Migrations | The Flyway **13.8.1** Java API applies `database/shard/catalog` as `catalog_migrator` with the settings of its `flyway.toml` and the inventory placeholders, as the migration Jobs do |
| TLS | `openssl` in the container makes a test CA and a server certificate for `localhost` only. Product connects with its `verify-full` default |
| Kubernetes API | For each generator scenario, one container of the pinned `tc.image.k3s` (k3s v1.36.5) with `server --disable-agent`: no kubelet, no privileges, and the API port bound to `127.0.0.1`. The `pre-integration-test` phase renders the chart with `helm template` and the shared chart, kind environment values and ordered inventory. Each scenario applies the rendered `generator-allocation.yaml` and creates the registry with the same fresh-cluster create-then-pin sequence used by `cluster-create.sh`. For the host name check, product gets an OpenJDK hosts file (`jdk.net.hosts.file`) that points a name in the `.test` domain to `127.0.0.1`. The k3s certificate does not name it |
| Applications | The packaged `quarkus-app` folders, started as JVMs with an empty environment. Product gets a new fixed test generator ID at each start, or in the generator scenarios it reserves an ID in the k3s API server with a service account token. It gets four shards (`shard-d` is a second US shard) and at most two connections for each endpoint |
| Logs | After each scenario, a hook stops product and fails the scenario if a product JVM logged a WARN or ERROR line. This also covers the invalid requests, deadlines and outages that the scenarios cause. The generator scenarios check their expected startup failures separately: the log has only the fixed message, without the token or API response text |
| Cleanup | Testcontainers runs without its Ryuk reaper, which publishes a port on all host interfaces and runs privileged with the Docker socket. A JVM shutdown hook removes the container; only a killed test JVM leaves it. Testcontainers logs one `Ryuk has been disabled` WARN line for each run |

| Feature | Replaces | Cases |
|---|---|---|
| `ManualReconciliationReset.feature`, `ManualReconciliationResetSql.feature` | Step 5.3 operator reset acceptance | Native `psql` with the restricted login, pair/state validation, advisory and row-lock races, bounded rollback, repeatable grants, and recorded/rejected recovery after five attempts, cleanup and restart; select `@manual-reconciliation-reset or @manual-reconciliation-reset-sql` |
| `TransportCleanup.feature`, `TransportCleanupMigration.feature` and the tagged ledger publisher case | Step 5.2 retention acceptance | Packaged cleanup after the operational window and durable CDC checkpoint, preservation of recent/unpublished/permanent/quarantined data, exact recorded/rejected replay, concurrent rearm, and retained-data index migrations; select `@transport-cleanup or @transport-cleanup-migration` |
| `Reconciliation.feature` | Step 5.1 recovery acceptance | Unpublished-command reuse, the durable five-attempt budget under competing scanners and restarts, checkpointed transport cleanup, and lost recorded/rejected results regenerated from permanent ledger outcomes; select `@saga-reconciliation` |
| `CatalogMigration.feature` | `verify-catalog-migration.py`, `verify-catalog-lab.sh` | V1 through V6 upgrade of legacy rows, provenance-based country backfill and legacy reporting, history validation of a rerun, object owners, no functions or triggers, creation-key and product constraints, profit-credit identities and lookup index, the privileges of each role and login, and a changed grants file that Flyway reapplies and that removes stale privileges |
| `ProductApi.feature` | `verify-product-provider.py` | Seller and product creation, retries and conflicts on the shard of the key or seller, current stock and profits, integer forms, concurrent creation, lost responses, invalid input over the real HTTP stack, 32 KiB bodies, an incomplete body, a held lock with a full pool, restarts, and unavailable primaries or replicas |
| `ProductTransport.feature` | `verify-product-transport.py` | TLS sessions, an untrusted CA, a host name that the certificate does not name, and pool recovery after a PostgreSQL restart without a product restart |
| `ProductBrowser.feature` | Product page acceptance | Arbitrary products across regional shards, keyset pagination, exact signed per-product earnings, invalid queries, unavailable shards and replicas, and packaged browser assets; tagged `OrderResults.feature` scenarios verify reservation, cancellation and confirmed-credit updates |
| `ProductSeeder.feature` | `verify-product-seeder.py` | The [seeder runbook](../shardshop-workload/shardshop-product-seeder/RUNBOOK.md) cases |
| `ProductReader.feature` | `verify-product-reader.py` | The [reader runbook](../shardshop-workload/shardshop-product-reader/RUNBOOK.md) cases |
| `Contracts.feature` | `verify-contracts.py` and `verify-datasets.py`, for what connects the contracts of different modules | The RecordOrder example buys the product of the product creation example; both ledger result examples answer the RecordOrder example with its reserved result message ID, amount and currency, after its time; the illustrative IDs of all examples follow the service-issued ID rules; each row of catalog-v2 and buyers-v1 is a valid creation request of product or order |
| `GeneratorAllocation.feature` | `verify-generator-allocation.sh` | A reservation at each start and restart, the generator ID of new sellers, six concurrent starts, the permissions of the product, order, ledger, workload and default service accounts, the retention policy, and the failures: another registry UID, an exhausted, missing or unreadable registry, a refused PATCH, an untrusted CA, a host name that the server certificate does not name and an API server that does not answer |

The expected bodies come from the product OpenAPI document; an error body has only
a code and a nonempty message. `CatalogApi` also checks each product
response against that document: the document must define its status for the
operation, and the body must match the schema of that status. The scenarios
calculate routes, key homes and advisory locks again with
the JDK, and they read the dataset TSV files, not the Java definitions. On the lab,
every `up.sh` invocation runs Flyway through Helm hooks and validates actual
history under the [migration rules](../database/RUNBOOK.md#database-schemas-and-migrations).

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
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-infrastructure
```

The required snapshot files are `chart/`, `environment.yaml`, `inventory.yaml`,
`inventory.json`, `kind.json` and `infrastructure.yaml`; `dns-probes.yaml` is also
needed for `kind-dns`. The inventory is read from the snapshot. Keep `-Dshardshop.kind.snapshot="$snapshot"` on every standalone
invocation; the suite does not create or reconcile the lab.

The `kind` profile defaults to `kind-deployment`, which combines `kind-topology`,
`kind-workload`, `kind-saga` and `kind-observability`. It needs an already-migrated and deployed lab
with all nine running application pods. The saga scenario creates two scoped orders
for the gate's own buyer; the other scenarios only observe. With the snapshot
above, run the default suite separately from the Testcontainers `integration`
profile:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot"
```

Disruptive scenarios require explicit selection; `kind-drill` combines
`kind-database-drill`, `kind-workload-drill`, `kind-saga-drill`, `kind-retry-drill`,
`kind-replica-read-drill` and `kind-failover-drill`:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-drill
```

The property uses JUnit tag expressions (without Gherkin's leading `@`); a
selection matching no scenarios fails. Existing lifecycle gates remain selectable:
`-Dshardshop.kind.tags='kind-config | kind-nodes | kind-retention | kind-allocator | kind-infrastructure'`
selects those checks together. `kind-dns` creates short-lived probe Pods from the
snapshot's `dns-probes.yaml`, with cleanup on failure. Each probe has a 120-second
DNS execution limit and a 17-minute overall Pod deadline to accommodate cold image startup.
Every node must resolve cluster DNS successfully before provisioning continues.
`kind-admission-bootstrap` is for fresh initialization only and requires
`-Dshardshop.kind.fresh=true`; do not use it on a retained lab. Migration and
release safety checks are also selectable: `kind-migration-reservation`,
`kind-migrations-idle`, `kind-publications` and `kind-drills-idle`. Helmfile runs
them before migrations or routing/application publication. `kind-drills-idle`
stops the application release while the `shardshop-kind-drill` lock exists. The idle guard rejects active, pending or terminating Flyway Jobs,
including legacy Jobs. Publication observation waits up to 180 seconds for every
inventory Publication to be applied at its current generation. The basic standalone
snapshot above is sufficient for these observations and `kind-deployment`. Normal builds and `-Pintegration` do not access the kind lab. Cucumber reports are under
`shardshop-testing/target/kind-reports/`.

The workload credential gate accepts only the documented reader/seeder/producer HTTP
settings with literal typed values. It rejects unknown environment variables,
JVM configuration overrides, command/argument overrides and executable hooks or
probes. Product URLs must contain no user information, query or fragment.
Reader images built before periodic digest reporting need a rebuild and deployment
for verification to survive startup-log rotation.

The normal `kind-deployment` gate requires product primary reads. It checks the
product and routing ConfigMaps, Deployment template and ready pods for replica-profile settings,
including environment and JVM property overrides; indirect profile/JVM settings and
external configuration locations (including routing-config overrides) fail the check.
Both mounted ConfigMaps must contain nonempty application.properties. Unrelated environment and JVM
settings remain allowed. This validates declared deployment configuration, not an
introspection of the running JVM's resolved configuration. The opt-in
[strict replica-read drill](#strict-replica-read-drill) implements the deployed
step 6.1 checks, which passed [live qualification on 2026-10-26](../CHANGELOG.md#step-61).
It captures the exact prior profile sources, registers restoration before changing
them, and verifies the restored Deployment and ready pods before releasing the
shared drill lock. Helm alone cannot remove a profile environment variable added
outside the release.

All drill families share the `shardshop-kind-drill` ConfigMap lock. Recovery
registers before each temporary mutation and runs in reverse order on failure or
normal completion, with a JVM termination hook for ordinary shutdown. Fence updates
atomically test the observed Cluster UID and resource version; concurrent changes
stop the mutation rather than overwrite another actor's fence. If recovery
fails, the lock stays and the report identifies the remaining temporary changes.
After an unclean process termination, ensure no drill is running, inspect the
report and lock, restore fenced instances and wait for database/product readiness,
then remove only the drill's temporary Jobs, SQL objects and slots before releasing
the lock. After a saga drill, also restore two order HTTP replicas and one replica each of the order saga and order
producer Deployments. Remove the drill's `SHARDSHOP_LEDGER_ALLOWED_CURRENCIES` variable
from the ledger Deployment and the drill's `nodeSelector` from the order saga Deployment.
Helm does not remove these out-of-band additions, thus `up.sh` stops at
`kind-drills-idle` until you release the lock. Never restore an older generator
registry or lower `highWaterMark`.

After the last check, remove the local snapshot:

```bash
rm -rf -- "$snapshot"
```

## Retry and DLQ capacity drills

Step 5.4 has two complementary suites. Measure the pinned broker's five delayed
redeliveries, sixth-rejection parking boundary and total parking time in disposable
containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pintegration verify \
  -Dcucumber.filter.tags=@broker-retry-timing
```

For the application recovery checks, prepare the [kind snapshot](#kind-verification-snapshot)
and use the existing running lab with the step 5.1–5.3 code and migrations deployed:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-retry-drill
```

`kind-retry-recovery` selects the shard-outage case. It pauses the producer through
its control API and drains prior work, stops ledger consumption long enough to
prepare one published command, then fences all three instances of the buyer's
shard. After ledger resumes, the unavailable shard causes result delivery to
exhaust the broker budget. The drill inspects the exact parked result and its
`delivery_limit` death reason, restores the shard, and waits for automatic saga
reconciliation to regenerate the saved result. It verifies one ledger entry,
stock reservation and profit credit, then acknowledges only the inspected parked
diagnostic. It never republishes the parked result.

`kind-dlq-capacity` selects four cases: an unbound or full DLQ for each of
`ledger.record-order` and `order.ledger-results`. They keep the broker and source
queues running, use temporary exact-queue policies with a small message-count
limit, and inject only marked malformed filler. Retained dead letters fill the
source, negative publisher confirms block the real order CDC or ledger result
outbox, and restoration allows the same outbox to publish and the scoped order
to confirm. These are queue-capacity checks, not a new sustainable-throughput
measurement. The normal 1,000-message/16 MiB limits remain unchanged after recovery.
Use `kind-dlq-unbound` or `kind-dlq-full` to select only the two binding or capacity
cases. Full-DLQ recovery removes owned parked filler and restores the original
parking capacity, retaining the small source limit until the outbox progresses.
The check preserves the broker's native 180-second internal dead-letter
confirmation timer. Individually timestamped messages can straddle its first
retry tick, so full-DLQ outbox recovery allows 450 seconds for two ticks and
application recovery. The [dated evidence](../CHANGELOG.md#step-54) records the
observed counts, elapsed recovery and harness corrections.

The shared `KindDrillGuard` registers restoration before mutations. It restores
producer rate/pause settings, shard fencing, ledger replicas, bindings and temporary
policies; the producer JVM remains running. A selected source or DLQ containing unrelated
work is a failed precondition. Cleanup acknowledges only this drill's owned filler
or inspected result, never purges a shared queue. Scoped orders and their business
effects remain as evidence. Logs record counts, timings and IDs without payloads
or credentials. If restoration fails, keep `shardshop-kind-drill`, inspect the
reported outstanding action, restore that state and verify readiness before
removing the lock.

## Live deployment check

Prepare the [kind verification snapshot](#kind-verification-snapshot) from
this lab's inputs, then run the default deployment suite:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot"
```

The lab must already be running, migrated and deployed through `up.sh`, with two
Ready product pods and one running reader. The scenarios
observe this deployment and preserve reader replicas. They check the deployed
routing and database topology, workload credential restrictions, the saved IDs and
rows for all 151 `catalog-v2` sellers and 201 products, and the reader's resolution
digest. Only these rows can also exist: the saga gate's two sellers and two products,
and on the retained lab the complete `catalog-v1` (150 sellers, 300 products) and the
first saga gate seller with its two products. Any other catalog row fails the check.
Within 120 seconds, the read counters of both Ready product pods must increase;
a fresh reader report interval must contain only `OK` reads at 180/s or more.
Use `-Dshardshop.kind.tags=kind-workload` for the workload checks alone.

Repeated seeding and product pod replacement are explicit disruptive drills:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-workload-drill
```

The drill runs repeated temporary seeder Jobs and compares their digests and the
saved seller/product IDs with the existing catalog. It replaces one product pod
and requires a distinct generator ID greater than the registry `highWaterMark`
saved before replacement, and no greater than the current high-water mark. After
replacement it repeats the two-pod traffic check and requires an all-OK interval
that starts after recovery. Temporary Jobs are removed and the already-running
reader keeps its replicas. The pod replacement consumes generator allocations;
cleanup never rewinds allocator state.

On the lab, the catalog is already complete, thus the reader does not wait there.
`CatalogWaitTest` of the reader module shows the wait: the reader sends no load
while an entity is missing, starts after the entity appears, and stops after the
maximum wait.

## Topology check and database drill

After catalog and ordering migrations, prepare the
[kind verification snapshot](#kind-verification-snapshot) and run the
nondisruptive topology/routing check on every inventory primary:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-topology
```

It verifies every shard's writable primary, streaming quorum standbys, independent
database lineage, `-rw`/`-ro` Service endpoints and distinct Bound PVCs/backing
volumes. It compares PostgreSQL SHA-256 arithmetic and the deployed seller and
buyer placement constraints against the shared golden fixtures used by Java.
Counts come from the snapshot inventory; this check creates no SQL objects.

Run the reversible database drill explicitly in this disposable lab. It defaults
to the first inventory shard; select another shard by inventory name:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-database-drill \
    -Dshardshop.kind.drillShard=shard-b
```

A probe row must reach both standbys, writes on standbys must fail, and the probe
schema must remain absent from peer shards. The drill requires the lab's three
instances per shard. It checks failover-slot synchronization and WAL retention,
replaces one standby pod while preserving its PVC, and temporarily fences one then
both standbys to observe available and blocked quorum commits. Reads and writes
are disrupted during the drill. It restores standbys and removes its temporary
SQL objects and slots without deleting a PVC. The shared lock and failure recovery
rules in the [snapshot runbook](#kind-verification-snapshot) apply.

## Strict replica-read drill

Step 6.1 passed retained-lab qualification on 2026-10-26, including exact primary
profile/control restoration and six final deployment/idle gates. The lab was
returned to its stopped state. See the [evidence](../CHANGELOG.md#step-61).
Prepare the [kind verification snapshot](#kind-verification-snapshot) and explicitly
select the disruptive drill, which is also included in `kind-drill`:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-replica-read-drill \
    -Dshardshop.kind.drillShard=shard-b
```

Omit `shardshop.kind.drillShard` to select the first inventory shard. The drill
requires the deployed primary-read gate, two ready product pods, the existing
HTTP workloads and three instances per shard. It saves and pauses producer,
reader and interactive-seeder controls, then waits for in-flight work and the
producer's nonterminal backlog to drain. It checks an acknowledged product write
with an immediate primary read before changing profiles.

Before any profile mutation, it validates both product/routing ConfigMaps, the
Deployment template and ready product pods with the primary-read gate. It captures
exact ConfigMap data and the full product pod template, including literal profile
environment settings and JVM `-D` overrides in option variables, commands and
arguments. It registers recovery before converting every applicable source to
`replica`; indirect/external configuration fails the gate. JSON patches test the
resource UID, resource version and saved field, so concurrent edits prevent an
overwrite. Snapshot evidence stores identities and hashes; private temporary patch
files avoid exposing environment values in command arguments.

After a completed profile rollout, the reader runs at 200 requests/s and must
produce a fresh complete all-OK interval at at least 180/s with traffic on both
product pods. Paused replay on both selected standbys establishes a committed
primary write that replica reads return as `404 PRODUCT_NOT_FOUND`; resuming replay
must make it readable. Fencing one standby must preserve reads and writes. Fencing
both must yield `503 READ_REPLICA_UNAVAILABLE` on each product pod in less than four
seconds, before the five-second client deadline, while peer-shard reads succeed.
A separate bounded `ACCESS EXCLUSIVE` lock on `catalog.products`, replayed onto both
standbys, checks the same response contract for hung queries. During each outage,
a fresh reader interval must contain both successful peer reads and replica
unavailability, with no deadline, transport or other unexpected outcomes. Restored
standbys must resume reads, followed by another clean 200/s reader observation.

Recovery removes the query block, restores replay/fencing and the exact original
profile, and awaits the Deployment plus the primary configuration of every ready
product pod. It removes only invocation-owned product fixtures, including creations
whose response was lost, then restores saved workload controls. The final
`kind-deployment` primary gate must pass before the shared lock is released. Failed
recovery retains the lock under the [shared recovery rules](#kind-verification-snapshot).
Primary promotion is implemented by the [synchronous failover drill](#synchronous-failover-drill); step 6.2 passed [retained-lab qualification on 2026-10-26](../CHANGELOG.md#step-62).

## Coordinated backup and isolated restore

**Step 6.3 is Done**, qualified on 2026-10-26; see
[evidence](../CHANGELOG.md#step-63). The initial implementation passed 45 focused checks in 4.588
seconds on 2026-10-26 at 19:32:56+04
(`/tmp/shardshop-6.3-focused-final.log`). Live coordinated backup then passed one
scenario in 103.6 seconds and sealed four native dumps plus broker definitions in
the private host bundle
`/Users/nikita.lipatov/.local/share/shardshop/backups/2026-10-26-step63/backup`.
Restore failed before container creation on Testcontainers qualified-image
compatibility. Source cleanup succeeded and the lab was stopped at
2026-10-26 19:38:21+04. At that point correction/retry and complete restore qualification were pending. See
`/tmp/shardshop-6.3-live/backup.log` and `/tmp/shardshop-6.3-live/restore.log`.
A subsequent restore passed all four databases, row hashes, database security
and consistency checks, then CDC verification was blocked by a missing generated
broker fixture. Fixture preflight is implemented; 47 focused checks pass
(`/tmp/shardshop-6.3-fixture-focused.log`). The fixture-prepared attempt in
`/tmp/shardshop-6.3-restore-fixture` failed after 267.2 seconds: all three
missing-slot refusals, unchanged offset/history checks and pre-reset consistency
passed, but isolated ledger startup failed after explicit reset because its
allowlist included unsupported INR and omitted KRW. The corrected configuration
uses `CurrencyPolicy.SUPPORTED_CURRENCIES`. The regression failed against the old
configuration (`/tmp/shardshop-6.3-currency-before.log`); 48 focused checks then
passed in 4.590 seconds at 2026-10-26 20:02:48+04
(`/tmp/shardshop-6.3-currency-focused.log`). All three owned container IDs were
removed; the source remained unchanged and stopped. The wrapper completed in
271.167 seconds without cleanup errors. The user authorized a twenty-minute corrected-restore phase at 20:06+04;
the corrected isolated restore passed in 365.4 seconds (Maven 6:07), finishing
2026-10-26 20:13:20+04. Its private `restore-result.json` reports `passed: true`
and `ownedTargetRemoved: true`: 11,537 orders, 11,535 ledger decisions, 56 retained
published commands and 11,545 result bindings were verified; ledger entries
remained 11,534 and all three streaming checkpoints were positive.

The initial final source suite passed seven of eight cases; the reader gate failed
on under-rate intervals of 174.1, 168.9 and 169.5 reads/s. A later 30-second log
interval reached 6,000 all-OK reads (200/s). That failure remains evidence; it is
not an eight-case same-run pass. The unchanged-threshold workload-only rerun in
`/tmp/shardshop-6.3-reader-final` then passed two cases in 64.32 seconds (Maven
1:08), finishing 2026-10-26 20:23:15+04. Independent latest-allocator export
passed one case in 4.616 seconds (Maven 7.537 seconds), finishing 20:23:25+04.
The wrapper passed in 178.498 seconds without cleanup errors and stopped all four
source nodes at 20:23:36+04. Its restoration audit passed UID/spec, primary/fence,
PVC, profile/routing, controls and absence-of-owned-lock checks; no CNPG pod UID
changed. An independent Docker check confirmed removal of all three restore and
broker container IDs. Source hashes were unchanged, so the valid 365.4-second
restore result was reused without another restore. This completes step 6.3;
earlier failed attempts remain preserved.
Step 6.2 remains qualified. The separate tags `kind-backup` and `kind-restore`
are excluded from both the default kind suite and `kind-drill`.

Prepare the [kind snapshot](#kind-verification-snapshot), a running qualified
source lab, Docker/OpenSSL and the qualified packaged order/ledger outputs needed
by target-only CDC verification. Unchanged qualified application outputs do not
need rebuilding. Generate the broker fixture used by the integration profile
before restore; preflight now refuses its absence before target mutation:

```bash
helm template shardshop microservices/shardshop/infra/helm/shardshop \
  --namespace shardshop \
  --values microservices/shardshop/infra/helm/shardshop/kind-values.yaml \
  --values microservices/shardshop/infra/shards.yaml \
  --set-string render=infrastructure \
  --output-dir microservices/shardshop/shardshop-testing/target/chart
```

Existing packaged order and ledger JARs are also required. Set
`shardshop.kind.cluster` explicitly to the
source cluster name matching the snapshot. Choose absolute normalized artifact
paths outside the repository and every Maven `target` directory:

- `shardshop.backup.directory`: a new exclusive backup directory; do not precreate it.
- `shardshop.backup.allocator`: a separate latest-history file under an existing
  private parent, outside the backup directory. Preserve it independently across
  backups; restore checks its monotonic high-water mark and allocator lineage.
- `shardshop.restore.directory`: a new exclusive private evidence directory,
  separate from the immutable backup; do not precreate it.
- `shardshop.restore.target`: a fresh Docker name matching
  `shardshop-restore-` plus a DNS label, at most 63 characters in total. Existing
  containers, including stopped containers, are refused.

Use a mode `700` host parent and mode `600` artifact/allocator files. The bundle
contains full seeded/business data and exported broker credential hashes; protect
it and never commit it. Keep backup and allocator history outside kind and Maven
cleanup. For example, after assigning `$backup`, `$allocator`, `$restore` and
`$restoreTarget` to those selected paths/name:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.cluster=shardshop \
  -Dshardshop.kind.tags=kind-backup \
  -Dshardshop.backup.directory="$backup" -Dshardshop.backup.allocator="$allocator"

mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.cluster=shardshop \
  -Dshardshop.kind.tags=kind-restore \
  -Dshardshop.backup.directory="$backup" -Dshardshop.backup.allocator="$allocator" \
  -Dshardshop.restore.directory="$restore" -Dshardshop.restore.target="$restoreTarget"
```

Backup acquires the host lifecycle lock and shared Kubernetes drill lock, pauses
all three workload controls and saves exact deployment replicas. It stops writers,
drains saga/ledger work and every queue including DLQ parking queues, then stops
the remaining consumers. Nonempty dead letters refuse capture; this is an explicit
empty-queue baseline restriction. It records four full dumps, actual RabbitMQ
definitions, roles, database ownership/ACLs, routing/region/schema versions,
lineage, allocator history and complete table fingerprints. Source deployment,
controls, topology, identities and business invariants are restored before the
bundle is sealed; recovery failure retains the shared lock.

Restore verifies compatibility/integrity and the latest independent allocator
history before target mutation. It never contacts the source lab. A new owned
loopback-only PostgreSQL container holds four isolated databases with TLS,
restored roles and database ACLs. Full row/schema fingerprints and cross-shard
saga/global checks must match. A logical dump has saved CDC offsets/history but
no replication slot: packaged CDC must first refuse startup. Explicit target-only
recovery resets that metadata, resnapshots retained commands to a fresh broker
and ledger worker, then proves streaming replay of the same command/result IDs
without adding ledger effects. No ID-emitting traffic runs; the source allocator
is never rewritten. The target is removed, and private restore evidence remains
separate from the immutable bundle. No older-backup repair (6.4), PITR or
production cutover is qualified by this workflow.

After final source gates, refresh the independent latest allocator history before
stopping the lab. This standalone tag requires only explicit source cluster,
snapshot and allocator path; it needs no backup or restore directory:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.cluster=shardshop \
  -Dshardshop.kind.tags=kind-backup-allocator -Dshardshop.backup.allocator="$allocator"
```

`kind-backup-allocator` holds the host lifecycle lock and verifies deployment/saga
readiness before observing the latest mark. Avoid restarting source generators
after this capture; if a later restart occurs, refresh history again before any
ID-producing restore use. Isolated target qualification always disables ID paths.
This export is opt-in and excluded from `kind-drill` and the default selection.

Focused implementation checks (no live lab):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true \
  '-Dtest=Backup*Test,KindBackupRestoreTest,OwnedRestoreTargetTest,Restore*Test,KindDrillGuardTest,KindFailoverDrillTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dshardshop.failIfNoTests=false test
```

The initial sandboxed process/sysctl attempt was blocked; the reviewed host run
passed. Exact evidence is recorded in the [changelog](../CHANGELOG.md#step-63).
Focused checks do not establish live capture/restore qualification.

## Older-backup audit and selected repair

**Step 6.4 is Done.** Sixty focused checks, repeated native schema restoration and
the full older-backup recovery drill passed on 2026-10-26, with verified cleanup
and unchanged stopped source nodes. See [evidence](../CHANGELOG.md#step-64). All five
`kind-recovery-*` tags below are explicitly opt-in and excluded from `kind-drill`.
Use the verified [6.3 bundle and private-artifact rules](#coordinated-backup-and-isolated-restore),
latest independent allocator file and invocation snapshot. Packaged applications
and generated broker fixture are required for the drill's replay probes.

Every operation creates a fresh owned isolated target, reads only supplied backup
files/snapshot metadata and makes no source database connection or write. Choose
a new mode-700 private output directory outside Maven `target`, separate from all
input bundles, allocator file and original record. Do not precreate the output;
its existing parent must be private. Use a fresh `shardshop-restore-*` target name.
Common invocation, with `$recoveryTag` set to exactly one tag described below:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags="$recoveryTag" \
  -Dshardshop.backup.directory="$backup" -Dshardshop.backup.allocator="$allocator" \
  -Dshardshop.restore.target="$restoreTarget" -Dshardshop.recovery.directory="$recovery"
```

- `kind-recovery-schema`: common inputs suffice. This focused native regression
  restores one captured ordering schema twice and checks its owner/grants,
  publication membership and all four original database fingerprints. It runs no
  application or broker processes and is separate from full recovery qualification.
- `kind-recovery-record`: additionally pass
  `-Dshardshop.recovery.order-id="$orderId"`. Exports verified original rows to
  `$recovery/original-record` before disposing the target.
- `kind-recovery-audit`: additionally pass
  `-Dshardshop.recovery.ledger-backup="$ledgerBackup"`. This verified bundle must
  be the same age or later than the ordering bundle; its ledger replaces the
  older ledger, then permanent outcomes are exported and audited against orders,
  fingerprints and stock reservations.
- `kind-recovery-repair`: pass the ledger-backup option plus
  `-Dshardshop.recovery.record="$originalRecord"` and
  `-Dshardshop.recovery.order-id="$orderId"`. Select exactly one canonical positive
  ID and its verified original-record directory. Foreign/conflicting records,
  missing original history/costs and unproved catalog effects remain unresolved;
  pending authentic outcomes apply only when retained catalog effects agree.
- `kind-recovery-drill`: common inputs suffice. It creates a causal older dump
  from three acknowledged witnesses, proves later state existed, then restores
  the older dump losing two orders and mismatches the third fingerprint. Audit
  precedes late delivery; quarantine, orphan reservations, late acknowledgement,
  HTTP `409`, refusal of conflicting/missing records and recovery of one original
  order are checked. Replay keeps command/result IDs, ledger cardinality and all
  catalog table fingerprints unchanged. Other incidents stay unresolved.

Audit/repair retain full four-database review dumps, reports and quarantine/repair
state before deleting their owned target. The sealed review bundle is explicitly
`purpose: recovery-review-only`, `trafficAuthorized: false`. Protect its complete
data and credential material; never commit it or use it as production-cutover
authorization. All ordinary application traffic is absent from the frozen target;
only explicit isolated replay probes run. Optional physical asynchronous-loss
qualification is separate and does not change the default synchronous profile.

## Synchronous failover drill

**Step 6.2 passed retained-lab qualification on 2026-10-26**, including automatic
recovery and eight final gates. See the [evidence](../CHANGELOG.md#step-62). This drill
is disruptive and opt-in, including through `kind-drill`. Prepare the
[kind verification snapshot](#kind-verification-snapshot) against the deployed
retained lab, then select it explicitly:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-failover-drill \
  -Dshardshop.kind.drillShard=shard-b
```

Omit `shardshop.kind.drillShard` to select the first inventory shard. The drill
requires the pinned CNPG 1.30.1 operator image, three instances on the selected
shard, failover quorum and the normal primary-profile deployment/ledger policy.
The [October 26 decision](../VERSIONS.md#step-62-existing-version-decision--2026-10-26)
permits qualification on the existing operator pin; it does not supply a passing
result or change advisory/support deadlines.

`KindFailoverDrill` acquires the shared lock, saves and pauses all three live
controls (producer, reader and interactive seeder), and drains in-flight work and
the producer's nonterminal backlog. `KindFailoverWitness` creates a buyer on the
selected shard and orders through the HTTP API against a seeded peer-shard
product. It retains a confirmed anchor and the original pending command across
both a planned switchover and primary-failure promotion. With each standby absent
in turn, a ten-second observation must show CDC blocked while a real HTTP order
commits through the surviving synchronous quorum. After recovery, the original
order/saga/command/result IDs, immutable order data and outbox envelope must
survive and complete exactly one business effect. The synchronized logical slot
must cover the saved durable checkpoint; permanent reader history and the offset
partition must stay unchanged, and the durable streaming position must advance.

`KindFailoverCluster` uses CNPG's switchover status contract. Primary failure uses
UID/resource-version guarded pod deletion with an invocation-owned finalizer,
observing PostgreSQL termination before allowing replacement. It checks for at
most one writable primary and safe rejoin of the former primary. The separate
unsafe-survivor phase fences one standby before a synchronous witness commit,
then holds the aligned standby and primary deleted. It verifies that the survivor
actually lacks the acknowledged witness. Throughout 180 seconds plus configured
failover delay (at most 60 seconds), the survivor must remain read-only, never be
nominated and have no ready writable-service endpoint.

After promotion, the drill reconciles logical slots on known former primaries
before declaring the topology healthy or switching back. A captured slot may be
retired there only when the authoritative server is still the stable primary,
the former primary is a read-only standby, both retain the original database
lineage, and slot name/database/plugin match the captured identity. The local
confirmed LSN must lie between the captured and current authoritative positions;
the source must not have moved backwards. Unknown logical slots cause refusal.
Only inactive, valid, permanent failover slots that are not already synchronized
qualify. A conditional SQL statement rechecks recovery role, slot identity,
flags and exact observed LSN at retirement. The authoritative primary slot is
preserved; native synchronization must restore synchronized copies on the demoted
server before healthy-topology checks and switchback proceed.

An invocation-owned `failover_slot_*` logical slot, publication and probe schema
exercise the former-primary reconciliation branch independently of the business
slot. The probe is initialized and synchronized before promotion. During native
resynchronization, a missing, temporary or null-confirmed-LSN probe copy causes
priming of only the invocation-owned probe on the authoritative primary, while
the captured required source LSN stays fixed. The business slot is never consumed
by this priming. Recovery drops
only that named probe slot on the current primary, awaits its absence on all
three instances, then removes its publication/schema and captured probe entry
before the final business-slot switchback. This reconciliation passed the final
complete drill. Earlier failed reader and incomplete promotion attempts remain
historical evidence with their own recorded outcomes.

Fence updates use at most three conditional attempts within a forty-second total
bound. Every reread checks the original cluster UID and verifies that the fence
is original or invocation-owned. The conditional patch tests the original UID
and exact observed fence value, avoiding a global resource-version test. When
the fence key is absent it tests the observed annotations map; when that map is
missing it uses a protected metadata fallback. Unrelated annotations are
preserved. If the target state is already observed, the
update accepts a possibly lost successful response. A retry is allowed only
when the observed resource version changed; unchanged command failures, foreign
fence changes and cluster replacement stop the operation. An unclassified command
failure alone does not establish concurrency as its cause.

The 10:00 UTC live attempt completed planned switchover, actual primary-failure
promotion and their CDC checks, then failed while restoring a fence before the
unsafe-survivor observation window began. Later recovery callbacks restored the
original primary and fence; the owned unsafe-survivor schema was subsequently
removed manually. This partial evidence does not qualify the complete drill or
the unsafe-survivor observation. Step 6.2 was pending live qualification at that point;
exact commands and final recovery outcomes belong to the dated changelog.

The later 10:50 UTC attempt passed planned switchover but failed lifting a fence
during primary failure after three observed resource-version changes. Its guard
completed all cleanup without cleanup errors, released the lock and returned the
lab to its stopped state. Earlier actual-primary-failure evidence remains
separately recorded. The redacted command output does not establish the raw API
failure cause. The revised fence patch still required complete live qualification at that point.

The later semantic-fence attempt passed planned switchover, actual primary
failure, the complete 180-second unsafe-survivor observation and database
recovery, then failed on a `503` from the final new allocation POST before final
gates. Guard cleanup completed without errors and restored the stopped lab and
original state. This remains partial evidence, not complete 6.2 qualification.

The HTTP witness now retries synchronously on the guard thread with a separate
180-second recovery budget for allocation POST and for order PUT, keeping the
allocation run/ordinal fixed and replaying the same order PUT ID/body. Observing
the committed outbox has an additional 60-second bound; these are separate phase
budgets, not an overall `accepted()` SLA. Retry is limited to `503` and selected transient transport
failures; permanent errors fail immediately. Initial success requires allocation
`201` and PUT `202`; `200` replay is allowed only after an uncertain request.
Focused verification passed 57 checks in 7.866 seconds at 2026-10-26 15:24:09+04.
The revised witness still required a complete live run and final gates at that point.

The later HTTP-fixed attempt passed the entire drill body, including the full
180-second unsafe-survivor observation and fresh HTTP order. Its guard-close
producer-control restoration then failed with EOF. The wrapper restored controls
manually; seven recovery gates passed in 126.6 seconds at 2026-10-26 15:47:42+04,
the idle check passed, and the lab was stopped by 15:47:57+04 without leftovers.
This manual recovery does not qualify the revised automatic callback.

Control restoration now opens a fresh tunnel for each attempt and reads back the
exact saved rate and paused state. Each workload has at most three attempts within
one shared 180-second budget. Only `503` and classified transient transport
failures permit retry; other responses and readback mismatches are terminal.
Focused verification passed 61 checks in 9.379 seconds at
2026-10-26 15:46:02+04. At that point, complete live qualification still required
a fresh run with successful automatic callbacks and final gates.

The subsequent controls-fixed attempt passed planned switchover and promoted a
primary after failure, but owned-probe decoding emitted SQLSTATE `55000` warning
output while waiting on inactive physical standby slot `_cnpg_shard_a_1`
(PostgreSQL log PID 5231). The query reached a consistent point about 1.5 seconds
later; the strict `successfulSql` check rejected its warning output. This records
the observed failure without treating that query as a qualified probe pass.
Automatic guard cleanup, including the corrected control callbacks, completed:
the lock was absent and original primary, profile and fences were restored. The
wrapper stopped the nodes without cleanup errors after 463.700 seconds. Failsafe
reported 375.5 seconds, Maven 6:18, finishing 2026-10-26 18:06:56+04. Earlier
complete drill-body evidence remained preserved, but step 6.2 was still unqualified
at that point.
At that point, the warning-free retry/barrier correction was pending and the
prior 61 focused checks were the latest recorded result; the corrected candidate
and its subsequent focused verification are recorded below.

The warning correction now checks that both physical slots are active/streaming
and exactly match `synchronized_standby_slots` before owned-probe priming. Verbose
probe decode retries at most three times only for the exact SQLSTATE `55000`
missing-`active_pid` warning naming an expected physical slot, with exit zero and
a numeric count. A final warning-free decode is mandatory. Other warnings, mixed
warnings and nonzero exits are terminal. Reads and mutations share the existing
convergence deadline with thirty-second reserves; decode has an explicit
twenty-second bound. The business slot is never consumed and required synchronized
slot properties remain unchanged.

The isolated regression first failed on the expected generic SQL-warning check
in 4.079 seconds at 2026-10-26 18:12:47+04
(`/tmp/shardshop-6.2-owned-warning-before.log`). The corrected candidate passed
64 focused checks in 9.538 seconds at 18:15:48+04
(`/tmp/shardshop-6.2-owned-warning-after.log`), including readiness, terminal-error
and three-attempt bounds. Peer review reported no issues. Source hashes remained
unchanged for the final live qualification.

The final `/tmp/shardshop-6.2-slot-ready` run passed the complete drill: one case
in 743.7 seconds, Maven 12:27, finishing 2026-10-26 18:34:44+04. All eight final
gates passed in 111.4 seconds, Maven 1:55, finishing 18:36:42+04. The wrapper
reported `passed: true`, no cleanup errors and 920.281 seconds elapsed, returning
the lab to its stopped state at 18:36:54+04. The restoration audit passed every
check: cluster UID/spec, original primaries/fences, PVC UID/spec/backing PV,
Deployment UID/spec, routing/profile configuration, and absence of owned
finalizers and the drill lock. Database pod UIDs for shard-a instances 2 and 3
were recreated as expected; their PVCs were preserved. This complete run
qualifies step 6.2; coordinated backup/isolated restore subsequently passed
step 6.3, and older-backup audit/recovery passed [6.4](../CHANGELOG.md#step-64).
Exact commands, artifacts and preserved failures for failover are in the
[dated evidence](../CHANGELOG.md#step-62).

Recovery removes only this invocation's finalizers, restores the exact original
fence state, original primary and saved controls, and checks database lineage,
original instance membership, exact PVC/backing-volume identities, streaming
roles, deployment readiness and saga invariants before releasing the shared lock.
It deletes no PVCs or PVs. The temporary unsafe-survivor probe schema is removed;
normal HTTP fixture buyers, allocations, orders and their permanent business
history remain. A recovery failure retains the lock under the
[shared recovery rules](#kind-verification-snapshot). Coordinated backup/restore
passed step 6.3; older-backup audit/recovery passed [6.4](../CHANGELOG.md#step-64).

## Saga gate and drill

The producer owns catalog gating and buyer creation in Java. Helm deploys it with
the reader and seeder; startup does not implement its HTTP loops. The default
`kind-deployment` selection includes `kind-saga`. This gate does not examine
producer orders, thus the producer rejection share, stock use and restart replay
do not change its result. Through local port forwards to the product and order
Services, it creates valid US/USD and EU/EUR seller/product fixtures and its own
buyer. The order shard differs from each product shard. Each product starts with
1,000,000 units. The fixed `kind-saga-gate-v2.*` creation keys make later runs recover
the same IDs. The gate first checks that the deployed ledger allowlist is every
supported currency. Each run then submits one USD order and one EUR order, and the
normal policy must confirm both. The gate checks the ledger decisions, the stock
reservations, the seller profit, the HTTP status and the stock and profit invariants.
Each run uses one unit of each gate product and keeps its two orders as evidence. The
gate does not stop the producer or change the ledger; business rejection belongs to
the explicit saga drill below.

Prepare the [verification snapshot](#kind-verification-snapshot) and run
the explicit saga drill for duplicate delivery, permanent rejection replay after
transport cleanup and a temporary EUR allowlist, two orders for the same stock,
CDC resumption after moving saga to another worker, an HTTP rollout that preserves
the saga pod and CDC active PIDs, and a saga restart while writes continue:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot" -Dshardshop.kind.tags=kind-saga-drill
```

The three drill scenarios restore temporary deployment settings afterward. The
business-invariant scenario pauses the producer while exercising its own orders;
the rollout and restart scenarios keep writes running. The lab has one saga replica
with one stock worker thread, so its stock reservations run in sequence.
The retained lab has one catalog shard per region, and each permitted currency belongs to one shard.
Its two-item stock-failure check therefore verifies atomic rollback within that shard: an oversized
second item leaves both stocks unchanged, with no reservation rows or profit credits. The stock-reservation
PostgreSQL scenarios use two US shards to cover cross-shard partial-reservation compensation and
competing stock coordinators. The drill keeps business and allocator history;
new order JVM starts permanently consume generator IDs. Do not run the kind suite
and the disposable Testcontainers suite against shared build outputs concurrently.

## Crash recovery and observability

Step 5.5 uses disposable PostgreSQL/RabbitMQ containers for exact process-crash
boundaries. Rebuild the changed packaged applications, then select the recovery
suite from the repository root:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-order,shardshop-ledger -am -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pintegration verify \
  '-Dcucumber.filter.tags=@crash-recovery or @cdc-relay or @order-processes'
```

The test JDK must include `jdk.jdi` (the configured JDK 25 does). A test-only JDWP
listener binds a random loopback port in a packaged JVM. The harness installs a
source boundary resolved from a unique sequence of complete statements before
startup resumes, suspends the JVM there,
checks independent SQL/broker evidence, and forcibly kills it. Application images
and production settings have no debugger or crash switches. The barriers validate
unique statement anchors, methods and executable bytecode locations. Harmless line
shifts need no test edit; missing or ambiguous statements fail before attachment,
and changed statements require an intentional anchor review.

These cases distinguish an uncommitted ledger decision from a committed decision
whose delivery is still unacknowledged, and broker-confirmed publication from its
database mark and durable CDC checkpoint. Recovery retains command/result identity
and permanent outcomes. Integrated command/result cases also commit new orders
through the independent HTTP JVM while the consumer JVM is suspended. The existing
CDC cases cover each shard, snapshot handoff, rollback, metadata changes,
mandatory returns, connection loss, exclusive ownership and missing/invalid durable
state. Slot/offset/WAL destruction happens only in these disposable databases.
Promotion and synchronized-slot failover use the separate [synchronous failover drill](#synchronous-failover-drill); step 6.2 passed [retained-lab qualification on 2026-10-26](../CHANGELOG.md#step-62).

After deploying with `scripts/up.sh`, prepare the [kind snapshot](#kind-verification-snapshot):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pkind verify \
  -Dshardshop.kind.snapshot="$snapshot" \
  '-Dshardshop.kind.tags=kind-saga-drill | kind-observability'
```

The HTTP rollout drill submits scoped orders at up to one per second through the
Kubernetes Service proxy, with a five-minute bound. A pod-bound port forward could
not follow a replaced HTTP pod. The drill verifies commits before, during and after
replacement, unchanged saga pod UID and active slot PIDs, and complete stock,
ledger and profit effects for every accepted order. It restores the rollout
annotation through the existing drill guard. No new workload pod is needed.
`kind-observability` is also part of the normal deployment gate; the
[monitoring guide](../infra/README.md#monitoring) names the endpoints and signals.

## Capacity measurement

Read the [measurement protocol](ARCHITECTURE.md#capacity-measurement) first.
Use an already prepared lab; follow the [lifecycle runbook](../infra/RUNBOOK.md#stop-and-resume-the-cluster) to resume it if
needed and record its prior running state. Reuse the deployed lab without a rebuild
of images or new migrations.

Run the existing control tunnels in a separate terminal:

```bash
bash microservices/shardshop/scripts/webpages.sh
```

Save original controls before changing them, pause the producer, and poll its
status for at most 60 seconds until `inFlight` and `producer.nonTerminalBacklog`
are both zero. A nonzero backlog or failed workload makes the phase invalid.
Leave the interactive seeder idle.

```bash
curl --fail --silent http://127.0.0.1:18082/api/v1/order-producer/control \
  > /tmp/shardshop-capacity-producer-before.json
curl --fail --silent http://127.0.0.1:18083/api/v1/product-reader/control \
  > /tmp/shardshop-capacity-reader-before.json
curl --fail --silent -X POST http://127.0.0.1:18082/api/v1/order-producer/control/pause
curl --fail --silent http://127.0.0.1:18082/api/v1/order-producer/control
```

Apply each target in the protocol, record the UTC time of the update, and poll
reader logs every ten seconds for the three eligible reports. Stop each phase
within 150 seconds. For example, the first target is:

```bash
curl --fail --silent -X PUT -H 'Content-Type: application/json' \
  -d '{"rate":500}' http://127.0.0.1:18083/api/v1/product-reader/control/rate
curl --fail --silent -X POST http://127.0.0.1:18083/api/v1/product-reader/control/resume
date -u '+%Y-%m-%dT%H:%M:%SZ'
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-product-reader \
  --timestamps --tail=60
```

Record [pod inventory](../infra/README.md#pod-inventory), hardware and control
status alongside each phase. The reader's `Product reads, interval` lines provide
requests, duration, outcomes, latency, regions and pool snapshots. Retain raw
reports with the dated evidence. For write measurements, use the existing
[order-capacity runbook](../shardshop-workload/shardshop-order-producer/RUNBOOK.md)
and the stricter backlog criteria in the protocol.

For writes, save monotonic timestamps around each producer control request and
check both the elapsed observation time and post-warmup latency-sample count as
specified in the protocol. Capture `/internal/order/write-metrics` from **each**
order HTTP pod alongside `/q/metrics`; the former provides separate allocation
and PUT p95 windows, while the latter's request sums/counts provide averages.
Do not treat those per-pod windows as interval percentiles or add their p95s to
reconstruct the producer's allocation-through-PUT p95.

Always restore `targetRate` with the same `/control/rate` endpoint and restore
`paused` with `/control/pause` or `/control/resume` from **each saved control**,
including on errors. Confirm status by GET. Stop the task's `webpages.sh` process
with Ctrl+C (SIGTERM also cleans up its tunnels); if the lab was initially stopped,
finish with:

```bash
bash microservices/shardshop/scripts/down.sh
```

Verify all four retained kind nodes are stopped and task-owned tunnel ports have
no listeners. Do not replace saved settings with defaults when reproducing the
measurement in a differently configured lab.
