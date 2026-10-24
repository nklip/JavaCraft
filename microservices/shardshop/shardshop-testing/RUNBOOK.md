# Verification runbook

[ShardShop](../README.md) › [Verification](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

Run every command from the repository root. Standalone kind suites require the
documented invocation snapshot. Default acceptance observes the existing deployed
lab; disruptive drills require explicit selection, use the shared drill lock and
register recovery before any mutation. Keep every wait bounded and do not claim
complete coverage for a planned scenario.

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
`kind-workload` and `kind-saga`. It needs an already-migrated and deployed lab
with all nine running application pods. The saga scenario creates two scoped orders
for the gate's own buyer; the other scenarios only observe. With the snapshot
above, run the default suite separately from the Testcontainers `integration`
profile:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pkind verify \
    -Dshardshop.kind.snapshot="$snapshot"
```

Disruptive scenarios require explicit selection; `kind-drill` combines
`kind-database-drill`, `kind-workload-drill` and `kind-saga-drill`:

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
