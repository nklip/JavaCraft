# ShardShop versions

This file gives the current versions of the ShardShop components, their support windows and the rules for an
update. [versions.lock.yaml](versions.lock.yaml) is the machine record. It has the exact identities: image
digests, artifact checksums, sources, support dates and review dates. If this file and the lock do not agree, the
lock is correct. The full review history before 2026-10-31 is in the Git tag `shardshop-docs-full-2026-10-31`.

The policy for this local project is community support with regular updates:

- Use an LTS release where one is available. Otherwise, use a maintained stable GA release.
- In the compatible stable set, select the release with the longest remaining support window.
- No commercial subscription, extended support or SLA is assumed.
- The Snowflake library and the Quarkus plugin XML libraries are the only exceptions to the maintained-release
  rule. Refer to [Snowflake library](#snowflake-library) and [Quarkus plugin XML libraries](#quarkus-plugin-xml-libraries).

The lock has `deployment_ready: false`, because backup/restore and the final capacity acceptance are not qualified.

## Current versions

The platform is darwin/arm64 for the host and linux/arm64 for the containers. Other CPU architectures need their
own qualification. "Next review" is the `review_by` date of the lock entry.

### Runtime and platform

| Component | Version | Support end or policy | Next review |
|---|---|---|---|
| Java release (build target) | 25, no preview features | Any maintained JDK 25 or newer patch; no vendor or patch restriction | 2026-11-25 |
| Host JDK | Temurin 25.0.5+8-LTS, selected through `JAVA_HOME` | Temurin 25 community support at least until 2031-09 | 2026-11-25 |
| Application and migration JRE | Canonical OpenJDK 25.0.4.1 (`25.0.4.1+1-1-26.04.4-Ubuntu`), `ubuntu/jre:25-26.04_stable` | Canonical track supported through 2031-05 | 2026-11-25 |
| Container build JDK | Canonical OpenJDK 25.0.4.1, `ubuntu/jdk:25-26.04_stable` | Canonical track supported through 2031-05 | 2026-11-25 |
| Quarkus | 3.40.1.1 (platform BOM and Maven plugin) | 3.40 LTS, EOL 2027-09-30 | 2026-11-25 |
| PostgreSQL | 18.6 | EOL 2030-11-14 | 2026-11-13 |
| RabbitMQ | 4.3.7 | EOL 2027-01-31. Qualify a successor by 2027-01-15 | 2026-11-16 |
| Erlang/OTP | 27.3.4.19, bundled in the RabbitMQ image | No published fixed EOL. Update only with the broker image | 2026-11-16 |
| Kubernetes | 1.36.4 (kind node) | EOL 2027-06-28 | 2026-11-28 |
| CloudNativePG (CNPG) | Operator 1.30.1, Helm chart 0.29.1 | EOL approximately 2026-12. Project upgrade deadline 2026-11-30 | 2026-11-05 |
| Debezium Engine, PostgreSQL connector, JDBC store | 3.7.0.Final, embedded in order | Maintained stable, no fixed LTS | 2026-11-18 |
| Flyway OSS | 13.12.0 | Maintained stable, no fixed EOL | 2026-11-25 |
| Ubuntu 26.04 LTS | Base of the application, JDK and migration images | Standard security support through 2031-05 | 2026-11-25 |
| Ubuntu 24.04 LTS | Base of the RabbitMQ image and the Flyway distribution image | Standard security support through 2029-05 | 2026-11-25 |
| Debian 13 (trixie) | Base of the CNPG, PostgreSQL and kind node images | Full support until 2028-08-09, LTS until 2030-06-30 | 2026-11-25 |

### Lab infrastructure and images

All images are pinned by digest in the lock. Use the `reference` field for a deployment pin, never a tag alone.
Each image entry with a `security_review_by` field has the date 2026-11-30.

| Component | Version and tag | Support end or policy | Next review |
|---|---|---|---|
| kind node | `kindest/node:v1.36.4` | Kubernetes 1.36, user-authorized fallback (`KIND-1.36.5`) | 2026-11-28 |
| CNPG operator | `ghcr.io/cloudnative-pg/cloudnative-pg:1.30.1` | CNPG 1.30 | 2026-11-05 |
| CNPG chart | `cloudnative-pg` 0.29.1, vendored as `infra/helm/cnpg/cloudnative-pg-0.29.1.tgz` | CNPG 1.30 | 2026-11-05 |
| Shard and ledger PostgreSQL | `ghcr.io/cloudnative-pg/postgresql:18.6-standard-trixie`, build of 2026-10-26 | PostgreSQL 18, Debian 13 | 2026-11-13 |
| RabbitMQ broker | `rabbitmq:4.3.7-management` | RabbitMQ 4.3, Ubuntu 24.04 | 2026-11-16 |
| Migration image | `shardshop-flyway:13.12.0-openjdk25`, built from `infra/images/flyway/Dockerfile` | Flyway 13, Canonical JRE | 2026-11-25 |
| Flyway distribution (build source only) | `flyway/flyway:13.12.0` | Flyway 13 | 2026-11-28 |
| Application images | `shardshop-<application>`, built from `infra/images/application/Dockerfile` | Canonical JRE | 2026-11-25 |
| Test PostgreSQL | `postgres:18.6-trixie`, build of 2026-10-28 | PostgreSQL 18, test only | 2026-11-13 |
| Test Kubernetes API | `rancher/k3s:v1.36.5-k3s1` (v1.36.5+k3s1) | Kubernetes 1.36, test only | 2026-11-28 |

### Build and test dependencies

The Quarkus platform BOM selects most library versions. This table shows the explicit pins, the overrides and the
families that the lock records separately. The lock has the checksums of each artifact.

| Component | Version | Support end or policy | Next review |
|---|---|---|---|
| Jackson BOM (application) | 2.21.8, imported before the Quarkus BOM | 2.21 open LTS branch, no fixed EOL | 2026-11-25 |
| PostgreSQL JDBC (application) | 42.7.14 override | Maintained 42.7 line | 2026-11-25 |
| Netty BOM | 4.1.139.Final, imported before the Quarkus BOM | Netty 4.1 maintenance ends 2027-07-01 | 2026-11-04 |
| Vert.x (platform-managed) | 4.5.35 | Previous major supported up to two years after the next major | 2026-11-25 |
| Agroal (platform-managed) | 3.2.1 | No published fixed EOL | 2026-11-04 |
| RabbitMQ Java client | 5.37.0, socket transport only | Maintained 5.x GA | 2026-11-16 |
| Debezium | 3.7.0.Final, with Kafka Connect/client 4.2.1 | Maintained stable | 2026-11-18 |
| lz4-java | 1.12.1, order-local override | Maintained | 2026-11-18 |
| Apache HttpClient / HttpCore / HttpCore H2 | 5.6.4 / 5.4.3 / 5.4.3 (platform-managed) | Current stable HttpComponents lines | 2026-11-05 |
| Micrometer | 1.17.1 (platform-managed) | Community support until 2027-07 | 2026-11-04 |
| Prometheus simpleclient | 0.16.0 (platform-managed) | Not maintained. Refer to [platform-managed exceptions](#platform-managed-libraries-without-an-override) | 2026-11-04 |
| SmallRye Fault Tolerance | 6.11.4 (platform-managed) | Maintained 6.11 line | 2026-11-04 |
| Snowflake ID | `de.mkammerer.snowflake-id:snowflake-id:0.0.2` | User-selected exception, no published support | 2026-11-25 |
| JUnit / Mockito / Lombok | 6.1.3 / 5.21.0 / 1.18.46 | Platform or parent selection | 2026-11-25 |
| Testcontainers | 2.0.5 (platform-managed), test only | Maintained | 2026-11-04 |
| Flyway Java API | 13.12.0 (`flyway.version`), test only | Same release as the migration image | 2026-11-25 |
| Cucumber JVM / PicoContainer | 8.0.4 / 2.16, test only | Maintained 8.0 line | 2026-11-04 |
| Vert.x JSON Schema and OpenAPI / SnakeYAML | 4.5.35 / 2.6 (platform-managed), test only | Vert.x policy above | 2026-11-04 |
| Quarkus plugin XML libraries | 4.0.0-alpha-7 (three artifacts) | Plugin-only prerelease exception | 2026-11-04 |

Build plugins (all with the next review 2026-11-25, policy "maintained stable, no published fixed EOL"):

| Plugin | Version |
|---|---|
| Maven Compiler | 3.16.0 |
| Maven Surefire / Failsafe | 3.6.1 / 3.6.1 |
| Maven Resources / JAR | 3.5.0 / 3.5.1 |
| Maven Clean / Install / Deploy | 3.5.0 / 3.2.0 / 3.2.0 |
| Maven Dependency / Help | 3.11.0 / 3.5.2 |
| Maven Enforcer / Toolchains | 3.7.0 / 3.3.0 |
| Quarkus Maven plugin | 3.40.1.1 |
| JaCoCo / CycloneDX | 0.8.16 / 2.9.3 |
| Mojo Versions / Exec | 2.23.0 / 3.6.4 |

Plugin dependency overrides fix advisories in plugin classpaths. The `pom.xml` comments name each advisory.

| Plugin | Overrides |
|---|---|
| Quarkus Maven plugin | Jackson core, databind, XML, YAML and JDK8 2.21.8, annotations 2.21 |
| CycloneDX | `cyclonedx-core-java` 11.0.1, Jackson core, databind, XML and YAML 2.22.4, annotations 2.22 |
| Resources | Sisu Plexus and Inject 1.1.1, `plexus-utils` 3.6.2 |
| Clean | `plexus-utils` 4.1.0 |
| Dependency | `commons-beanutils` 1.11.0 |
| Help | `jsoup` 1.23.2 |
| Enforcer | `enforcer-rules` 3.7.0, BeanShell (`bsh`) excluded |
| JaCoCo | `plexus-utils` 3.6.2 |

Plugin graphs do not inherit the application Jackson BOM. Thus the plugin overrides are separate from the
application pins.

### Host tools

The host tools come from the user's `PATH`. The repository downloads no binary and pins no host version.

| Tool | Version | Support end or policy | Next review |
|---|---|---|---|
| Docker Desktop | 4.97.0 (build 244584): Engine 29.9.2, containerd 2.4.2, runc 1.5.2 | Rolling stable, no fixed-version LTS | 2026-11-25 |
| Node.js | 24.22.2 LTS, Homebrew `node@24` keg | Community LTS, maintained patch | 2026-11-25 |
| kind | 0.33.0 | Maintained stable, no fixed EOL | 2026-11-25 |
| kubectl | 1.37.2 | Kubernetes skew policy, one minor newer than the 1.36.4 API server | 2026-11-25 |
| Helm | 4.3.1 | Maintained stable, 4.3.x supports Kubernetes 1.34–1.37 | 2026-11-25 |
| Helmfile | 1.8.2 | Maintained stable 1.8 series | 2026-11-13 |
| Skaffold | 2.25.0 | Maintained stable | 2026-11-13 |
| Maven | 3.9.16 minimum, not pinned | Maintained 3.9 series. Maven 4 release candidates are excluded | With Quarkus |

Other host tools: Docker Buildx and Docker Scout from Docker Desktop, `jq` from `PATH`, and PostgreSQL 18.x `psql`
from `PATH` for the
[manual reconciliation reset](shardshop-order/RUNBOOK.md#manual-reconciliation-reset).

## Update rules

### Approval

- The user approves each update for each component. Do not apply an update that the user did not approve.
- Review support status and updates monthly and before deployment. Take stable security and bug-fix updates
  promptly.
- Start the migration plan for a supported branch at least 90 days before its EOL, where possible.
- Complete each upgrade before the EOL. Do not use an unreleased successor. A roadmap date is not availability.
- If no compatible successor is available, stop deployment past the EOL, or qualify a supported community
  replacement. Commercial support is outside the scope.
- A digest pin gives reproducibility, not maintenance. Rebuild pinned images for OS fixes.

### Record an update in the lock

1. Change the POM, the chart values or the Dockerfile and the lock entry together. No script compares the POMs
   with the lock.
2. Record the exact version, the digest or the checksum, the origin, the support model, the source URL, the check
   date, the EOL if one is published, and the next review date.
3. For an OCI image, record the index, the linux/arm64 manifest and the config digests. Calculate them from the raw
   bytes. Re-serialized JSON does not keep an OCI digest. A tag can move, thus a recheck uses the locked digest.
4. For a Maven artifact, compare the artifact with its published Maven Central checksum. Then record an
   independently calculated SHA-256.
5. For a library without a published support end, record "no published fixed EOL" with the check date, the source
   and the next review. Do not copy the JDK, Quarkus or PostgreSQL EOL to a dependency.
6. Keep the history in the dated lock records and in [CHANGELOG.md](CHANGELOG.md).

Do not use external prereleases (except the plugin XML libraries), floating image tags, Maven version ranges or
overrides that nobody reviewed. Use Maven Central and official vendor images. The `audit` Maven profile writes the
dependency trees, the plugin classpaths and the SBOMs to `target/audit` of each application.

### Docker Scout gate

`scripts/up.sh` scans each application image and the migration image before it loads the image into kind:

```bash
docker scout cves --exit-code --only-severity critical,high
```

The gate applies the reviewed OpenVEX statements in
`infra/images/application/vex/jre-pebble.openvex.json`. These rules apply:

- The gate accepts only statements whose author agrees with the anchored pattern `^ShardShop image review$`.
  `--vex-author` is a pattern, thus a pattern without anchors also accepts authors such as
  `Not the ShardShop image review`.
- The statements are `not_affected` statements for the Go stdlib of `/usr/bin/pebble` in the Canonical JRE base.
  The images replace the pebble entrypoint with Java, thus pebble never runs.
- Each statement names the products (Docker PURLs without a tag) and the exact package version. A new package
  version or a new advisory has no statement, thus the gate stops `up.sh`.
- Review the statements when the JRE base digest changes and when the Java entrypoint changes.
- Do not add a general exception. A risk acceptance does not change the gate.

The gate examines only the packages that Docker Scout indexes. Refer to
[OS packages without an advisory check](#os-packages-without-an-advisory-check).

### Qualify an update

- Re-evaluate the full compatibility matrix for each change. A Kubernetes update alone can make the operator
  unsupported.
- Qualify a Maven change with the resolved Maven graph, the SBOM and advisory check, and the contract, unit and
  integration tests. Use the commands in [RUNBOOK.md](RUNBOOK.md#dependency-and-test-validation).
- Qualify an image change with two uncached builds that give the same digests, the Scout gate, and a run of
  `scripts/up.sh` on the lab.
- Qualify a Flyway change with the migration Jobs through `scripts/up.sh`. Change the image and the
  `flyway.version` property together.
- Qualify a host Node.js change with the three Maven browser test suites
  ([Node.js browser test tool](#nodejs-browser-test-tool)).
- Qualify a broker, database or operator upgrade with the implemented replay, capacity, failover and restore
  scenarios in [ARCHITECTURE.md](ARCHITECTURE.md). An operator or broker change makes the earlier failover and
  restore results invalid until these drills pass again.

### Review dates

- The lock default is `review_by: '2026-11-04'`. An entry with its own `review_by` overrides it.
- A support review examines releases and compatibility. A security review examines images and advisories. The next
  security review is due by 2026-11-30 (`security_review_2026_10_31.next_review_by`).
- [PLAN.md](PLAN.md#rules-for-a-step) requires the complete due review before a step starts, or a
  recorded user decision.
- A query to a public advisory service uses package names only. Do not send the dependency inventory or the
  versions. An empty advisory response is not evidence that a package has no finding.

## Open findings and risk acceptance

### Local risk acceptance until 2026-11-30

On 2026-10-31, the user renewed the local risk acceptance until **2026-11-30** for these saved findings:

- the Canonical application and Flyway JRE glibc and OpenJDK findings, including the missing October 2026 Java
  fixes;
- the K3s test image findings, including the active binary (12 critical and 31 high on 2026-10-12);
- the test PostgreSQL image findings (4 critical and 33 high on 2026-10-31);
- the kind node image and CNPG operator findings. Both images have no newer release.

The acceptance applies only to the pinned images, for the loopback-only local lab and the unprivileged
loopback-only Maven test containers. It is not a production acceptance. It does not declare a package unaffected,
change a VEX statement, bypass a scan gate or extend a deadline. The lock records it in
`security_review_2026_10_31.local_risk_acceptance`.

Other recorded findings: RabbitMQ 4.3.7 has 6 critical and 34 high, all in the Go stdlib 1.22.2 of `gosu`. The
chart never runs `gosu`. The shard and ledger PostgreSQL image has 0 critical and 4 high. The migration image has no
vulnerable package.

### Canonical OpenJDK 25.0.5

The applications keep Canonical OpenJDK 25.0.4.1 until OpenJDK 25.0.5 is published for Ubuntu 26.04. On
2026-10-31, 25.0.5 was only in the proposed pocket of a later Ubuntu series. The user decided to wait. Recheck at
each review. When 25.0.5 is available, update the JRE and JDK pins, rebuild the images and run the Scout gate.

### OS packages without an advisory check

The JRE base records 12 Ubuntu packages in `/usr/share/rocks/dpkg.query`. `/var/lib/chisel/manifest.wall` records 11
of them, without `openjdk-25-jdk-headless`. Thus a complete check reads both files.

Docker Scout does not index six of the 12 packages: libc6 2.43-2ubuntu2.4 (glibc), libgcc-s1, libstdc++6 and
gcc-16-base 16-20260322-1ubuntu1, base-files 14ubuntu6.2 and base-passwd 3.6.8. Thus the `up.sh` inventory does not
show them, and the gate does not examine them.

On 2026-10-10, OSV listed 14 Ubuntu 26.04 advisories for these images. Docker Scout reports none of them:

| Source package | Version | Advisories |
|---|---|---|
| glibc | 2.43-2ubuntu2.4 | 6 |
| openjdk-25 | 25.0.4.1+1-1~26.04.4 | 7 |
| zlib | 1:1.3.dfsg+really1.3.1-1ubuntu3.1 | 1 |

Ubuntu gives all 14 the priority medium. Their CVSS 3.1 vectors give six of them a high base score of 7.0–8.1. The
2026-10-23 JRE rebuild fixed the zlib advisory CVE-2026-85091.

`SBOM-AND-RUNTIME` in the lock stays open and blocks step 4.9. The lock lists the advisory IDs. Close the item only
when a check covers every package of the base image and a review handles these advisories.
[OSV](https://osv.dev/), [Ubuntu CVE tracker](https://ubuntu.com/security/cves).

### CNPG upgrade deadline

CNPG 1.30.1 with chart 0.29.1 is the latest GA release (checked 2026-10-29). No 1.31.0 tag exists. CNPG 1.30
supports Kubernetes 1.34–1.36 and PostgreSQL 14–18, with an EOL of approximately December 2026. The operator image
has 2 critical and 9 high findings, including the active manager Go and x-net dependencies.

- The project upgrade deadline is **2026-11-30**. The next support check is 2026-11-05.
- An upgrade needs its own approved scope, a compatible GA artifact, a rehearsal with retained data, and recovery
  and restore validation.
- Do not renew support dates. Do not count an unreleased version as qualified.

[Releases](https://github.com/cloudnative-pg/cloudnative-pg/releases),
[support matrix](https://cloudnative-pg.io/docs/1.30/supported_releases/),
[upgrade procedure](https://cloudnative-pg.io/docs/1.30/installation_upgrade/).

### RabbitMQ 4.4 upgrade plan

The target is RabbitMQ **4.4.0**, supported through 2027-07-31. Keep 4.3.7 with OTP 27.3.4.19 until the
rehearsal passes. Qualify by **2027-01-15**, and upgrade before **2027-01-31**. 4.3.7 is the recommended
intermediate release before 4.4.

1. Verify the official image digest and the bundled OTP and OS. The 4.4 matrix accepts OTP 27–29.
2. Rehearse the retained messages, definitions, permissions and stable feature flags on an owned lab. Prove an
   isolated restore of a consistent backup of the stopped broker.
3. Pause and drain the producers. Record the remaining work. Do the single-broker outage through the lifecycle
   scripts and the chart.
4. Examine the policies, delayed retries, parking, confirms, acknowledgements, health and permissions. Repeat the
   implemented CDC, replay, reset, crash, DLQ and capacity checks, and the completed failover and restore drills.
5. If the upgrade fails, restore the qualified backup in isolation and reconcile the outcomes.

CAUTION: Do not downgrade the broker image after an upgrade to 4.4. RabbitMQ 4.3 can be unable to read the 4.4
classic queue files.

[Support](https://www.rabbitmq.com/release-information), [upgrade requirements](https://www.rabbitmq.com/docs/upgrade),
[Erlang matrix](https://www.rabbitmq.com/docs/which-erlang).

### Other open items

- `KIND-1.36.5`: the lab uses the kind node 1.36.4 under a user-authorized fallback
  ([Kubernetes, kind and kubectl](#kubernetes-kind-and-kubectl)).
- HttpClient 5.6.5, SnakeYAML 2.7, slf4j-jboss-logmanager 2.1.0 and Mockito 5.24.0 have no security reason. They
  wait for the next Quarkus platform.

## Component notes

### Kubernetes, kind and kubectl

- CNPG 1.30 supports Kubernetes 1.34–1.36. Thus the lab uses Kubernetes 1.36, the newest supported minor.
- kind 0.33.0 publishes a tested 1.36.4 node image. Its default node image is 1.37.0, which is outside the CNPG
  support matrix. Thus select the 1.36 node image explicitly.
- On 2026-09-28, `kindest/node:v1.36.5` returned 404 `MANIFEST_UNKNOWN`. The user authorized 1.36.4 if 1.36.5 is not
  available. On 2026-10-29, Kubernetes 1.36.6 was current, but Docker Hub had no v1.36.5 or v1.36.6 node tag.
  Recheck monthly.
- Homebrew has no 1.36 kubectl formula, thus the host uses kubectl 1.37.2. The skew policy permits a kubectl one
  minor version newer than the API server. The step 6.9 acceptance run used kubectl 1.37.2 against 1.36.4.
- Helm installs only vendored, verified third-party charts. The 11 CNPG CRDs have `helm.sh/resource-policy: keep`.
  Thus an uninstall cannot delete them or the database clusters.

### Java and Maven

- Compile and test with one JDK. Maven selects it through `JAVA_HOME`, without a vendor or patch gate. The
  bytecode target is release 25.
- Temurin 25.0.5+8 is installed beside the old upstream OpenJDK 25 installation. The default symlink and the shell
  files did not change.
- The Quarkus plugin declares Maven 3.9.16 as the minimum. Maven comes from the command line and is not pinned.
- The ShardShop parent keeps its own dependency management. The repository root management must not select
  ShardShop versions. Examine the effective model of each application after a change.

### Canonical JRE images

- The application images and the migration image use the pinned `ubuntu/jre:25-26.04_stable` digest. The stable tag
  has a `25-edge` label, but the executed Java is GA 25.0.4.1.
- The JRE is shell-free. Each final image declares the non-root user `10001:10001`.
- The images replace the `/usr/bin/pebble enter` entrypoint with `java -jar /app/quarkus-run.jar`. The pebble
  binary stays in the image, thus Scout reports its Go stdlib findings. The VEX statements cover them.

### Application images

`infra/images/application/Dockerfile` builds the six application images on the pinned JRE. `scripts/up.sh` builds
them with Skaffold, scans them with the Scout gate, loads them into kind and deploys the manifest digest of the
current build. No image contains kubectl.

### Flyway migration image

`infra/images/flyway/Dockerfile` copies only these items from the official Flyway 13.12.0 image into the Canonical
JRE: the core and PostgreSQL plugin libraries, the PostgreSQL JDBC driver (42.7.14) and the licenses. The upstream
Temurin runtime and the Ubuntu 24.04 base do not go into the final image.

- The launcher calls Java directly. The image declares UID/GID 10001.
- The build uses `SOURCE_DATE_EPOCH=0` and sets the copied tree to epoch zero. Two uncached ARM64 builds give the
  same config and platform digests.
- `up.sh` verifies the pinned platform manifest digest on each kind node before Helmfile runs the migration
  release.
- The Testcontainers scenarios use the Flyway Java API with the same version and exclude the CockroachDB plugin, as
  the image does.

### RabbitMQ and Erlang

Use the OTP that the official RabbitMQ image bundles. Do not install a custom Erlang. Update the broker and the
runtime only together, through the vendor image. Keep the vendor Ubuntu 24.04 base. RabbitMQ 4.3 or later is
necessary for the native quorum delayed retry. An older broker is not a long-support option: an EOL branch stays
EOL, and pre-4.3 versions need a different retry design.

### PostgreSQL images

- The three shard clusters and the ledger use the CNPG `standard` variant. The deprecated `system` variant is not
  necessary for logical `pg_dump` backups. CNPG `imageName` validation needs the version tag, and the digest pins the
  bytes.
- Testcontainers uses the official `postgres:18.6-trixie` image (`PG_VERSION=18.6-1.pgdg13+2`). The port is bound to
  `127.0.0.1`.
- Testcontainers runs with `TESTCONTAINERS_RYUK_DISABLED=true`. Ryuk publishes its port on all host interfaces and
  runs privileged with the Docker socket. A JVM shutdown hook removes the container.

### K3s test API server

The generator scenarios of `shardshop-testing` run k3s in a plain Testcontainers `GenericContainer` with
`server --disable-agent`. Without an agent, k3s runs no kubelet and no containerd, thus the container is not
privileged. The Testcontainers `K3sContainer` module always sets privileged mode, thus it is not used.

### Node.js browser test tool

`shardshop-testing` needs Node.js from `PATH` during ordinary Maven `test` and `verify`. Its product, order and
workload-control runners fail for a missing executable, a timeout or a nonzero script exit. The build downloads no
Node binary, npm dependency or wrapper. Application-only Maven selections do not include these checks. Use the
[verification-module command](shardshop-testing/RUNBOOK.md#javascript-page-checks).

- Use the separate Homebrew keg: `PATH="/opt/homebrew/opt/node@24/bin:$PATH"`.
- The global Homebrew `node` is 26.13.0. The project qualification uses `node@24` 24.22.2.
- The three suites run 68 JavaScript cases (36 product, 20 order, 12 workload control).
- After a change of the host Node.js, run the three suites again.

[Release list](https://nodejs.org/en/blog/release), [support policy](https://nodejs.org/en/about/previous-releases).

### Snowflake library

`de.mkammerer.snowflake-id:snowflake-id:0.0.2` is the user's explicit selection. It is a pre-1.0 release from
2023-01-15 with no published LTS or maintenance commitment. Its POM declares Java 11 minimum, LGPLv3 and no runtime
dependencies.

- Only `shardshop-idgen` declares it. Only product and order package it.
- Common, sharding, ledger and the workloads must not depend on it. Maven Enforcer rejects direct, optional and
  transitive dependencies in these modules.
- Do not invent a successor version, and do not silently replace the library.
- A critical unresolved defect needs a documented remediation before deployment.
- A library update needs a new qualification of the ID contract in [ARCHITECTURE.md](ARCHITECTURE.md). A
  configuration change can affect uniqueness.

[Tagged POM](https://github.com/phxql/snowflake-id/blob/v0.0.2/pom.xml).

### Quarkus plugin XML libraries

The official Quarkus Maven plugin resolves `org.apache.maven:maven-xml-impl`, `maven-api-xml` and
`maven-api-meta` at **4.0.0-alpha-7**. These three artifacts are a compatibility exception for the stable plugin.
They are not in the application runtime or test graphs and SBOMs. They do not make Maven 4 necessary. Maven Central
lists no GA versions of these artifacts, thus an override cannot satisfy the GA policy.

Keep the versions of the official plugin and their checksums in the lock. Examine them again at each Quarkus
update. The exception does not permit prerelease application libraries or plugins.

### Platform-managed libraries without an override

The Quarkus platform selects some versions that are not the newest. They stay without an override until a Quarkus
patch changes them, unless a security reason occurs:

- Prometheus simpleclient 0.16.0 is the last 0.x release. The Quarkus 3.40 Micrometer extension needs it.
- JNA 5.8.0 (test only), SmallRye Context Propagation 2.3.0 and SnakeYAML 2.6 (test only).

## Review log

The full text of each review is in the Git tag `shardshop-docs-full-2026-10-31` and in [CHANGELOG.md](CHANGELOG.md).

| Date | Scope | Result |
|---|---|---|
| 2026-09-28 | Step 0.1 initial inventory | Image indexes verified. Canonical OpenJDK 25 images and the kind node 1.36.4 fallback selected. |
| 2026-09-28 | Step 0.3 build pins | Effective POMs matched 17 plugin pins. Plugin overrides replaced prerelease transitives. |
| 2026-09-28 | Step 1.1 host runtime | Docker Desktop 4.92.0. kind node findings accepted for the loopback-only lab. |
| 2026-09-29 | Step 1.2 CNPG chart | Vendored CNPG chart 0.29.1, signature verified. |
| 2026-09-30 | Step 2.3 migration runtime | Flyway 13.8.1 image. Superseded by the Canonical JRE migration image. |
| 2026-10-03 | Quarkus target | Quarkus 3.40 LTS selected. |
| 2026-10-04 | Step 0.5 Quarkus migration | Six applications qualified. Plugin XML exception recorded. |
| 2026-10-06 | Steps 3.2 and 3.3 | jackson-core in common. Snowflake 0.0.2 qualified on Java 25. |
| 2026-10-07 | Steps 3.4, 3.5 and 3.6 | Datasets, product REST/JDBC and the Netty override qualified. |
| 2026-10-08 | Step 3.8 reader client | Apache HttpClient 5 added. |
| 2026-10-08 | Request balancer candidates | Step 6.8 candidates listed. Superseded. |
| 2026-10-10 | Step 3.9 images and metrics | Images, Scout gate and VEX qualified. Micrometer added. Scout gap recorded. |
| 2026-10-11 | Step 3.9 reader fault tolerance | SmallRye Fault Tolerance added. |
| 2026-10-12 | Steps 3.10 and 3.11 test dependencies | Testcontainers, Cucumber and k3s selected. Test image findings accepted. |
| 2026-10-13 | Step 3.12 contract tests | Vert.x validator replaced the Python tools. |
| 2026-10-14 | Helmfile and Skaffold | Helmfile 1.8.0 and Skaffold 2.25.0 qualified. VEX version 3. |
| 2026-10-16 | Steps 4.1 and 4.3 | Order provider and RabbitMQ 4.3.6 qualified. |
| 2026-10-18 | Steps 4.4, 4.5 and 4.6 | Debezium, lz4-java override and ledger qualified. VEX version 4. |
| 2026-10-21 | Step 4.12 currency precision | ISO 4217 minor units verified. |
| 2026-10-22 | Step 4.13 workload control | Workloads gained Quarkus REST. No new version. |
| 2026-10-24 | Steps 5.3 and 5.5 | Migration image build reproducible. Metrics in order and ledger. |
| 2026-10-25 | Step 6.0 maintenance review | Quarkus 3.40.1.1, Jackson 2.21.8, pgJDBC 42.7.14 and Temurin 25.0.5+8. CNPG excluded. |
| 2026-10-26 | Step 6.2 CNPG decision | Step 6.2 permitted on CNPG 1.30.1. Deadline unchanged. |
| 2026-10-29 | Support review | Support only. No version change. |
| 2026-10-29 | Step 6.8 security decision | Step 6.8 permitted with the security review open. |
| 2026-10-29 | Step 6.8 balancer and HTTP/2 client | Envoy 1.40.2 and h2c selected. Reverted 2026-10-30, HTTP/1.1 kept. |
| 2026-10-31 | Security review | Docker Desktop, Flyway, PostgreSQL, RabbitMQ, host tools and plugins updated. Risk acceptance until 2026-11-30. |
