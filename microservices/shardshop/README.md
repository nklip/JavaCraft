# ShardShop

[Architecture](ARCHITECTURE.md) · [Implementation plan](PLAN.md) ·
[Version policy](VERSIONS.md) · [Artifact lock](versions.lock.yaml)

Steps 0.2–0.4 supply six Spring Boot application skeletons, their build/test
configuration, and the shared shard-routing library. Each application starts a minimal
application context and exits; no HTTP server, database connection, messaging,
seeding, or load generation is implemented yet. No Docker or Kubernetes is
needed to build or run these skeletons.

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

## Java and Maven

Set `JAVA_HOME` to a JDK **25 or newer**. The SDKMAN `25-open` installation
works; on this machine it reports `25+36-3489`. For example:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25-open"
```

The lock records an official macOS arm64 OpenJDK 25 archive and its SHA-256 as a
reference download. The build has no vendor or exact-patch restriction. Use the
same JDK's `bin/java` to launch the packaged applications.

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
"$JAVA_HOME/bin/java" -jar microservices/shardshop/shardshop-product/target/shardshop-product-1.0-SNAPSHOT.jar
```

Verify all six one at a time, without installing anything:

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

## Local Kubernetes cluster, operator and first shard (steps 1.1–1.3)

The lab runs on kind, and everything through the final acceptance run works on
this one machine; the AWS track with Terraform (PLAN milestone 7) is optional and
comes later. Take `docker`, `kind`, `kubectl`, and `helm` from your `PATH`, like
Maven; this project never downloads or pins tool binaries. It was tested with
Docker Desktop 4.92.0, kind 0.33.0, kubectl 1.36, and Helm 4.3.0 (for example,
`brew install kind kubectl helm`). The node image's advisories were reviewed on
2026-09-28 and accepted for this loopback-only, disposable lab. Give Docker
Desktop's VM at least 12 GB of memory (Settings → Resources); `up.sh` stops early
if it has less.

[`infra/kind.yaml`](infra/kind.yaml) defines one control plane and three workers,
one per instance of each three-instance shard, on the digest-pinned Kubernetes
1.36.4 node image, with the API server on 127.0.0.1. kind cannot add nodes, so a
cluster created with fewer workers must be deleted and recreated once.
Manifests follow the [portability rules](PLAN.md#portability-beyond-kind): a
common base in `infra/k8s/base/` and the kind overlay in `infra/k8s/overlays/kind/`.

Create or reuse the cluster from the repository root:

```bash
bash microservices/shardshop/scripts/up.sh
```

It creates the `shardshop` cluster if it does not exist and waits for all nodes to
be Ready. It then installs CloudNativePG 1.30.1 from its vendored chart with
`helm upgrade --install`, waiting up to 3 minutes for the operator, waits up to 180
seconds for every CRD to be Established, and applies the kind overlay. That creates
the `shardshop` namespace with the `restricted` Pod Security label and the
three-instance `shard-a` cluster, and the script waits up to 300 seconds for every
database cluster to be Ready. A fresh run takes about five minutes, mostly image
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
kubectl --context kind-shardshop -n shardshop get clusters,pods,pvc
```

[`infra/helm/cnpg/`](infra/helm/cnpg/) holds the official CloudNativePG chart
0.29.1, verified against the lock's SHA-256 and its cosign signature, and
`values.yaml`, which pins the operator by tag and digest. The chart uses that one
reference for both the operator image and `OPERATOR_IMAGE_NAME`, and it keeps its
CRDs if the release is ever uninstalled, so the database clusters survive. The
operator installs before the application overlay so its APIs and webhooks are
ready for database resources. To upgrade, review the lock's support deadline, then
vendor and verify the new chart and update `values.yaml` and the lock together;
a later cloud target installs the same chart and values.

`shard-a` runs PostgreSQL 18.6 with one primary and two standbys, each on a separate
worker with its own 2 GiB PVC. Its version tag lets CNPG identify PostgreSQL
upgrades; the accompanying digest pins the image bytes. The base declares the
database, resources and replication settings; the kind overlay patches every CNPG
`Cluster` to select `standard` storage and require placement on distinct workers,
so later shards need no overlay changes. No pod security context is
hard-coded. CNPG generates the `shard-a-app` Secret at runtime for the
`shardshop_owner` bootstrap role and `shardshop` database. No passwords are committed
or printed; application and migration roles arrive in steps 2.3–2.4.

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

Run the reversible topology drill against this disposable lab:

```bash
bash microservices/shardshop/scripts/verify-topology.sh
```

It drills `shard-a` by default; pass `shard-b` or `shard-c` to drill another shard.
It checks Service roles, replication, failover-slot synchronization and PVC
persistence, replaces one standby pod, and temporarily fences one then both
standbys to observe quorum commits. Reads and writes are disrupted during the
drill. It restores the standbys and removes its own SQL objects on exit; it never
deletes a PVC. A ConfigMap prevents concurrent runs. If the process is killed
without a chance to clean up, first ensure no drill is running, remove the
`cnpg.io/fencedInstances` annotation from the drilled shard, wait for the cluster to
be Ready, and inspect the probe name in its `<shard>-topology-drill` ConfigMap before
removing its SQL schema/slot and the ConfigMap.

`kind delete cluster --name shardshop` removes the whole lab, including its
storage; kind storage is disposable and is not a backup.
