# Shard routing runbook

[ShardShop](../../README.md) › [Core](../README.md) › **Shard routing runbook** · [Architecture](ARCHITECTURE.md)

## Shard inventory and routing

[`infra/shards.yaml`](../../infra/shards.yaml) is the only maintained list of shards.
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

[`infra/helm/shardshop/`](../../infra/helm/shardshop) contains shared templates for
clusters, databases/roles, migration Jobs, and routing configuration. Common
settings live in `values.yaml`; kind storage/placement settings live in
`kind-values.yaml`. Helmfile syncs real Helm releases for their lifecycle,
preserving existing database names and PVCs. Namespace, credential Secrets and
allocator registry/identity remain outside Helm release ownership. There are no per-shard directories,
committed generated manifests, or per-shard Helm releases. The separate ledger
is excluded from the shard list. Names must start with a lowercase letter, contain
only lowercase letters, digits and hyphens, end in a letter/digit, and fit 44 characters.
The chart bounds the migration hook release prefix; stream, inventory index and
revision identify each attempt.
Empty/duplicate or malformed names, `ledger-db`, and missing/invalid regions fail
before deployment. Regions use only uppercase `US`, `EU`, and `ASIA`; a custom
inventory may assign multiple shards to one region. The chart adds the
`shardshop.javacraft/region` label to each shard Cluster and its inherited metadata.
These are business regions in the local lab, not separate geographic locations.

Preview without Kubernetes access using native Helm. These repository-root commands
use the same shared chart, environment values and ordered inventory as the lifecycle:

```bash
chart=microservices/shardshop/infra/helm/shardshop
environment=${SHARDSHOP_ENV_VALUES:-$chart/kind-values.yaml}
inventory=${SHARDSHOP_INVENTORY:-microservices/shardshop/infra/shards.yaml}
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=infrastructure
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=migration --set-literal migrationShard=shard-a --set-literal migrationStream=catalog
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=migration --set-literal migrationShard=shard-a --set-literal migrationStream=ordering
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=migration --set-literal migrationShard=ledger-db --set-literal migrationStream=ledger
helm template shardshop "$chart" --namespace shardshop -f "$environment" -f "$inventory" --set-string render=routing
```

The migration preview selects `migrationShard` and `migrationStream` explicitly.
Its Job, SQL ConfigMap, migrator Secret and component names follow the stream;
both shard streams receive `shardRegion`, `shardIndex` and `shardCount`. The ledger
stream has no shard placeholders or region labels. The chart rejects ledger
streams on shards and shard streams on `ledger-db`. Previews do not apply resources
or run migrations. `cluster-create.sh` provisions all three schemas and their roles;
`up.sh` runs catalog on every shard, then ordering on every shard, then ledger
once, and waits for current-generation ordering outbox publications before
activating routing.

For application or seeder previews, select `render=applications` or `render=seeding`
with the same Helm arguments, provide a valid 1–32 character `run` and each required
`images.<application>` digest reference using `--set-literal`. Normal deployments
use Helmfile's releases and revision-owned migration/seeder hooks through `up.sh`.
`SHARDSHOP_INVENTORY=/path/inventory.yaml` selects another inventory;
`SHARDSHOP_ENV_VALUES=/path/values.yaml` replaces the kind overrides.

`cluster-create.sh` provisions the declared shards and ledger. `up.sh` checks current-generation
cluster/database readiness, migrates sequentially, then publishes `shardshop-routing`.
That ConfigMap contains `application.properties` with aligned
`shardshop.routing.shards` and `shardshop.routing.regions` lists. Its `version` key
remains SHA-256 of the comma-joined UTF-8 names; `regionVersion` is SHA-256 of the
comma-joined UTF-8 `name=REGION` pairs in inventory order. Before running any
migrations, `up.sh` creates the immutable `shardshop-migration-topology` reservation
with both hashes and checks it against the invocation snapshot.
Both existing ConfigMaps must contain matching `version` and `regionVersion`
values. The scripts refuse missing hashes or a changed saved or published topology
or region mapping; absent ConfigMaps are allowed for initial bootstrap.
The saved reservation survives partial migrations and publication failures;
matching reruns reuse it. Publication is atomic;
a failed migration leaves the previous routing configuration intact. Startup and
migration snapshot the input inventory for the duration of each run.

Product and order import `/etc/shardshop/routing/application.properties` at startup;
future Deployments must mount the ConfigMap there. Local JAR launches copy
its properties to a file and set `SHARDSHOP_ROUTING_CONFIG`, as
[build and run](../../RUNBOOK.md#build-and-run) shows. The Java
library requires names and regions, rejects invalid entries or mismatched list
lengths, and keeps an immutable list of shards with their regions. It routes the
unsigned SHA-256 digest of a positive decimal ID modulo the list size.
Missing or invalid configuration fails startup. The initial three-shard vectors are
unchanged.
Applications do not watch cluster health or reload routing while running; a failed
primary remains on the same shard, behind the same `-rw` Service.

Adding a shard means adding one inventory entry; all corresponding resources and
routing settings are generated. Changing membership **or order** changes ownership
of existing IDs. `up.sh` refuses a list that differs from an already
published version; a changed published region assignment is also refused.
Schema V1 is independent of the unchanged version-2 ID-routing algorithm.

For a different topology, reset this disposable lab:

1. Stop the applications and workloads.
2. Retire all old backups, broker data and other replay inputs. The new lab gets a
   new generator registry, thus it can issue IDs that old data already uses
   ([generator identity](../idgen/ARCHITECTURE.md#generator-identity-across-processes-and-restarts)).
3. Run `bash microservices/shardshop/scripts/cluster-destroy.sh` to delete the lab
   and its data, volumes and kind kubeconfig.
4. Run `cluster-create.sh` to create the foundation and initialize the allocator
   after applying its admission guard, then `up.sh` to migrate, publish the new
   routing and seed the deployed release.
5. Export the new configuration for any separate local application launches.

Online data redistribution and rolling topology changes are outside this
implementation; deleting the routing ConfigMap alone would bypass the guard
without moving any data.
