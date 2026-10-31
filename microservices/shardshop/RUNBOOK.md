# ShardShop runbook

[ShardShop](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md) · [Implementation plan](PLAN.md)

This runbook holds the procedures for the whole build. The [README](README.md)
tells how to start the lab; each module runbook holds the configuration, tests and
recovery of that module.

| Runbook | Covers |
|---|---|
| [Infrastructure](infra/RUNBOOK.md) | Cluster creation, deployment, images, RabbitMQ, stop, destruction and recovery |
| [Database](database/RUNBOOK.md) | Shard clusters, ledger database, migrations, roles and schemas |
| [Product](shardshop-product/RUNBOOK.md), [order](shardshop-order/RUNBOOK.md), [ledger](shardshop-ledger/RUNBOOK.md) | Service configuration, behavior and tests |
| [Workloads](shardshop-workload/README.md) | Seeding gate, workload pods, datasets, seeder, reader and producer |
| [Core](shardshop-core/README.md) | Contracts, ID validation, ID generation, routing and workload control |
| [Verification](shardshop-testing/RUNBOOK.md) | Testcontainers scenarios, kind suites, drills and capacity measurement |

## Java and Maven

Set `JAVA_HOME` to a maintained JDK **25 or newer patch**. The qualified build is
in [VERSIONS](VERSIONS.md). For example:

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.5-tem"
export PATH="$JAVA_HOME/bin:$PATH"
```

This example is not a vendor or exact-patch restriction. Use the `bin/java` of the
same JDK to launch the packaged applications.

Application and Flyway containers use the pinned Canonical OpenJDK 25 JRE. The
[migration runbook](database/RUNBOOK.md#database-schemas-and-migrations) describes
the Flyway image. The container runtime does not change the JDK that `JAVA_HOME`
selects.

Maven comes from the command line: use the `mvn` on your `PATH`. ShardShop keeps
Java **25** as its minimum and requires Maven **3.9.16** or newer, matching the
Quarkus plugin's declared minimum. Its Toolchains execution selects the
`JAVA_HOME` JDK, so compilation and forked tests use the same JDK as Maven; see the
[JDK selection parameters](https://maven.apache.org/plugins/maven-toolchains-plugin/select-jdk-toolchain-mojo.html).
Compilation targets release 25 with preview features disabled, whichever JDK
builds it. These rules are inherited only by ShardShop's children.

## Build and run

All six applications use Quarkus in JVM mode on Java 25. Maven module
tests need no Docker or Kubernetes; chart rendering and release
orchestration tests require `helm` and `helmfile` on `PATH`. Product and order
production starts require the routing snapshot and a live generator ID, which the
JVM reserves through the Kubernetes API at each start; tests use a fixed test ID or
a k3s API server.

The verification module also requires `node` on `PATH`: its ordinary Maven
`test`/`verify` phases run product, order and workload-control JavaScript suites.
Missing Node, a timeout or a failing script fails Maven. A product- or order-only
build does not include these checks; select `shardshop-testing` as well when
changing browser assets. See [Node qualification](VERSIONS.md#nodejs-browser-test-tool)
and the [browser test command](shardshop-testing/RUNBOOK.md#javascript-page-checks).

| Maven module | Quarkus application class |
|---|---|
| [shardshop-product](shardshop-product/README.md) | `dev.nklip.javacraft.shardshop.product.ProductApplication` |
| [shardshop-order](shardshop-order/README.md) | `dev.nklip.javacraft.shardshop.order.OrderApplication` |
| [shardshop-ledger](shardshop-ledger/README.md) | `dev.nklip.javacraft.shardshop.ledger.LedgerApplication` |
| [shardshop-workload/shardshop-product-seeder](shardshop-workload/shardshop-product-seeder/README.md) | `dev.nklip.javacraft.shardshop.workload.seeder.ProductSeederApplication` |
| [shardshop-workload/shardshop-product-reader](shardshop-workload/shardshop-product-reader/README.md) | `dev.nklip.javacraft.shardshop.workload.reader.ProductReaderApplication` |
| [shardshop-workload/shardshop-order-producer](shardshop-workload/shardshop-order-producer/README.md) | `dev.nklip.javacraft.shardshop.workload.producer.OrderProducerApplication` |

Run from the repository root with `JAVA_HOME` already set. Always select a
ShardShop POM; do not invoke the repository root reactor. Build one application
with `-pl`; `-am` also builds the common, idgen and sharding libraries for product
and order. Product/order require the external routing snapshot described below
and [generator allocation](shardshop-core/idgen/README.md#generator-allocation-at-jvm-startup)
at startup.

Verify all six one at a time, without installing anything. Product/order run their
packaged startup tests during `verify`. Command-mode tests use `--check-startup`
and test configuration; launch the continuous workloads separately with the
service URLs and settings in their runbooks:

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
done
```

Command-mode tests start each application through Quarkus. Sharding tests CDI
injection of the configured topology and router; idgen tests injection of its
singleton ID generator. Each library owns its test configuration, and
product/order each verify packaged startup. JaCoCo reports go to each Java module's
`target/site/jacoco/`. JVM packages live in `target/quarkus-app/`;
deploy that entire directory. All six applications start with `quarkus-run.jar`.
Product and order then reserve their generator ID through the configured
Kubernetes allocator.

Product/order load the external routing file through `quarkus.config.locations`,
retaining the `SHARDSHOP_ROUTING_CONFIG` override and default path
`file:/etc/shardshop/routing/application.properties`. Their eager CDI producers
validate the snapshot before the command runs. The Maven plugin uses only the
bundled `application.properties` during augmentation, so packaging does not need
a deployed snapshot. Production startup still fails for a missing file, missing
shard/region keys or an invalid topology; packaged integration tests cover those
failures and a valid external snapshot.

## Dependency and test validation

The scoped Quarkus platform BOM manages application and test dependencies. The local
parent aligns JUnit and Mockito with that platform, overrides Netty, Jackson and
pgJDBC, and pins all build and report plugins. Versions are in
[VERSIONS](VERSIONS.md). Plugin security overrides are confined to their own classpaths; the
official Quarkus plugin's three XML-library exceptions are recorded in
[the version policy](VERSIONS.md#quarkus-plugin-xml-libraries). Comments in the
parent POM explain the aligned JUnit/Netty/Mockito/Lombok properties and name the
advisory each plugin override fixes.
No application dependency is added merely because the BOM manages it.

Surefire runs `*Test` during `test`. Product/order each run twenty-four packaged routing, generator and reservation-pin
startup cases through Failsafe during **every `verify`**, without a profile or
infrastructure. Opt-in `-Pintegration` runs other `*IT` tests and excludes those
already executed routing cases. Ordinary tests disable automatic Dev Services.
Surefire/Failsafe supply Mockito's startup agent through their plugin dependencies;
common and idgen declare Mockito for their controlled-source tests.
Product and workload clients declare Mockito for their controlled IO tests. `shardshop-sharding`
tests its router against golden vectors whose expected shards were computed
independently. The PostgreSQL and RabbitMQ test-image properties use the immutable
digests of the lock. Only the `shardshop-testing`
scenarios start a container, and only with `-Pintegration`
([integration tests](shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)).

Idgen and sharding keep the test JVM option
`--add-opens=java.base/java.lang.invoke=ALL-UNNAMED` in their POM `argLine`
properties. Without it, the tests pass but log bootstrap warnings
(`Could not get access to jdk.internal.module API` and `FallbackModulesReconfigurer`
failures to open `java.lang` to `org.jboss.threads`). The command for the product and
order build is:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order -am clean verify
```

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

After a change to common or another shared library, run the six-application
audit. It builds all six applications with their libraries and writes the same
reports for each application:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml \
  -pl shardshop-product,shardshop-order,shardshop-ledger,shardshop-workload/shardshop-product-seeder,shardshop-workload/shardshop-product-reader,shardshop-workload/shardshop-order-producer \
  -am -Paudit clean verify
```
