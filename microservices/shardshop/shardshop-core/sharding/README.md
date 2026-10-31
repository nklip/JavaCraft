# shardshop-sharding

[ShardShop](../../README.md) › [Core](../README.md) › **Shard routing**

`shardshop-sharding` maps a seller or buyer ID to its home shard. Product and order route through it directly.
There is no router service, and no other module computes routes.

- **Owns:** the version-2 routing rule, the shard topology and the routing golden-vector tests. The
  `RoutingConfiguration` mapping, the `ShardRoutingConfiguration` CDI producer and the injection tests are in
  `dev.nklip.javacraft.shardshop.sharding.config`.
- **Depends on:** `shardshop-common` for digest calculation.
- **Used by:** product and order only.

## Design

### Version-2 routing and topology

#### Routing rule

Sellers and buyers use the same routing rule with their own Snowflake IDs. A seller creates products only on
its home-region shard, and the ID of a product does not determine its placement. The products and stock
reservations of a seller stay on the shard of the seller, so the catalog routes by `seller_id`. The orders of a
buyer, with their items, sagas and transport records, are on the shard of the buyer, so ordering routes by
`buyer_id`:

```text
digestInteger(id) = unsignedBigEndian(SHA-256(UTF-8(canonical decimal Snowflake ID)))
shard(id) = deployedShards[digestInteger(id) % deployedShards.size]
initial deployedShards = [shard-a, shard-b, shard-c]
initial regions = [US, EU, ASIA]
homeRegion(id) = shard(id).region
```

- Use the validated canonical decimal ID text, with no newline or leading zeros, before UTF-8 encoding.
- Interpret all 32 SHA-256 digest bytes as one unsigned 256-bit **big-endian** integer: byte 0 is the most
  significant, byte 31 the least significant. Do not use a signed integer, truncate the digest or use Java
  `hashCode()`.
- Routing and currency selection both use `Sha256.unsignedDigest` of `shardshop-common` after they validate the
  ID. The order producer uses the order ID digest modulo 100 only in the explicit USD/EUR test mode. Normal
  traffic uses the region of the buyer definition and its weighted schedule. Neither mode depends on
  `shardshop-sharding` or computes routing from an ID.

This is identifier and routing contract **version 2**. The hash contract is immutable, and the initial ordered
inventory preserves all version-2 routing vectors. Generator IDs are independent of database shard IDs.

#### Inventory and routing snapshot

`infra/shards.yaml` defines ordered entries with `name` and `region`. A custom inventory can have multiple
shards in the same region, and the IDs still select the exact shard through the unchanged hash rule. Shared
Helm templates generate all resources and a routing snapshot. Its `version` is the SHA-256 of the comma-joined
UTF-8 names. Its `regionVersion` is the SHA-256 of the comma-joined UTF-8 `name=REGION` pairs in the same
order. The chart schema validates the names and the `US`, `EU` and `ASIA` enum.

`up.sh` publishes the snapshot as the `shardshop-routing` ConfigMap only after every deployed shard and the
separate ledger pass their migrations and current-generation outbox publications. Product and order import the
aligned `shardshop.routing.shards` and `shardshop.routing.regions` lists at startup and construct an immutable
`ShardTopology` with `fromNamesAndRegions`. Missing, empty or duplicate names, invalid regions and different
list lengths fail the startup. All replicas must start with the same snapshot.

#### Topology changes

Topology membership is not a health check. Outages and primary promotion never remove a shard from the list.
Processes do not refresh the topology while they run. A change of membership or order reassigns existing IDs,
so deployment and migration refuse a change to a published list until the lab is reset and reseeded. A change
of the region of an existing shard also fails the published `regionVersion` guard.

Before it starts migrations, `up.sh` reserves both version hashes in the immutable
`shardshop-migration-topology` ConfigMap. It compares the inventory with this saved topology and with the
published routing snapshot, also when no versioned migration is pending. Both existing ConfigMaps must contain
matching `version` and `regionVersion` values. Absent snapshots are allowed for the initial bootstrap. The
reservation survives a partial migration or a failed first publication, and matching reruns reuse it. Keep this
ConfigMap while you keep the dataset, because the checksums of versioned SQL do not detect changed Flyway
placeholder values.

To keep populated data across topology changes, a separate migration and cutover protocol is necessary. Never
probe all shards as a fallback. Freeze the hash rule and the published inventory, because a changed divisor
needs a data migration strategy.

### Data placement

| Data | Routing key | Writer / owner |
|---|---|---|
| `catalog.sellers` | `seller_id` | Product service |
| `catalog.products` | the product's `seller_id` | Product service creates them. The order service reads them and updates only `stock` in its reservation transactions |
| `catalog.stock_reservations` | the product's `seller_id` | Order service, which writes reservations and adjusts product stock in the same shard transaction |
| `catalog.seller_profits` | `seller_id` | Product service inserts zero balances for the seller policy currencies. The order service applies confirmed credits |
| `catalog.profit_credits` | the product's `seller_id` | Order service inserts one immutable credit for each order and product |
| `ordering.buyers` | `buyer_id` | Order service |
| `ordering.order_allocations` | `buyer_id` | Order service. Permanent allocation retry mappings |
| `ordering.orders`, `ordering.order_items` | the order's `buyer_id` | Order service |
| `ordering.order_sagas`, `ordering.order_outbox`, `ordering.order_inbox` | the order's `buyer_id` | Order service. Colocated with the order |
| `ordering.order_result_ids` | the order's `buyer_id` | Order service. Permanent command and result message bindings survive transport cleanup |
| `ordering.profit_intents` | the order's `buyer_id` | Order service. Confirmed item margins wait for idempotent application on seller shards |
| `ordering.orphan_results` | the order's `buyer_id` | Order service. Quarantine for a result whose order is missing |
| `ordering.conflicting_results` | the order's `buyer_id` | Order service. Quarantine for a result that conflicts with a saved identity or outcome |
| `ledger.ledger_entries`, `ledger.ledger_operations`, `ledger.ledger_result_ids`, `ledger.ledger_inbox`, `ledger.ledger_outbox` | No sharding | Ledger service, in its separate database |
| `ledger.conflicting_commands` | No sharding | Ledger service. Quarantine for a command that conflicts with a permanent decision |

### Cross-shard rules

- Order items stay on the shard of their order, also when the products are on other shards. A buyer in US, EU
  or ASIA can buy from sellers in any region, and one order can mix sellers whose product currency matches.
- The region and country of the seller determine the permitted product prices. The location of the buyer sets no
  currency restriction. The same-currency and stock rules still apply.
- Each item names its seller, product and quantity. Resolve them against the primary of the seller before the
  order is created, and persist server-derived product and price snapshots in the items. Product details are
  immutable after creation. Only `stock` changes, through the
  [reservation step](../../shardshop-order/ARCHITECTURE.md#stock-reservation).
- There is no payment and no cross-shard transaction. Foreign keys stay inside one shard (products to sellers,
  reservations to products, orders to buyers, items to orders). Order items have no foreign key to products.
- Product and order IDs are unique within a shard by constraint and across shards by Snowflake generation. The
  API always addresses a product together with its seller and an order together with its buyer.
- Select the shard before you open a transaction, and keep it until commit. Order creation and the initial saga
  state commit together on one primary. After stock reservation, the transition to `PENDING_LEDGER` and its
  outgoing command commit together on that primary.
- The order CDC relay captures committed outbox inserts from the WAL of every shard. It does not poll for
  unpublished rows.
- Public APIs expose decimal-string Snowflake business IDs and no shard selectors. Catalog calls carry the
  seller ID and order calls the buyer ID, and the services derive the shard from them.

### Related contracts

Product owns its [creation-key placement and read profiles](../../shardshop-product/ARCHITECTURE.md). Order
owns allocation and acceptance. The [datasets](../../shardshop-workload/shardshop-datasets/README.md) contain
definitions and never routing implementations.

## Operation

### Shard inventory and routing

[`infra/shards.yaml`](../../infra/shards.yaml) is the only maintained list of shards. Its order defines the
routing indexes, and each entry has an immutable home region. The initial inventory is:

```yaml
shards:
  - name: shard-a
    region: US
  - name: shard-b
    region: EU
  - name: shard-c
    region: ASIA
```

[`infra/helm/shardshop/`](../../infra/helm/shardshop) has shared templates for clusters, databases and roles,
migration Jobs and routing configuration. Common settings are in `values.yaml`. The kind storage and placement
settings are in `kind-values.yaml`. Helmfile syncs real Helm releases for their lifecycle and keeps existing
database names and PVCs. The namespace, the credential Secrets and the allocator registry and identity are
outside the Helm release ownership. There are no per-shard directories, committed generated manifests or
per-shard Helm releases. The separate ledger is not in the shard list.

- A name starts with a lowercase letter, contains only lowercase letters, digits and hyphens, ends in a letter
  or digit, and has at most 44 characters.
- Empty, duplicate or malformed names, `ledger-db`, and missing or invalid regions fail before deployment.
- Regions are only uppercase `US`, `EU` and `ASIA`. A custom inventory can assign multiple shards to one
  region. The chart adds the label `shardshop.javacraft/region` to each shard Cluster and its inherited
  metadata. The regions are business regions in the local lab, not separate geographic locations.
- The chart bounds the migration hook release prefix. Stream, inventory index and revision identify each attempt.

Preview without Kubernetes access with native Helm. These commands use the same shared chart, environment
values and ordered inventory as the lifecycle:

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

The migration preview selects `migrationShard` and `migrationStream` explicitly. The Job, SQL ConfigMap,
migrator Secret and component names follow the stream. Both shard streams receive `shardRegion`, `shardIndex`
and `shardCount`. The ledger stream has no shard placeholders or region labels. The chart rejects ledger streams
on shards and shard streams on `ledger-db`. Previews do not apply resources or run migrations.

For an application or seeder preview, select `render=applications` or `render=seeding` with the same Helm
arguments. Provide a valid 1-32 character `run` and each necessary `images.<application>` digest reference with
`--set-literal`. Normal deployments use the Helmfile releases and the revision-owned migration and seeder hooks
through `up.sh`. `SHARDSHOP_INVENTORY=/path/inventory.yaml` selects another inventory.
`SHARDSHOP_ENV_VALUES=/path/values.yaml` replaces the kind overrides.

`cluster-create.sh` provisions all three schemas and their roles for the declared shards and the ledger. `up.sh`
checks the current-generation cluster and database readiness and runs catalog on every shard, then ordering on
every shard, then ledger once. It waits for the current-generation ordering outbox publications, then publishes
`shardshop-routing`. The ConfigMap contains `application.properties` with the aligned
`shardshop.routing.shards` and `shardshop.routing.regions` lists, and the `version` and `regionVersion` keys
([hashes](#inventory-and-routing-snapshot)).

The scripts refuse missing hashes and a changed saved or published topology or region mapping
([topology changes](#topology-changes)). Publication is atomic, and a failed migration leaves the previous
routing configuration intact. Startup and migration snapshot the input inventory for the duration of each run.

Product and order import `/etc/shardshop/routing/application.properties` at startup, so future Deployments must
mount the ConfigMap there. Local JAR launches copy its properties to a file and set `SHARDSHOP_ROUTING_CONFIG`,
as [build and run](../../RUNBOOK.md#build-and-run) shows. The Java library requires names and regions, rejects
invalid entries and mismatched list lengths, and keeps an immutable list of shards with their regions. It
routes the unsigned SHA-256 digest of a positive decimal ID modulo the list size. Missing or invalid
configuration fails the startup. Applications do not watch the cluster health or reload routing while they run.
A failed primary stays on the same shard, behind the same `-rw` Service.

To add a shard, add one inventory entry. All corresponding resources and routing settings are generated.
A change of membership or order changes the ownership of existing IDs, and `up.sh` refuses a list that differs
from a published version. Schema V1 is independent of the version-2 ID-routing algorithm.

To use a different topology, reset this disposable lab:

1. Stop the applications and workloads.
2. Retire all old backups, broker data and other replay inputs. The new lab gets a new generator registry, so it
   can issue IDs that old data already uses
   ([generator identity](../idgen/README.md#generator-identity-across-processes-and-restarts)).
3. Run `bash microservices/shardshop/scripts/cluster-destroy.sh` to delete the lab and its data, volumes and
   kind kubeconfig.
4. Run `cluster-create.sh` to create the foundation and to initialize the allocator after it applies the
   admission guard. Then run `up.sh` to migrate, publish the new routing and seed the deployed release.
5. Export the new configuration for any separate local application launches.

Online data redistribution and rolling topology changes are not available. A deleted routing ConfigMap alone
bypasses the guard and moves no data.
