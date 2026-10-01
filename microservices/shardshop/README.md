# ShardShop

[Architecture](ARCHITECTURE.md) · [Implementation plan](PLAN.md) ·
[Version policy](VERSIONS.md) · [Artifact lock](versions.lock.yaml)

Steps 0.2–0.4 supply six Spring Boot application skeletons, their build/test
configuration, and the shared shard-routing library. Each application starts a minimal
application context and exits; no HTTP server, database connection, messaging,
seeding, or load generation is implemented yet. No Docker or Kubernetes is
needed to build or test these skeletons. Product and order now require a routing
configuration file when launched; tests supply their own fixture.

| Maven module | Entry point |
|---|---|
| `shardshop-product` | `dev.nklip.javacraft.shardshop.product.ProductApplication` |
| `shardshop-order` | `dev.nklip.javacraft.shardshop.order.OrderApplication` |
| `shardshop-ledger` | `dev.nklip.javacraft.shardshop.ledger.LedgerApplication` |
| `shardshop-workload/shardshop-product-seeder` | `dev.nklip.javacraft.shardshop.workload.seeder.ProductSeederApplication` |
| `shardshop-workload/shardshop-product-reader` | `dev.nklip.javacraft.shardshop.workload.reader.ProductReaderApplication` |
| `shardshop-workload/shardshop-order-producer` | `dev.nklip.javacraft.shardshop.workload.producer.OrderProducerApplication` |

The ShardShop parent has five children. `shardshop-sharding` is a plain Java
library holding the shard-routing rule; only `shardshop-product` and
`shardshop-order` depend on it. `shardshop-workload` aggregates its three
applications and has no executable code. Applications have no dependencies on
one another, and no API, model, or JSON types are shared.

The regional model assigns `shard-a` to US, `shard-b` to EU, and `shard-c` to
ASIA. Sellers create products only on their home shard; buyers may buy from any
region, including multiple regions in one order under the existing currency and
stock rules. Service-issued seller/buyer fixtures carry immutable home regions
derived from their ID routes; products inherit their seller's region.

The target architecture permits ID creation only in `shardshop-product` and
`shardshop-order`. Product issues seller/product IDs; order issues buyer/order,
saga, command and message IDs, including ledger result IDs. Workload modules
obtain IDs through service APIs and reuse them on retries; they never generate
IDs or derive fixture IDs. Ledger reuses IDs supplied by order. Workloads and
ledger have no Snowflake dependency or generator-allocator access. These API and
generation contracts remain planned; the current applications are skeletons.

## Java and Maven

Set `JAVA_HOME` to a JDK **25 or newer**. The SDKMAN `25-open` installation
works; on this machine it reports `25+36-3489`. For example:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25-open"
```

The lock records an official macOS arm64 OpenJDK 25 archive and its SHA-256 as a
reference download. The build has no vendor or exact-patch restriction. Use the
same JDK's `bin/java` to launch the packaged applications.

The selected OpenJDK runtime also applies to Java migration tools. Application
and Flyway containers use the pinned Canonical OpenJDK 25 JRE on Ubuntu 26.04;
the Flyway image is assembled from its upstream libraries without copying the
upstream image's Temurin runtime. This does not change the local `25-open`
installation or the JDK selected by `JAVA_HOME`.

Maven comes from the command line: use the `mvn` on your `PATH`. ShardShop keeps
the repository's minimums, Maven 3.9.0 and Java **25**; newer JDKs such as 26 also
work. Its Toolchains execution selects the `JAVA_HOME` JDK, so compilation and
forked tests use the same JDK as Maven; see the
[JDK selection parameters](https://maven.apache.org/plugins/maven-toolchains-plugin/select-jdk-toolchain-mojo.html).
Compilation targets release 25 with preview features disabled, whichever JDK
builds it. These rules are inherited only by ShardShop's children.

## Build and run

Run from the repository root with `JAVA_HOME` already set. Always select a
ShardShop POM; do not invoke the repository root reactor. Build one application
with `-pl`; `-am` also builds `shardshop-sharding` for product and order:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am clean verify
# After up.sh and migrate.sh have provisioned the lab:
kubectl --context kind-shardshop -n shardshop get configmap shardshop-routing \
    -o jsonpath='{.data.application\.properties}' > /tmp/shardshop-routing.properties
export SHARDSHOP_ROUTING_CONFIG=file:/tmp/shardshop-routing.properties
"$JAVA_HOME/bin/java" -jar microservices/shardshop/shardshop-product/target/shardshop-product-1.0-SNAPSHOT.jar
```

Verify all six one at a time, without installing anything. For the JAR launches,
keep `SHARDSHOP_ROUTING_CONFIG` exported as above:

```bash
for module in \
  shardshop-product \
  shardshop-order \
  shardshop-ledger \
  shardshop-workload/shardshop-product-seeder \
  shardshop-workload/shardshop-product-reader \
  shardshop-workload/shardshop-order-producer
do
  mvn -B -ntp -f microservices/shardshop/pom.xml -pl "$module" -am clean verify || exit 1
  "$JAVA_HOME/bin/java" -jar \
    "microservices/shardshop/$module/target/${module##*/}-1.0-SNAPSHOT.jar" || exit 1
done
```

Each startup test invokes the real main method and checks that its application
bean exists in an active context. JaCoCo reports go to each application's
`target/site/jacoco/`; executable Spring Boot JARs go to `target/`.

Step 0.3 adds scoped dependency/plugin checks, SBOMs, pinned test images, and
integration-test configuration (commands below). Step 0.1 now pins the
user-authorized kind 1.36.4 fallback and Canonical OpenJDK 25 JDK/JRE images on
Ubuntu 26.04. Their digest checks, product container build/tests, and all six JRE
startup checks passed on 2026-09-28; the lock records the verified digests.
Cluster setup and final application images remain separate milestones.

## Dependency and test validation

Boot's **4.1.1 BOM** manages application/test dependencies. The local parent pins
all 17 build/report plugins; security overrides are confined to their own plugin
classpaths. Comments in the parent POM explain the aligned JUnit/Netty/Mockito/Lombok
properties and name the advisory each plugin override fixes.
No application dependency is added merely because the BOM manages it.

Surefire runs `*Test` during `test`; opt-in `-Pintegration` runs `*IT` through
Failsafe during `verify`; there are no integration tests yet. Tests load Mockito's
agent at JVM startup because dynamic agent loading is disabled. `shardshop-sharding`
tests its router against golden vectors whose expected shards were computed
independently. PostgreSQL 18.6 and RabbitMQ 4.3.6 test-image
properties use the lock's immutable digests. No containers run at this stage;
Testcontainers dependencies and auxiliary image pins belong to the first tests
that actually use containers.

For a fast unit-only loop or explicit integration check:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am clean test
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am -Pintegration clean verify
```

Add the `audit` profile to write dependency and plugin reports for one
application:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am -Pintegration,audit clean verify
```

It writes `effective-pom.xml`, `dependencies.json`, `plugins.txt`, `sbom.json`
and `sbom.xml` to that application's `target/audit/`. The SBOM goal needs Maven
online mode; with `-o` it is skipped with a warning. Unit coverage is under
`target/site/jacoco`; integration coverage uses `jacoco-it.exec` and
`target/site/jacoco-it`. `-Djacoco.skip=true` works for both runners.

## Shard inventory and routing

[`infra/shards.yaml`](infra/shards.yaml) is the only maintained list of shards.
Its order defines routing indexes; each entry has an immutable home region. The
initial inventory is:

```yaml
shards:
  - name: shard-a
    region: US
  - name: shard-b
    region: EU
  - name: shard-c
    region: ASIA
```

[`infra/helm/shardshop/`](infra/helm/shardshop/) contains shared templates for
clusters, databases/roles, migration Jobs, and routing configuration. Common
settings live in `values.yaml`; kind storage/placement settings live in
`kind-values.yaml`. Helm only renders these resources; `kubectl apply` owns their
lifecycle, preserving existing names and PVCs. There are no per-shard directories,
committed generated manifests, or new database Helm releases. The separate ledger
is excluded from the shard list. Names must start with a lowercase letter, contain
only lowercase letters, digits and hyphens, end in a letter/digit, and fit 44 characters,
leaving room for either stream's migration Job suffix.
Empty/duplicate or malformed names, `ledger-db`, and missing/invalid regions fail
before deployment. Regions use only uppercase `US`, `EU`, and `ASIA`; a custom
inventory may assign multiple shards to one region. The chart adds the
`shardshop.javacraft/region` label to each shard Cluster and its inherited metadata.
These are business regions in the local lab, not separate geographic locations.

Preview without Kubernetes access:

```bash
bash microservices/shardshop/scripts/render.sh infrastructure
bash microservices/shardshop/scripts/render.sh migration shard-a
bash microservices/shardshop/scripts/render.sh migration shard-a ordering
bash microservices/shardshop/scripts/render.sh migration ledger-db ledger
bash microservices/shardshop/scripts/render.sh routing
```

The migration render takes `SHARD [catalog|ordering]` and defaults to `catalog`.
Its Job, SQL ConfigMap, migrator Secret, and component names follow the selected
stream; both shard streams receive `shardRegion`, `shardIndex`, and `shardCount`.
`migration ledger-db ledger` selects the separate ledger stream without shard
placeholders or region labels. The chart rejects ledger streams on shards and
shard streams on `ledger-db`. `up.sh` provisions all three schemas and their roles;
`migrate.sh` runs catalog on every shard, then ordering on every shard, then ledger
once. It waits for the ordering outbox publications before activating routing.

The scripts use Python 3's standard JSON library and Helm, with no YAML package to
install. Earlier live inventory and script checks are recorded in PLAN.
`SHARDSHOP_INVENTORY=/path/inventory.yaml` selects another inventory;
`SHARDSHOP_ENV_VALUES=/path/values.yaml` replaces the kind overrides.

`up.sh` provisions the declared shards and ledger. `migrate.sh` waits for their
deployed clusters/databases, migrates sequentially, then publishes `shardshop-routing`.
That ConfigMap contains `application.properties` with aligned
`shardshop.routing.shards` and `shardshop.routing.regions` lists. Its `version` key
remains SHA-256 of the comma-joined UTF-8 names; `regionVersion` is SHA-256 of the
comma-joined UTF-8 `name=REGION` pairs in inventory order. Before running any
migrations, `migrate.sh` saves both hashes in `shardshop-migration-topology`.
Both existing ConfigMaps must contain matching `version` and `regionVersion`
values. The scripts refuse missing hashes or a changed saved or published topology
or region mapping; absent ConfigMaps are allowed for initial bootstrap.
The saved reservation survives partial migrations and publication failures;
matching reruns reuse it. Publication is atomic;
a failed migration leaves the previous routing configuration intact. Startup and
migration snapshot the input inventory for the duration of each run.

Product and order import `/etc/shardshop/routing/application.properties` at startup;
future Deployments must mount the ConfigMap there. Local JAR launches copy
its properties to a file and set `SHARDSHOP_ROUTING_CONFIG` as shown above. The Java
library requires names and regions, rejects invalid entries or mismatched list
lengths, and keeps an immutable list of shards with their regions. It routes the
unsigned SHA-256 digest of a positive decimal ID modulo the list size.
Missing or invalid configuration fails startup. The initial three-shard vectors are
unchanged.
Applications do not watch cluster health or reload routing while running; a failed
primary remains on the same shard, behind the same `-rw` Service.

Adding a shard means adding one inventory entry; all corresponding resources and
routing settings are generated. Changing membership **or order** changes ownership
of existing IDs. `up.sh` and `migrate.sh` refuse a list that differs from an already
published version; a changed published region assignment is also refused.
Schema V1 is independent of the unchanged version-2 ID-routing algorithm.

For a different topology, stop applications/workloads and reset
this disposable lab (`kind delete cluster --name shardshop`), then run `up.sh`,
`migrate.sh`, export the new configuration, and reseed before restarting workloads.
This reset destroys the lab's data. Online data redistribution and rolling topology
changes are outside this implementation; deleting the routing ConfigMap alone
would bypass the guard without moving any data.

## Local Kubernetes cluster, operator and databases (steps 1.1–2.3)

The lab runs on kind, and everything through the final acceptance run works on
this one machine; the AWS track with Terraform (PLAN milestone 7) is optional and
comes later. Take `docker`, `kind`, `kubectl`, `helm`, `openssl`, and `python3` from your `PATH`, like
Maven; this project never downloads or pins tool binaries. It was tested with
Docker Desktop 4.92.0, kind 0.33.0, kubectl 1.36, and Helm 4.3.0 (for example,
`brew install kind kubectl helm`). The node image's advisories were reviewed on
2026-09-28 and accepted for this loopback-only, disposable lab. Give Docker
Desktop's VM at least 12 GB of memory (Settings → Resources); `up.sh` stops early
if it has less. Migrations also require Docker's Buildx plugin: the migration
script builds the OpenJDK Flyway image and loads it into the kind nodes.

[`infra/kind.yaml`](infra/kind.yaml) defines one control plane and three workers,
one per instance of each three-instance shard, on the digest-pinned Kubernetes
1.36.4 node image, with the API server on 127.0.0.1. kind cannot add nodes, so a
cluster created with fewer workers must be deleted and recreated once.
Manifests follow the [portability rules](PLAN.md#portability-beyond-kind): a
shared chart in `infra/helm/shardshop/` with kind settings in `kind-values.yaml`.

Create or reuse the cluster from the repository root:

```bash
bash microservices/shardshop/scripts/up.sh
```

It creates the `shardshop` cluster if it does not exist and waits for all nodes to
be Ready. It then installs CloudNativePG 1.30.1 from its vendored chart with
`helm upgrade --install`, waiting up to 3 minutes for the operator, waits up to 180
seconds for every CRD to be Established, and renders/applies the shared chart. That creates
the `shardshop` namespace with the `restricted` Pod Security label, three
three-instance clusters (`shard-a`/US, `shard-b`/EU, `shard-c`/ASIA), and the single-instance
`ledger-db` cluster. It waits up to 300 seconds for every database cluster to be
Ready, then until each cluster's `-rw` Service accepts connections. It also
creates random password Secrets only if absent: `catalog-migrator`,
`ordering-migrator`, `product-app`, `order-app`, and `ordering-cdc` are shared by
the shards; `ledger-migrator` and `ledger-app` belong to the separate ledger.
It applies CNPG `DatabaseRole`s and `Database` resources for catalog, ordering,
and ledger, waits for roles at their current generation, then waits up to 180
seconds each for a Database's current generation and `applied` status. It never
creates tables or other schema contents; `migrate.sh` does (step 2.3 below).
A fresh run takes about five minutes, mostly image
pulls; a rerun takes seconds. kind adds the `kind-shardshop` context to
`~/.kube/config` and selects it; the script passes that context on every command
and never deletes a cluster, namespace, or volume. Check the result:

```bash
kubectl --context kind-shardshop get nodes
kubectl --context kind-shardshop get storageclass
kubectl --context kind-shardshop get namespace shardshop --show-labels
kubectl --context kind-shardshop get crds
helm --kube-context kind-shardshop -n cnpg-system list
kubectl --context kind-shardshop -n cnpg-system get deployment cnpg-cloudnative-pg
kubectl --context kind-shardshop -n shardshop get clusters,pods,pvc -L shardshop.javacraft/region
```

Stop the lab when you do not need it, to free Docker memory:

```bash
bash microservices/shardshop/scripts/down.sh
```

It stops the four node containers without deleting them; volumes, databases and
cluster state stay inside. PostgreSQL shuts down cleanly within seconds, and Docker
stops the rest after its default 10-second timeout. Rerun `up.sh` to start the same
containers; it waits for the API server and for every `-rw` Service, because the
stored Ready statuses predate the restart, and takes about a minute. Only
`kind delete cluster --name shardshop` deletes the lab's data.

[`infra/helm/cnpg/`](infra/helm/cnpg/) holds the official CloudNativePG chart
0.29.1, verified against the lock's SHA-256 and its cosign signature, and
`values.yaml`, which pins the operator by tag and digest. The chart uses that one
reference for both the operator image and `OPERATOR_IMAGE_NAME`, and it keeps its
CRDs if the release is ever uninstalled, so the database clusters survive. The
operator installs before the shared database resources so its APIs and webhooks are
ready for database resources. To upgrade, review the lock's support deadline, then
vendor and verify the new chart and update `values.yaml` and the lock together;
a later cloud target installs the same chart and values.

Each shard runs PostgreSQL 18.6 with one primary and two standbys, each on a separate
worker with its own 2 GiB PVC. The nine pods share three workers; anti-affinity is
scoped to each shard. Each shard is an independent database: physical replication
never copies rows between shards. The version tag lets CNPG identify PostgreSQL
upgrades; the accompanying digest pins the image bytes. The chart declares the
database, resources and replication settings; kind values select `standard`
storage and require placement on distinct workers for every CNPG `Cluster`,
so additional inventory entries need no placement changes. No pod security context is
hard-coded. CNPG generates each shard's `<shard>-app` Secret at runtime for the
`shardshop_owner` bootstrap role, which owns only the `shardshop` database. When a
cluster is created, its `postInitApplicationSQL` revokes `TEMPORARY` on the
database and all access to schema `public` from `PUBLIC`; `CONNECT` stays. No
passwords are committed or printed; catalog and ordering roles are provisioned
by the database layer (steps 2.3 and 2.5).

Step 2.1 reduces each database's memory limit from 1 GiB to 512 MiB after
measuring the tiny-data lab; requests stay at 512 MiB and 250m CPU, with a one-CPU
limit. The nine databases reserve 4.5 GiB in total. This measurement precedes the
ledger, broker and application workloads: retain the 12 GB VM minimum and
remeasure under load before treating these limits as a full-lab capacity budget.
After all three drills on 2026-09-29, the database pods used about 563 MiB of
working-set memory and the four kind nodes used 2.78 GiB; see PLAN step 2.1 for
the measurement sources and full verification evidence.

The default quorum waits for durable WAL acknowledgement from any one standby.
One absent standby leaves writes available; two absent standbys block synchronous
commits. Logical-slot synchronization prepares the shard for CDC, which waits for
both standbys. Ten replication slots and WAL senders leave room for the two
physical replicas, CDC and recovery. `max_slot_wal_keep_size=512MB` bounds slot
retention at checkpoints, while `max_wal_size=256MB` fits the small lab volumes.
`wal_keep_size=128MB` replaces CNPG's 512MB default: the replication slots already
retain the WAL each standby needs, so a large extra floor only fills the 2 GiB volume.
These are not hard disk-usage caps: monitor free space and lag, and recover an
invalidated slot explicitly after an outage that exceeds the retained WAL budget.

After catalog and ordering migrations, run the read-only routing check on every inventory
primary:

```bash
bash microservices/shardshop/scripts/verify-topology.sh --routing-only
```

It compares PostgreSQL SHA-256 arithmetic and the deployed seller and buyer
placement constraints against the shared golden fixtures used by Java. It creates
no SQL objects and does not run a failure drill.

Run the reversible topology drill in this disposable lab (defaults to the first
inventory entry; pass another inventory name to drill that shard):

```bash
bash microservices/shardshop/scripts/verify-topology.sh
```

Each invocation checks every inventory shard's writable primary, quorum standbys,
independent database lineage, `-rw`/`-ro` Service endpoints and distinct Bound
PVCs/backing volumes, and runs the same read-only routing checks. Counts come from
the inventory. It then drills the selected shard (the first entry by default): a
probe row must reach both standbys, writes on standbys must fail, and the probe
schema must remain absent from peer shards. This failure drill requires the lab's
three instances per shard; changing the number of shards needs no script edit.
It checks failover-slot synchronization and PVC persistence, replaces one standby
pod, and temporarily fences one then both standbys to observe quorum commits.
Reads and writes are disrupted during the
drill. It restores the standbys and removes its own SQL objects on exit; it never
deletes a PVC. A global `shardshop-topology-drill` ConfigMap prevents concurrent
runs across shards, because every run checks the whole topology. If the process
is killed without a chance to clean up, first ensure no drill is running, remove the
`cnpg.io/fencedInstances` annotation from the drilled shard, wait for the cluster to
be Ready, and inspect the `cluster` and `probe` entries in that ConfigMap before
removing its SQL schema/slot and the ConfigMap.

## Separate ledger database (step 2.2)

[`clusters.yaml`](infra/helm/shardshop/templates/clusters.yaml) adds a separate
CloudNativePG cluster with one PostgreSQL instance, separate from the nine shard
pods. It uses the same pinned PostgreSQL image and its own 2 GiB PVC. The kind
values place it on a worker using `standard` storage; `up.sh` creates it and
waits for readiness alongside the shards. Its 250m CPU/512 MiB memory requests
and one-CPU/512 MiB limits match the tiny-data baseline. The ten databases now
request 5 GiB of memory in total; remeasure when workloads arrive.

Connect to database `ledger` through the internal Service
`ledger-db-rw.shardshop.svc:5432`. CNPG generates `ledger-db-app` at runtime for
bootstrap owner `ledger_owner`; application clients use the separate `ledger-app`
Secret from step 2.6. Secret values are never committed or printed. TLS clients
can mount only `ca.crt` from `ledger-db-ca` and use `sslmode=verify-full`. The bootstrap owner
owns only the database; `ledger_schema_owner` owns schema `ledger`, its objects,
and its Flyway history. Step 2.6 below describes its migrations and runtime grants.
No application is wired to the bootstrap owner. Like the shards, the cluster
revokes `TEMPORARY` and access to schema `public` from `PUBLIC` when it is created.

The ledger uses ordinary WAL and local durable commits, with no synchronous
standby requirement or CDC setup; its planned outbox relay polls its own tables.
It has **no database failover target**: ledger access stops during pod restarts
or worker outages. Its PVC preserves data across ordinary pod replacement, but
local-path storage cannot survive loss of its worker or volume and is not a
backup. Backup and restore arrive in step 6.3.

Inspect the database and its Service without exposing credentials:

```bash
kubectl --context kind-shardshop -n shardshop get cluster ledger-db
kubectl --context kind-shardshop -n shardshop get pods,pvc -l cnpg.io/cluster=ledger-db
kubectl --context kind-shardshop -n shardshop get service ledger-db-rw
kubectl --context kind-shardshop -n shardshop get endpointslices -l kubernetes.io/service-name=ledger-db-rw
```

Step 2.2's live checks use temporary restricted client Jobs, the generated Secret,
and TLS through that Service. They verify a committed row after replacing the
ledger pod, unchanged PVC/credential identities and startup reruns, then remove
their Jobs and SQL probe. The shard topology drill continues to target only the
nine shard instances. See PLAN step 2.2 for the verification evidence.

`kind delete cluster --name shardshop` removes the whole lab, including its
storage; kind storage is disposable and is not a backup.

## Database schemas and migrations (step 2.3)

No Maven module owns a schema: databases, roles and migrations belong to the
database layer in [ARCHITECTURE.md](ARCHITECTURE.md#database-change-management).
Keep all Flyway SQL simple and declarative: tables, constraints, indexes, grants,
and data changes only. Do not create SQL functions or triggers; use Java for
business logic and scripts for deployment checks.
The two scripts split the work:

- `up.sh` declares everything Kubernetes can: the clusters and their creation-time
  hardening, the schema roles, and each shard's `Database` resource,
  which creates schemas `catalog` and `ordering` with their respective owners.
  Its `Publication` resource selects only inserts into `ordering.order_outbox`;
  CNPG reconciles it after the migration creates the table. The shared
  [`databases.yaml`](infra/helm/shardshop/templates/databases.yaml) template
  renders the roles and `Database` for every inventory entry. The separate
  [`ledger-database.yaml`](infra/helm/shardshop/templates/ledger-database.yaml)
  declares `ledger-db-ledger`, schema `ledger`, and its four roles.
- `migrate.sh` fills the schemas by running each Flyway stream in
  [`database/`](database/) on every shard primary and then on ledger once. It builds
  and loads the migration image automatically; no Maven build, application JAR, or database
  superuser session is needed.

After `up.sh`, run:

```bash
bash microservices/shardshop/scripts/migrate.sh
```

After checking the published topology, `migrate.sh` invokes
[`build-migration-image.sh`](scripts/build-migration-image.sh) before creating SQL
ConfigMaps or migration Jobs. The helper uses Docker Buildx with pinned inputs and
`SOURCE_DATE_EPOCH=0`, loads the ARM64 image into kind, and checks its CRI manifest
digest on every node against the selected migration image pin. A build or digest
failure stops the migration. To prepare that image separately after `up.sh`, run:

```bash
bash microservices/shardshop/scripts/build-migration-image.sh
```

[`database/shard/catalog/`](database/shard/catalog/) is the catalog stream.
`V1__catalog.sql` creates all tables, including seller home regions, named region
and ID-placement checks, and regional comments. `R__catalog_grants.sql` holds the
complete privilege matrix; Flyway reapplies it whenever it changes. `flyway.toml` fixes the
schema, the history table `catalog.flyway_schema_history`, retries, and the 5-second connect, 30-second
socket, 15-second statement and 5-second lock timeouts. It also starts every
connection as `catalog_owner`, so the migrator login never owns an object.

The script runs the required `database/shard/` streams in the order `catalog`,
then `ordering`, followed by `database/ledger/ledger` once. For each shard stream it
loads the folder into the
`<stream>-migrations` ConfigMap, then runs one `<shard>-<stream>-migration` Job per
inventory entry from the shared
[`migration.yaml`](infra/helm/shardshop/templates/migration.yaml) template,
one shard at a time. Each Job uses the matching `<stream>-migrator` Secret and
stream component label. The template supplies the same `shardRegion`, `shardIndex`,
and `shardCount` Flyway placeholders for both streams from the inventory, and sets
`SHARD_NAME` and the shard's CA Secret. Kubernetes expands `FLYWAY_URL` using the
preceding environment variable. The ledger Job uses `ledger-migrations`,
`ledger-migrator`, `ledger-db-ca`, and the `ledger-db-rw` endpoint/database `ledger`,
without shard placement settings. Before each Job, the script waits for its
cluster and the Database's current-generation `applied` status. Each Job runs
Flyway OSS 13.8.1 on the pinned Canonical OpenJDK 25 runtime. Its
[`Dockerfile`](infra/images/flyway/Dockerfile) copies only Flyway's core and
PostgreSQL plugin libraries, the PostgreSQL JDBC driver and the licenses from the
pinned official distribution image;
its final base is `ubuntu/jre` on Ubuntu 26.04. The Job logs in as the stream's
migrator (`catalog_migrator` for catalog) over TLS with `verify-full` and the
cluster's CA. It has a 180-second deadline and no retry. The script prints its log
and stops at the first failure, leaving later Jobs unattempted and routing
unpublished; successful databases stay migrated because migrations are not a
transaction across databases.
Correct the failure and rerun. Retained versioned migrations are immutable; fix
forward with another version. Never repair checksums automatically, bypass
validation, or clean a catalog that holds data. Normal Flyway configuration keeps
`clean` disabled. The last run's Jobs and logs stay until the next run, which
replaces them. Run one `migrate.sh` at a time.

If a run stops partway through, retain the same regions, routing indexes, and
shard count on retry. The `shardshop-migration-topology` ConfigMap is created once
before any migration starts and must contain both `version` and `regionVersion`.
`up.sh` and `migrate.sh` reject an inventory that differs from this saved
reservation, even when no migrations remain. Keep the ConfigMap after partial
migration or failed publication and rerun with the same inventory. The published
`shardshop-routing` ConfigMap also requires both matching hashes. Export its routing
properties for local product/order launches after the first successful migration.
Attempts to reassign a published shard region are refused. The routing ConfigMap
is published only after all streams succeed and every outbox publication is
applied at its current resource generation:

```bash
kubectl --context kind-shardshop -n shardshop get jobs,pods -l app.kubernetes.io/name=schema-migration
kubectl --context kind-shardshop -n shardshop logs job/shard-a-catalog-migration
kubectl --context kind-shardshop -n shardshop get databaseroles,databases
```

The migration image and Job both select UID/GID 10001. The Job uses a
read-only root filesystem, `HOME=/tmp`, no service-account token, and telemetry
disabled. That fixed ID is an exception to the
[portability rules](PLAN.md#portability-beyond-kind), which OpenShift's assigned
UIDs would need to replace.

### Catalog roles and tables

| Role | Login | Privileges |
|---|---|---|
| `catalog_owner` | no | Owns schema `catalog`, its tables and history |
| `catalog_migrator` | yes | Member of `catalog_owner` without inheriting it; only Flyway uses it |
| `catalog_reader` | no | `SELECT` on sellers and products |
| `catalog_writer` | no | `SELECT` and `INSERT` on sellers and products |
| `catalog_reserver` | no | `SELECT`, `INSERT`, `UPDATE` and `DELETE` on stock reservations, plus `UPDATE (stock)` on products; no `TRUNCATE` |
| `product_app` | yes | Member of `catalog_writer` |

`up.sh` generates catalog login passwords into the Secrets `catalog-migrator` and
`product-app`, shared by all three shards; neither is committed or printed.
`order_app` joins `catalog_reader` and `catalog_reserver` as well as its ordering
group; its credentials use the separate `order-app` Secret.
Runtime logins do not own objects or have SQL privileges for DDL, temporary
tables or `SELECT` on the Flyway history.

`catalog.sellers` holds positive `BIGINT` seller IDs, names, and immutable
`US`/`EU`/`ASIA` regions. Its checks require the deployed region and the exact
shard selected by the unchanged version-2 SHA-256 rule. A `CHECK` expression
uses PostgreSQL's built-in SHA-256, hexadecimal encoding, exact `NUMERIC`
conversion, and modulo operator, preserving all 256 digest bits. No custom SQL
function, trigger, or function execution grant is needed. `catalog.products`
references its local seller and stores a positive `BIGINT` ID, a name of at most 200
characters (not empty or only spaces), a non-negative `NUMERIC(19,2)` price
(excluding NaN), an uppercase three-letter currency, and `initial_stock` and
`stock`, with `0 <= stock <= initial_stock`. The product service sets `stock` to
`initial_stock` when it creates a product. `catalog.stock_reservations` keeps one
row per order and product, with the quantity the buyer ordered. The order service
will reserve with `INSERT … ON CONFLICT (order_id, product_id) DO NOTHING` and,
only for a new reservation, conditionally decrement stock in the same Java-managed
shard transaction. Insufficient stock rolls back the reservation. Release will
mark an active reservation released and restore its quantity in one transaction;
retries must change stock at most once. There are no ID sequences. Products
inherit the seller's region and store no independent region column. The local
seller foreign key and placement checks prevent creating products on a different
region's shard. Runtime roles cannot change a seller's region.

The stock-column grant permits direct updates without allowing changes to product
names, prices or other columns. There is no stock trigger: reservation writes by
themselves do not change stock. Java reserve/release logic and its integration
tests remain planned in step 4.9. The database checks still enforce
`0 <= stock <= initial_stock`, but the application must keep stock and reservation
rows consistent.

The product application does not depend on Flyway, and its startup never migrates.
Implementation status and completed lab verification are recorded in PLAN.

## Ordering schema and catalog access (step 2.5)

[`database/shard/ordering/`](database/shard/ordering/) has its own V1, repeatable
grants and `flyway.toml`. It uses `ordering.flyway_schema_history` and runs as
`ordering_owner` through `ordering_migrator`, with the catalog stream's TLS,
timeouts and topology placeholders. No Java module or application startup migrates.

| Role | Login | Privileges |
|---|---|---|
| `ordering_owner` | no | Owns ordering schema, tables and history |
| `ordering_migrator` | yes | Non-inheriting owner membership, used only by Flyway |
| `ordering_writer` | no | Reads/inserts business records; updates status, reconciliation and publication metadata; deletes transport rows; reads/writes CDC offsets |
| `ordering_cdc_reader` | no | Ordinary SQL `SELECT` on `ordering.order_outbox` only |
| `order_app` | yes | `ordering_writer`, `catalog_reader`, `catalog_reserver` |
| `ordering_cdc` | yes | Replication attribute plus `ordering_cdc_reader`; highly privileged WAL access |

The additional Secrets are `ordering-migrator`, `order-app` and `ordering-cdc`.
`up.sh` creates each once and preserves it on reruns. It waits for every role's
current generation to be applied before checking the schemas. Ordinary SQL grants
give runtime logins no DDL, temporary-table, history or owner privileges. Product
has no ordering privileges; CDC has no SQL `SELECT` on catalog, other ordering
tables or offsets. Order can update only the `stock` column of catalog products
and cannot update sellers.

The `ordering-cdc` Secret is highly privileged: PostgreSQL's `REPLICATION`
attribute allows logical decoding beyond those SQL grants. The installed
`test_decoding` plugin can stream committed changes throughout `shardshop`
without consulting the outbox publication or table privileges. The dedicated
publication limits the configured `pgoutput` stream; it is not an access-control
boundary for this login. Restrict the Secret to the trusted CDC runtime and
operators, and treat its compromise as potential exposure of every shared shard's
database changes. See [PostgreSQL logical replication security](https://www.postgresql.org/docs/18/logical-replication-security.html).

Buyers have immutable home regions and the same exact version-2 placement check
as sellers. Allocations retain a unique `(buyer_id, run_name, request_ordinal)`
mapping; ordinals are nonnegative. Orders reference both their local buyer and
the allocation for that buyer. Items store immutable seller/product IDs, quantity,
name, unit price and currency snapshots, without cross-shard catalog foreign keys.
Orders and sagas start in `PENDING_STOCK`. Sagas retain cancellation/release state,
a zero-to-five reconciliation counter and the next eligible time (initially
creation plus 60 seconds).

`order_result_ids` permanently binds each order-issued command/result message pair
to its saga. Outbox and inbox foreign keys reject unreserved or wrong-saga result
IDs; deleting transport records leaves these bindings and the saga intact.
Runtime grants keep buyer, allocation, order/item payload and message identities
immutable. Outbox inserts cannot supply `created_at` or `published_at`: the
database assigns creation time and rows start unpublished. Only outbox publication
metadata can be updated. Java remains
responsible for atomic writes, valid state transitions, envelope validation,
stock reserve/release, and cleanup only after the retention and CDC checkpoint
gates; those behaviors are later milestones.

`orphan_results` and `conflicting_results` retain envelopes, correlation IDs,
fingerprints, outcomes, reasons and audit metadata. Their composite key is message
ID plus the SHA-256 fingerprint of the complete canonical received payload, so a
different payload with the same message ID stays visible. Neither store has
foreign keys that could prevent quarantine after data loss. The conflict store
also retains expected and received identity/outcome snapshots. Unresolved-order
indexes support the creation guard. Runtime may update delivery counts/times but
cannot delete incidents or resolve them; resolution is an operator action.
Column-level `INSERT` grants omit `resolved_at` and `resolution` on both stores,
so an orphan result cannot be inserted already resolved to bypass the order ID
reuse guard.

Each shard has CNPG Publication `<shard>-order-outbox`, SQL name
`ordering_order_outbox`, publishing only inserts into `ordering.order_outbox`.
`up.sh` may leave it pending until the table exists; `migrate.sh` waits for it
before publishing routing. CDC uses its own login; publication-status and offset
writes use `order_app`. The precreated `ordering.debezium_offset_storage` follows
the [Debezium JDBC offset-store column contract](https://debezium.io/documentation/reference/3.7/configuration/storage.html#_offset_table_defaults).
The future connector must set `offset.storage.jdbc.table.name` to that qualified
name and disable publication auto-creation; connector/library qualification is
still part of the CDC milestone.

Apply all streams and inspect ordering:

```bash
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/migrate.sh
bash microservices/shardshop/scripts/migrate.sh # unchanged histories: no pending migrations
bash microservices/shardshop/scripts/verify-topology.sh --routing-only
kubectl --context kind-shardshop -n shardshop get publications
kubectl --context kind-shardshop -n shardshop logs job/shard-a-ordering-migration
```

## Ledger schema and replay records (step 2.6)

[`database/ledger/ledger/`](database/ledger/ledger/) contains `V1__ledger.sql`,
the complete repeatable grants, and `flyway.toml`. Its history is
`ledger.flyway_schema_history`. Flyway logs in as `ledger_migrator` and starts
each connection as `ledger_schema_owner`, using the same TLS, retries, and session
timeouts as the shard streams. The ledger has no shard-placement constraints,
sequences, SQL functions, or triggers.

| Role | Login | Privileges |
|---|---|---|
| `ledger_owner` | yes, CNPG bootstrap | Owns database `ledger`; no application uses this role |
| `ledger_schema_owner` | no | Owns schema `ledger`, its tables and history |
| `ledger_migrator` | yes | Non-inheriting membership in `ledger_schema_owner`; only Flyway uses it |
| `ledger_writer` | no | Schema `USAGE`, table `SELECT`, and `INSERT` with the column restrictions below; only the listed updates/deletes |
| `ledger_app` | yes | Inherits `ledger_writer`; no owner membership |

`up.sh` creates `ledger-migrator` and `ledger-app` Secrets once and preserves
them on reruns. Four ledger `DatabaseRole` resources accompany the existing
bootstrap role; the default three-shard inventory therefore renders 40
`DatabaseRole` resources and four `Database` resources. Runtime has no DDL,
temporary-table, Flyway-history, or `TRUNCATE` privileges.

| Table in `ledger` | Stored data and constraints |
|---|---|
| `ledger_operations` | Permanent decision per order, unique saga/command IDs, buyer, fingerprint, immutable snapshot, amount/currency, outcome, rejection reason and saved result data |
| `ledger_entries` | One permanent row per recorded order; composite foreign key requires its amount, currency and timestamp to match a `RECORDED` decision, preventing an entry for a rejected order |
| `ledger_result_ids` | Permanent binding of order-reserved command/result message IDs to an operation, immutable result envelope and original timestamps, plus a durable `publication_attempt` |
| `ledger_inbox` | Disposable command deduplication rows; foreign key requires the saved command/result pair |
| `ledger_outbox` | Pending/confirmed result delivery metadata; `message_id` references the reserved result ID and its current publication attempt |
| `conflicting_commands` | Immutable conflicting envelope and expected/received identity snapshots, reason, delivery metadata and operator-resolution fields; no foreign keys obstruct quarantine |

`ledger_writer` may update only `ledger_result_ids.publication_attempt`,
`ledger_outbox.publication_attempt`/`published_at`, and
`conflicting_commands.last_seen_at`/`delivery_count`. It may delete only inbox and
outbox rows. Runtime cannot update or delete decisions or entries, delete permanent
message bindings, alter saved envelopes, delete quarantine records, or resolve an
existing incident. The binding's publication counter is its sole mutable field.
Column-level inserts into the outbox omit `created_at` and `published_at`; new
and recreated rows use database time and start unpublished. Command-quarantine
inserts omit `resolved_at` and `resolution`, so incidents start unresolved.
Quarantine is keyed by message ID plus the SHA-256 fingerprint of the complete
canonical received payload, preserving different conflicts sharing an ID.

The publisher joins `ledger_outbox.message_id` to
`ledger_result_ids.result_message_id` to load the saved envelope. A deferred
foreign key requires the outbox attempt to match the permanent binding at commit.
On a duplicate command, Java must atomically increment the durable counter and
reset the existing outbox row to pending or recreate it after cleanup, even on an
inbox hit. Publisher confirmation must compare both `message_id` and the sent
`publication_attempt` before setting `published_at`; a stale confirmation must
leave a newer attempt pending. Replays reuse the saved decision and envelope;
ledger never generates IDs or reevaluates policy for a decided order.

The SQL constraints and grants supply storage boundaries. Java command validation,
decision/entry atomicity, conflict detection, replay transactions, publisher
confirmation, and cleanup only after confirmed publication and retention remain
later steps. A DELETE grant does not enforce these cleanup gates. The ledger
application is still a skeleton and does not migrate at startup.
Decisions have no runtime `UPDATE` privilege, including for `FOR UPDATE`/`FOR KEY
SHARE` row locks. Step 4.4 must serialize with a transaction advisory lock or
insert-conflict handling followed by reading and comparing the saved decision.

Provision, migrate, and inspect the ledger from the repository root:

```bash
bash microservices/shardshop/scripts/render.sh migration ledger-db ledger
bash microservices/shardshop/scripts/up.sh
bash microservices/shardshop/scripts/migrate.sh
kubectl --context kind-shardshop -n shardshop get database ledger-db-ledger
kubectl --context kind-shardshop -n shardshop get databaseroles
kubectl --context kind-shardshop -n shardshop logs job/ledger-db-ledger-migration
bash microservices/shardshop/scripts/migrate.sh # unchanged histories: no pending migrations
```

The ledger Job must succeed before the script waits for shard publications and
publishes routing. Verification evidence and remaining work are tracked in PLAN.
