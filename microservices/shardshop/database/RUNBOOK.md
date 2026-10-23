# Database runbook

[ShardShop](../README.md) › [Database](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

This runbook describes the shard clusters, the ledger database, the migration
workflow and the roles and tables of each schema. The [README](README.md) shows how
to monitor them.

## Shard clusters

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
passwords are committed or printed; the database layer provisions the catalog and
ordering roles.

Each database's memory limit is 512 MiB, reduced from 1 GiB after a measurement of
the tiny-data lab; requests stay at 512 MiB and 250m CPU, with a one-CPU
limit. The nine databases reserve 4.5 GiB in total. This measurement precedes the
ledger, broker and application workloads: retain the 12 GB VM minimum and
remeasure under load before treating these limits as a full-lab capacity budget.
After all three drills on 2026-09-29, the database pods used about 563 MiB of
working-set memory and the four kind nodes used 2.78 GiB. The
[changelog](../CHANGELOG.md#step-21) records the evidence. The estimate for the complete lab is 6-10 GiB in steady
state. Each kind node reports the full VM as its capacity, thus an overcommitted
lab shows OOM kills or swapping, not Pending pods.

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

## Separate ledger database

[`clusters.yaml`](../infra/helm/shardshop/templates/clusters.yaml) adds a separate
CloudNativePG cluster with one PostgreSQL instance, separate from the nine shard
pods. It uses the same pinned PostgreSQL image and its own 2 GiB PVC. The kind
values place it on a worker using `standard` storage; `cluster-create.sh` creates it and
waits for readiness alongside the shards. Its 250m CPU/512 MiB memory requests
and one-CPU/512 MiB limits match the tiny-data baseline. The ten databases now
request 5 GiB of memory in total; remeasure when workloads arrive.

Connect to database `ledger` through the internal Service
`ledger-db-rw.shardshop.svc:5432`. CNPG generates `ledger-db-app` at runtime for
bootstrap owner `ledger_owner`; application clients use the separate `ledger-app`
Secret. Secret values are never committed or printed. TLS clients
can mount only `ca.crt` from `ledger-db-ca` and use `sslmode=verify-full`. The bootstrap owner
owns only the database; `ledger_schema_owner` owns schema `ledger`, its objects,
and its Flyway history. The [ledger schema](#ledger-schema-and-replay-records)
section describes its migrations and runtime grants.
No application is wired to the bootstrap owner. Like the shards, the cluster
revokes `TEMPORARY` and access to schema `public` from `PUBLIC` when it is created.

The ledger uses ordinary WAL and local durable commits, with no synchronous
standby requirement or CDC setup; its planned outbox relay polls its own tables.
It has **no database failover target**: ledger access stops during pod restarts
or worker outages. Its PVC preserves data across ordinary pod replacement, but
local-path storage cannot survive loss of its worker or volume and is not a
backup. Backup and restore are planned in step 6.3.

`cluster-destroy.sh` removes the whole lab, including its storage and kind kubeconfig; kind storage is disposable and is not a backup.

## Database schemas and migrations

No Maven module owns a schema: databases, roles and migrations belong to the
database layer in [ARCHITECTURE.md](ARCHITECTURE.md#database-change-management).
Keep all Flyway SQL simple and declarative: tables, constraints, indexes, grants,
and data changes only. Do not create SQL functions or triggers; use Java for
business logic and scripts for deployment checks.
`cluster-create.sh` prepares and reconciles the database layer; `up.sh` checks it and runs migrations:

- `cluster-create.sh` applies the shared declarative infrastructure: the clusters and their creation-time
  hardening, the schema roles, and each shard's `Database` resource,
  which creates schemas `catalog` and `ordering` with their respective owners.
  Its `Publication` resource selects only inserts into `ordering.order_outbox`;
  CNPG reconciles it after the migration creates the table. The shared
  [`databases.yaml`](../infra/helm/shardshop/templates/databases.yaml) template
  renders the roles and `Database` for every inventory entry. The separate
  [`ledger-database.yaml`](../infra/helm/shardshop/templates/ledger-database.yaml)
  declares `ledger-db-ledger`, schema `ledger`, and its four roles.
- `up.sh`'s Flyway Jobs fill the schemas by running each stream in
  [`database/`](.) on every shard primary and then on ledger once. It uses
  the migration image that `up.sh` loaded; no separate Maven
  build, application JAR, or database superuser session is needed.

After `cluster-create.sh` prepares the foundation, the normal migration and
deployment command is:

```bash
bash microservices/shardshop/scripts/up.sh
```

Skaffold builds the migration image from its reviewed Dockerfile, pinned inputs
and `SOURCE_DATE_EPOCH=0`. `up.sh` scans it with the gate of the
[application images](../infra/RUNBOOK.md#application-images), then its native kind adapter loads the
ARM64 image and checks each node's loaded platform manifest digest against the chart image pin. A build, scan or digest failure stops it before it registers the
image. Every enabled image must have the verified loaded platform manifest digest on every node
before `up.sh` creates SQL ConfigMaps or migration Jobs or applies a new release.

[`database/shard/catalog/`](shard/catalog) is the catalog stream.
`V1__catalog.sql` creates all tables, including seller home regions, named region
and ID-placement checks, and regional comments. `R__catalog_grants.sql` holds the
complete privilege matrix; Flyway reapplies it whenever it changes. `flyway.toml` fixes the
schema, the history table `catalog.flyway_schema_history`, retries, and the 5-second connect, 30-second
socket, 15-second statement and 5-second lock timeouts. It also starts every
connection as `catalog_owner`, so the migrator login never owns an object.

The script runs the required `database/shard/` streams in the order `catalog`,
then `ordering`, followed by `database/ledger/ledger` once. For each shard stream it
renders the snapshotted files into an immutable
`<stream>-migrations-<content-hash>` ConfigMap, then runs one Job named from the
release, stream, inventory index and Helm revision per inventory entry from the
shared [`migrations.yaml`](../infra/helm/shardshop/templates/migrations.yaml) and
[`_migrations.tpl`](../infra/helm/shardshop/templates/_migrations.tpl) templates,
one shard at a time. Each Job uses the matching `<stream>-migrator` Secret and
stream component label. The template supplies the same `shardRegion`, `shardIndex`,
and `shardCount` Flyway placeholders for both streams from the inventory, and sets
`SHARD_NAME` and the shard's CA Secret. Kubernetes expands `FLYWAY_URL` using the
preceding environment variable. The ledger Job uses its content-addressed
ConfigMap, `ledger-migrator`, `ledger-db-ca`, and the `ledger-db-rw` endpoint/database `ledger`,
without shard placement settings. The migration readiness gates check the cluster
and Database's current-generation `applied` status before SQL runs. Each Job runs
Flyway OSS 13.8.1 on the pinned Canonical OpenJDK 25 runtime. Its
[`Dockerfile`](../infra/images/flyway/Dockerfile) copies only Flyway's core and
PostgreSQL plugin libraries, the PostgreSQL JDBC driver and the licenses from the
pinned official distribution image;
its final base is `ubuntu/jre` on Ubuntu 26.04. The Job logs in as the stream's
migrator (`catalog_migrator` for catalog) over TLS with `verify-full` and the
cluster's CA. It has a 180-second deadline and no automatic pod retry. Helm stops
at the first failed hook, leaving later Jobs and the new routing/application
release unattempted. Successful databases stay migrated because migrations are
not a transaction across databases.
Correct the failure and rerun. Retained versioned migrations are immutable; fix
forward with another version. Never repair checksums automatically, bypass
validation, or clean a catalog that holds data. Normal Flyway configuration keeps
`clean` disabled.

Every `helmfile sync` creates revision-named migration hooks in stream/inventory
order. Helm waits for each weighted hook, so later SQL cannot start after a failure.
Before the migration release, Maven checks current infrastructure, allocator state,
topology reservation and the absence of active, pending or terminating Flyway Jobs.
If a caller stops while SQL is running, inspect the retained Job and wait for its
terminal result. Also inspect the affected release's Helm status and history:

```bash
helm --kube-context kind-shardshop -n shardshop status shardshop-migrations
helm --kube-context kind-shardshop -n shardshop history shardshop-migrations
```

Use `shardshop-application` instead if its deployment/seeder operation was
interrupted. A killed Helm client can leave `pending-install` or `pending-upgrade`
release bookkeeping even after all hook Jobs terminate. The idle guard does not
clear that Helm operation. Confirm no lifecycle/Helm process or hook Job is active,
then recover the release bookkeeping explicitly for the observed status before
retrying `up.sh`. Inspect existing resources and preserve database/PVC/allocator
state during that recovery; do not blindly delete release records or uninstall the
release. Once release bookkeeping permits a new revision, Flyway validates again.

Flyway history is authoritative on every invocation. Unchanged streams validate
and perform a no-op; repeatable grants rerun when their checksum differs, including
an A-to-B-to-A change. There is no cache of completed migration Jobs or generic
comparison of desired and live Job specifications. Successful migration hooks are
deleted after Helm observes completion; failed hooks retain bounded diagnostic
availability until their default 24-hour TTL. Inspect a failed Job before it
expires. SQL snapshots are immutable and remain content-addressed ConfigMaps.

Helmfile does not automatically roll back or uninstall a failed release. Previously
committed SQL is retained: correct the problem and rerun, using fix-forward
versioned migrations where required. Never repair checksums automatically, delete
history or PVCs to force validation, or invoke Flyway clean on retained data.
Run migrations and application deployment through `up.sh` so the Helmfile gates stay in effect.

After correcting the failure and any pending Helm bookkeeping, rerun with the
same inventory.
[Shard inventory and routing](../shardshop-core/sharding/RUNBOOK.md#shard-inventory-and-routing) describes the topology
reservation, the routing publication and the export for local launches. Inspect
the migration resources:

The migration image and Job both select UID/GID 10001. The Job uses a
read-only root filesystem, `HOME=/tmp`, no service-account token, and telemetry
disabled. That fixed ID is an exception to the
[portability rules](../PLAN.md#portability-beyond-kind), which OpenShift's assigned
UIDs would need to replace.

### Catalog roles and tables

[ARCHITECTURE](ARCHITECTURE.md#roles) defines the catalog roles and their
memberships. `cluster-create.sh` generates catalog login passwords into the Secrets
`catalog-migrator` and `product-app`, shared by all three shards; neither is
committed or printed. The `order_app` credentials use the separate `order-app`
Secret.
Runtime logins do not own objects or have SQL privileges for DDL, temporary
tables or `SELECT` on the Flyway history.

The implemented V1 `catalog.sellers` holds positive `BIGINT` seller IDs, names, and immutable
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
reserves with `INSERT … ON CONFLICT (order_id, product_id) DO NOTHING` and,
only for a new reservation, conditionally decrement stock in the same Java-managed
shard transaction. Insufficient stock rolls back the reservation. Release
marks an active reservation released and restores its quantity in one transaction;
retries must change stock at most once. There are no ID sequences. Products
inherit the seller's region and store no independent region column. The local
seller foreign key and placement checks prevent creating products on a different
region's shard. Runtime roles cannot change a seller's region.

`V2__catalog_creation.sql` adds company-name, product-description, unit-cost and
creation-key storage, plus service-owned `seller_profits`
and immutable `profit_credits` identities. Legacy descriptions backfill from the
product name; legacy unit cost and initial USD/EUR balances are zero. Catalog V4
adds profit posting: `catalog_reserver` (and therefore `order_app`) may read and
insert immutable credit identities, initialize currencies at zero and update
only balance amounts. It cannot update or delete credits. The repeatable grants
revoke obsolete table and column permissions before reapplying the current matrix.
Catalog V5 adds a product/currency credit index, and the grants give
`catalog_writer` column-level `SELECT (product_id, currency, amount)` on credits
for the regional product page. Product can aggregate applied earnings without
reading order identities or changing credits.

The stock-column grant permits direct updates without allowing changes to product
names, prices or other columns. There is no stock trigger: reservation writes by
themselves do not change stock; the Java transaction protocol of order changes
both. The database checks also enforce
`0 <= stock <= initial_stock`, but the application must keep stock and reservation
rows consistent.

The product application does not depend on Flyway, and its startup never migrates.

## Ordering schema and catalog access

[`database/shard/ordering/`](shard/ordering) has its own V1, repeatable
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
`cluster-create.sh` creates each once; `up.sh` preserves it on reruns. Both wait for every role's
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
Ordering V2 adds buyer first name/surname, contact and address
fields, durable creation keys, and immutable unit-cost snapshots for confirmed
profit accounting; the existing V1 migration is unchanged. Orders and sagas start
in `PENDING_STOCK`. Sagas retain cancellation/release state,
a zero-to-five reconciliation counter and the next eligible time (initially
creation plus 60 seconds).

`order_result_ids` permanently binds each order-issued command/result message pair
to its saga. Outbox and inbox foreign keys reject unreserved or wrong-saga result
IDs; deleting transport records leaves these bindings and the saga intact.
Runtime grants keep buyer, allocation, order/item payload and message identities
immutable. Outbox inserts cannot supply `created_at` or `published_at`: the
database assigns creation time and rows start unpublished. Only outbox publication
metadata can be updated. Java implements the atomic acceptance writes, the state
transitions, envelope validation and stock reserve/release; cleanup is permitted
only after the retention and CDC checkpoint gates.

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
`up.sh` leaves it pending until the table exists, then requires its current
generation to be applied before publishing routing. CDC uses its own login; publication-status and offset
writes use `order_app`. The precreated `ordering.debezium_offset_storage` follows
the [Debezium JDBC offset-store column contract](https://debezium.io/documentation/reference/3.7/configuration/storage.html#_offset_table_defaults).
The future connector must set `offset.storage.jdbc.table.name` to that qualified
name and disable publication auto-creation; connector/library qualification is
still part of the CDC milestone.

## Ledger schema and replay records

[`database/ledger/ledger/`](ledger/ledger) contains `V1__ledger.sql`,
the migration `V2__permanent_command_envelopes.sql`, the complete
repeatable grants, and `flyway.toml`. Its history is
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

`cluster-create.sh` creates `ledger-migrator` and `ledger-app` Secrets once;
`up.sh` preserves them on reruns. Four ledger `DatabaseRole` resources accompany the existing
bootstrap role; the default three-shard inventory therefore renders 40
`DatabaseRole` resources and four `Database` resources. Runtime has no DDL,
temporary-table, Flyway-history, or `TRUNCATE` privileges.

| Table in `ledger` | Stored data and constraints |
|---|---|
| `ledger_operations` | Permanent decision per order, unique saga/command IDs, buyer, fingerprint, immutable snapshot, amount/currency, outcome, rejection reason and saved result data |
| `ledger_entries` | One permanent row per recorded order; composite foreign key requires its amount, currency and timestamp to match a `RECORDED` decision, preventing an entry for a rejected order |
| `ledger_result_ids` | Permanent binding of order-reserved command/result message IDs to an operation, immutable result envelope and original timestamps, the original command envelope (`command_envelope`, V2), plus a durable `publication_attempt` |
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
[ARCHITECTURE](../shardshop-order/ARCHITECTURE.md#reconciliation-and-replayed-outcomes) defines how
Java replays a duplicate command and confirms each publication attempt.

The SQL constraints and grants supply storage boundaries. Java implements command
validation, decision/entry atomicity, conflict detection and replay transactions.
The result publisher confirms each publication and marks only its matching
attempt. Cleanup after confirmed publication and retention is planned in step 5.2.
A DELETE grant does not enforce these cleanup gates. The ledger application does
not migrate at startup.
Decisions have no runtime `UPDATE` privilege, including for `FOR UPDATE`/`FOR KEY
SHARE` row locks. The consumer serializes with transaction advisory locks before
reading and comparing the saved decision.

The ledger Job must succeed before `up.sh` waits for shard publications and
publishes routing.
