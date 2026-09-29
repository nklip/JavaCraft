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

## Local Kubernetes cluster (step 1.1)

The lab runs on kind. Take `docker`, `kind`, and `kubectl` from your `PATH`, like
Maven; this project never downloads or pins tool binaries. It was tested with
Docker Desktop 4.92.0, kind 0.33.0, and kubectl 1.36 (for example, `brew install
kind kubectl`). The node image's advisories were reviewed on 2026-09-28 and
accepted for this loopback-only, disposable lab. Give Docker Desktop's VM at least
12 GB of memory (Settings → Resources); `up.sh` stops early if it has less.

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

It creates the `shardshop` cluster if it does not exist, waits for all nodes to be
Ready, and applies the kind overlay, which creates the `shardshop` namespace with
the `restricted` Pod Security label. kind adds the `kind-shardshop` context to
`~/.kube/config` and selects it; the script passes that context on every command
and never deletes a cluster, namespace, or volume. Check the result:

```bash
kubectl --context kind-shardshop get nodes
kubectl --context kind-shardshop get storageclass
kubectl --context kind-shardshop get namespace shardshop --show-labels
```

`kind delete cluster --name shardshop` removes the whole lab, including its
storage; kind storage is disposable and is not a backup.
