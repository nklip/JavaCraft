# ShardShop decisions

This file records the main decisions of ShardShop and the reason for each one.
Each record gives the decision, the reason and the result for the system. The
linked [CHANGELOG](CHANGELOG.md) entry has the evidence. The Git tag
`shardshop-docs-full-2026-10-31` has the full history.

## Scope and platform

### Local lab first, cloud optional

**Decision.** Milestones 0–6 run and are verified only on one machine: on a kind
lab with four nodes or in local containers. AWS is the optional milestone 7.

**Reason.** A cloud target must add an environment, not change the local
behavior.

**Result.** A local step never depends on a cloud service, and step 6.9 must
still pass locally after the cloud work ([plan](PLAN.md#milestone-7-aws-deployment-optional)).

### Community releases with regular upgrades

**Decision.** Use long-supported releases where a project offers them, and
maintained stable GA releases elsewhere. Do not use prereleases, floating tags,
external snapshots or paid extended support. There are two exceptions: the
pre-1.0 Snowflake library, which the user selected explicitly, and three
plugin-only XML prereleases of the Quarkus plugin.

**Reason.** Commercial extended support is outside the scope of the lab.

**Result.** Each component has a review date, and the user approves each update
([update rules](VERSIONS.md#update-rules)).

### Quarkus LTS in JVM mode

**Decision.** The six applications use the Quarkus 3.40 LTS line in JVM mode with
`fast-jar` packaging on Java 25. There is no native compilation.

**Reason.** The LTS line agrees with the release policy.

**Result.** These rules apply to all application code:

- Import the Quarkus platform BOM. Configure augmentation only for the six
  applications.
- `common` has no Quarkus dependency. The domain classes of `idgen` and
  `sharding` have no framework imports.
- Use constructor injection, SmallRye Config and the Quarkus lifecycle APIs.
- Use explicit JDBC transactions on one selected shard with bounded pools. Keep
  blocking IO off event-loop threads.
- Use the RabbitMQ Java client behind module-owned adapters. They keep mandatory
  routing, returns, publisher confirms and the acknowledgement order.
- Test domain and routing code with plain JUnit and Mockito, and startup and CDI
  with `quarkus-junit`. Also test the packaged launch. Dev Services stay disabled.

### Canonical OpenJDK runtime in the images

**Decision.** The application and migration images use the Canonical OpenJDK 25
runtime of the `ubuntu/jre` stable track on Ubuntu 26.04, pinned by digest. They
never use a Temurin runtime. The runtime image has no shell.

**Reason.** Canonical advertises support for these tracks through May 2031.

**Result.** A Java patch reaches the images only when Canonical publishes it for
Ubuntu 26.04. On 2026-10-31 the October 2026 Java fixes were not published, and
the user accepted that risk until 2026-11-30
([open findings](VERSIONS.md#open-findings-and-risk-acceptance)).

## Data and consistency

### Regional shards with quorum replication

**Decision.** Three regional shards (`shard-a=US`, `shard-b=EU`, `shard-c=ASIA`)
and a separate ledger database. Each shard is a CloudNativePG cluster with three
instances. A commit is acknowledged when one of the two standbys has flushed it
(`ANY 1`). Quorum-based failover promotes a standby only when the operator can
confirm that it has every synchronously committed transaction.

**Reason.** A shard can lose one instance without the loss of acknowledged
commits and without blocked writes.

**Result.** Each write waits for a standby flush. When the operator cannot confirm
a current standby, the shard waits for an operator decision
([failover drills](CHANGELOG.md#step-62)).

### Local transactions and a saga

**Decision.** Each write is a local transaction in one database. The order saga
connects the shards and the ledger through CDC, RabbitMQ and compensation:

- Order writes the order and an outbox row in one transaction.
- Debezium Engine, embedded in the order saga process, reads the WAL of each
  shard (`pgoutput`, one reader for each shard, offsets in the shard database)
  and publishes the ledger commands.
- The ledger records a permanent decision and publishes the result, and order
  applies it.
- One order image runs two processes: two HTTP replicas, and one saga replica
  that owns the workers, the CDC publication and the result consumer.

**Reason.** ShardShop has no distributed transaction.

**Result.** The order outcome is eventual: its p95 was 1.5–2.2 s at 10–30 orders/s
([step 6.6](CHANGELOG.md#step-66)). Delivery is at least once, thus each handler is
idempotent, and reconciliation recovers lost results. Each CDC reader sends a
heartbeat every 10 seconds: without it, an idle outbox never confirmed WAL, and
the slot could pass `max_slot_wal_keep_size` (512 MB) ([step 4.4](CHANGELOG.md#step-44)).

### Shard-concurrent stock and profit workers

**Decision.** The stock and profit workers process the buyer-home shards
concurrently, with one lane for each shard. The default is 3. The value 1 keeps
the serial scan.

**Reason.** In isolated A/B runs, the stock stage increased from 40.60 to 57.99
orders/s (+42.8%), and the profit stage from 182.29 to 299.16 intents/s (+64.1%).

**Result.** Each shard batch and the operations of each order stay sequential
([step 6.5](CHANGELOG.md#step-65)).

### Snowflake IDs from product and order only

**Decision.** Only product and order create IDs, with the pinned
`de.mkammerer.snowflake-id:snowflake-id` library in their own JVM. At each start,
the JVM reserves a generator ID with one compare-and-set patch of the registry
ConfigMap through the Kubernetes API, with Apache HttpClient. Admission rules
protect the registry. Workloads get IDs only from the APIs.

**Reason.** The user selected the library explicitly. The Java client removed
the kubectl stage of the images and its advisories.

**Result.** Each new product or order JVM uses a new generator slot. IDs are
decimal strings on the wire and positive `BIGINT` values in PostgreSQL
([step 3.11](CHANGELOG.md#step-311)).

### Strict replica reads without primary fallback

**Decision.** A read with the replica profile uses only the standbys (`-ro`).
When no standby answers, it returns 503 within the four-second server budget. It
never falls back to a primary.

**Reason.** Acceptance scenarios 3 and 8 require explicit stale reads and no
primary fallback ([scenarios](shardshop-testing/ARCHITECTURE.md#acceptance-scenarios)).

**Result.** Product reads use the primaries (`-rw`) by default. The replica
profile is an optional load-test profile that permits stale or temporarily
missing products ([step 6.1](CHANGELOG.md#step-61)).

### Coordinated logical backups and isolated restores

**Decision.** A backup quiesces the writes, drains the saga and relay work, and
takes logical dumps of the shards and the ledger, with the broker definitions and
the allocator history. The artifacts stay outside kind. A restore goes only to an
isolated target. A restore from an older backup needs an operator audit and an
explicit repair.

**Reason.** The backup must keep the schema and routing metadata, the permanent
outcomes, the quarantine, the replay identities and the latest allocator
high-water mark.

**Result.** A restore bundle authorizes no production cutover
(`trafficAuthorized: false`). See the
[backup policy](database/ARCHITECTURE.md#replication-and-backup-policy) and
[step 6.4](CHANGELOG.md#step-64).

## Build, test and delivery

### Tool ownership

**Decision.** Each behavior has an owning tool. A script is the last choice:

- **Skaffold:** image artifacts, Docker build recipes and tags.
- **Helmfile:** release order, sync stages, timeouts and Maven hooks.
- **Helm:** release resources, Flyway and seeder hooks, and configuration.
- **Kubernetes and CloudNativePG:** reconciliation, restarts, readiness, Secrets,
  admission rules, database roles, databases and publications.
- **Flyway Jobs:** the database history and the migrations.
- **Maven:** builds, and the Java and Cucumber suites.
- **Bash:** the four lifecycle scripts and `webpages.sh`, only for snapshots,
  locks, the Scout scan, native kind and Docker commands, credential creation and
  the fresh allocator bootstrap.

**Reason.** Native tools give the same guarantees with less custom code. When
`down.sh` and `cluster-destroy.sh` changed to `docker stop` and
`kind delete cluster`, 21 of the 30 tests of the old scripts failed: they checked
only the removed logic ([step 4.3](CHANGELOG.md#step-43)).

**Result.** `cluster-create.sh` owns the infrastructure, and `up.sh` owns the
applications, as the user requested. Each `up.sh` restarts the application pods.
The final acceptance gate is a Helmfile `postsync` hook, because Helm reports
`deployed` also when the acceptance fails ([step 3.17](CHANGELOG.md#step-317)).

### Reproducible images with digest tags

**Decision.** The build sets `project.build.outputTimestamp`, and `up.sh` tags
each image that it loads into kind as `sha256-<digest>` on each node.

**Reason.** Two clean builds of the same sources gave different JAR files, thus
each `up.sh` loaded new images. After `up.sh` moved the `:local` tags, the CRI did
not find the earlier images and failed with `CreateContainerError`
([step 3.13](CHANGELOG.md#step-313)).

**Result.** An unchanged build uses the cached image layers again, and a pod of an
earlier image can still start.

### Tests in Maven

**Decision.** Test Java behavior only with Maven: unit tests, the `integration`
profile with Testcontainers and pinned images, the Cucumber scenarios of
`shardshop-testing`, and the opt-in `kind` profile on the lab. The allocation
scenarios use an unprivileged k3s container as the Kubernetes API. Testcontainers
runs without Ryuk, and a JVM shutdown hook removes each container.

**Reason.** The user selected a Cucumber test module, as in the other projects of
the repository. The k3s scenarios use no lab slots. Ryuk publishes its port on all
host interfaces, runs privileged with the Docker socket, and had 4 critical and 29
high advisories ([step 3.10](CHANGELOG.md#step-310)).

**Result.** There is no Bash or Python check of Java behavior.

### Release gates independent of workloads

**Decision.** The `up.sh` gates create their own buyer, seller and products. They
never depend on the orders of the workload producer.

**Reason.** The user decided it. Confirmed producer orders use the seeded stock
permanently: approximately 16 h of producer run time empties the 50,000 USD units
of a region ([step 4.9](CHANGELOG.md#step-49)).

**Result.** A gate passes or fails on its own data, also after a long producer run.

## Operations and security

### Image scan gate and explicit risk acceptance

**Decision.** `up.sh` scans every built image with Docker Scout before it changes
the cluster. A critical or high finding stops the run, unless a reviewed VEX
statement covers it. The user accepts each remaining risk explicitly, with an end
date.

**Reason.** No image reaches the cluster before its scan.

**Result.** The open findings and the current risk acceptance are in
[VERSIONS.md](VERSIONS.md#open-findings-and-risk-acceptance).

### Manual reconciliation reset as SQL

**Decision.** The manual reset is the database-owned
[`reset-reconciliation.sql`](database/operations/reset-reconciliation.sql), run
with `psql`. It takes one explicit order and saga ID pair. There is no wildcard
reset, shell entry point, HTTP endpoint or additional process.

**Reason.** The operator role has only the reads that the operation needs and
updates of the two retry columns. It cannot read payloads or change terminal
decisions.

**Result.** The checked-in operation keeps the pending-state protection; the
column grants alone cannot prevent ad hoc changes ([step 5.3](CHANGELOG.md#step-53)).

### Keep HTTP/1.1

**Decision.** On 2026-10-30 the user kept HTTP/1.1 without a request balancer. The
h2c and standalone Envoy implementation of step 6.8 is in a local Git stash.

**Reason.** h2c through Envoy gave no gain:

- With equal settings and 16 reader streams, reads passed up to 6,000/s, against
  8,000/s with HTTP/1.1.
- With 32 streams, h2c also passed 8,000/s, but its read p95 at 8,000/s was 5 ms,
  against 2 ms.
- Envoy added two pods, one Envoy pod got all connections, and Envoy had no drain
  on SIGTERM.
- The four workload clients use cleartext in the cluster, on a network with less
  than 1 ms latency. Thus fewer connections, no TLS handshakes and multiplexing
  give nothing.

**Result.** The applications and the workloads keep HTTP/1.1
([step 6.8](CHANGELOG.md#step-68)).
