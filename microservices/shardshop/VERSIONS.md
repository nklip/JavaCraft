# ShardShop version and support policy

Support information and published artifacts checked on **2026-09-28**. Exact
selections, checksums, image digests, sources, and review deadlines are now in
[versions.lock.yaml](versions.lock.yaml). Step **0.1 is Done**: kind 1.36.4 is selected under the user-authorized fallback,
and Canonical OpenJDK 25 JDK/JRE images are pinned and smoke-tested.
The inventory is complete; deployment readiness still requires later milestones.
Docker Desktop is updated, and the kind node image's advisory findings are
accepted for the local, loopback-only lab. Recheck upstream information before
implementation and version changes.

Use **LTS releases where available and maintained stable GA releases elsewhere**.
Choose the longest remaining support window within the compatible stable set.
The selected policy for this local project is **community support with regular
upgrades**. No commercial subscription, extended-support entitlement, or SLA is
assumed. A requirement to freeze every component on one release series for several
years is outside this plan; this community stack does not provide that guarantee.
The specifically requested `de.mkammerer.snowflake-id:snowflake-id:0.0.2` is the
sole named library exception to the maintained-release requirement below. Its
publication does not establish LTS or ongoing support.

The user-selected Java baseline is **OpenJDK 25** as a minimum, with no vendor or
exact-patch restriction for local builds; newer JDKs also work. The installed GA
build `25+36-3489` and JDK 26.0.2 are verified for the local skeletons. Containers use Canonical's maintained OpenJDK 25 stable
tracks on Ubuntu 26.04, currently verified as `25.0.4.1+1-1-26.04.4-Ubuntu`.
Local Maven remains vendor/patch-unrestricted. Review updates and requalify image
digests monthly and before deployment. [OpenJDK 25](https://jdk.java.net/25/),
[reference downloads](https://jdk.java.net/archive/).

## 1. Runtime and infrastructure baseline

| Component | Planned stable baseline | Maintenance window and selection constraint |
|---|---|---|
| JDK and application runtime | **JDK 25 or newer** for builds (verified with **25+36-3489** and **26.0.2**); OpenJDK 25 runtime images | Compile and test with one JDK, 25 or newer; bytecode targets release 25. No vendor or exact-patch requirement and no preview features. No fixed vendor support horizon is assumed. [OpenJDK 25](https://jdk.java.net/25/), [archive](https://jdk.java.net/archive/) |
| PostgreSQL, including ledger and backup tools | **18.6**, then maintained 18.x updates | PostgreSQL 18 maintenance ends **2030-11-14**. Use the same major in Kubernetes and Testcontainers; keep `pg_dump`/restore tools aligned. [Version policy](https://www.postgresql.org/support/versioning/) |
| Spring Boot | **4.1.1** and its matching dependencies BOM | 4.1.x OSS support ends **2027-07-31**. Supported stable branch upgrades are required; Boot's commercial dates are not the community entitlement. Java 25 is supported. [Lifecycle](https://api.spring.io/projects/spring-boot/generations/4.1.x), [Java compatibility](https://docs.spring.io/spring-boot/system-requirements.html) |
| Maven | Command-line `mvn`; inherited repository minimum **3.9.0** (verified with 3.9.16) | Maintained stable 3.9 series, no promised multi-year fixed-version LTS. Review future GA releases; exclude Maven 4 release candidates. [Release history](https://maven.apache.org/docs/history.html) |
| Kubernetes and kubectl | **Kubernetes 1.36.4**, user-authorized kind fallback; **kubectl 1.36.x** from the user's `PATH` | 1.36 maintenance ends **2027-06-28**. This is the newest series supported by the selected CNPG release; match kubectl to the server minor. [Patch lifecycle](https://kubernetes.io/releases/patch-releases/), [release artifacts](https://github.com/kubernetes/kubernetes/releases) |
| CloudNativePG | **1.30.1**, current maintained patch, installed from its official Helm chart **0.29.1** | 1.30.x supports Kubernetes 1.34-1.36 and PostgreSQL 14-18, with EOL approximately **December 2026**. Its short lifecycle requires operator upgrades. Kubernetes 1.37 is only tested, not supported by this operator version. [Support matrix](https://cloudnative-pg.io/docs/1.30/supported_releases/), [release](https://cloudnative-pg.io/releases/cloudnative-pg-1-30.1-released/) |
| kind | **0.33.0** tested; `kind` from the user's `PATH` | Stable local-development tool with no fixed LTS term. Explicitly choose a compatible 1.36 node image; its default 1.37 image does not meet the CNPG matrix. [Release and node images](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0) |
| Helm | **4.3.0** tested; `helm` from the user's `PATH` | Maintained stable 4.x with no fixed LTS term; 4.3.x supports Kubernetes 1.34–1.37, covering the 1.36 lab. It installs only vendored, verified third-party charts, starting with CloudNativePG; a later cloud target reuses the same charts and values. [Version skew](https://helm.sh/docs/topics/version_skew/), [release](https://github.com/helm/helm/releases/tag/v4.3.0) |
| RabbitMQ | **4.3.6**, maintained GA patches and subsequent supported series | Community support ends **2026-11-30**. This is a rolling-support exception, not LTS; no newer stable series is listed at review time. Native quorum delayed retry requires 4.3+. [Support timeline](https://www.rabbitmq.com/release-information), [retry feature](https://www.rabbitmq.com/docs/quorum-queues#delayed-retry) |
| Erlang/OTP | **27.3.4.17**, bundled by the selected official RabbitMQ image | This corrects the draft's 28.x assumption. OTP 27 is supported by RabbitMQ 4.3.6; upgrade the broker/runtime image together. No fixed-date LTS is assumed. [Compatibility](https://www.rabbitmq.com/docs/which-erlang), [immutable image recipe](https://github.com/docker-library/rabbitmq/blob/a2d49841fbcf81713cf0e1c279facbce545e2292/4.3/ubuntu/Dockerfile), [OTP security policy](https://github.com/erlang/otp/security) |
| PostgreSQL/operator image OS | Vendor-maintained **Debian 13 (trixie)** images | Full support through **2028-08-09**, Debian LTS through **2030-06-30**, subject to package/architecture coverage. The database/operator support window can expire first. [CNPG image baseline](https://cloudnative-pg.io/docs/1.30/release_notes/v1.30/), [Debian lifecycle](https://www.debian.org/releases/trixie/) |
| Application image OS | **Canonical OpenJDK 25** on **Ubuntu 26.04 LTS (resolute)**; `ubuntu/jdk` and `ubuntu/jre` stable tracks, pinned by digest | Canonical advertises these tracks through **May 2031**. Native ARM64 build/runtime checks passed on Java **25.0.4.1**. Requalify new digests monthly; the runtime is shell-free and final images must explicitly select a non-root user. [JDK support](https://hub.docker.com/r/ubuntu/jdk), [JRE support](https://hub.docker.com/r/ubuntu/jre) |
| Host container runtime and node internals | Docker Desktop **4.92.0** for macOS arm64 with a 12 GB VM (12 CPUs, 2 GB swap); node internals updated with kind | The host now runs 4.92.0 after checksum/signature verification; its component versions are recorded in the lock. Its containerd 2.3.5 and the node image have advisory findings, accepted for the local lab. Docker has rolling support, not fixed-version LTS. [Release notes](https://docs.docker.com/desktop/release-notes/), [macOS support](https://docs.docker.com/desktop/setup/install/mac-install/), [kind base-image contract](https://kind.sigs.k8s.io/docs/design/base-image/) |

The lock records OCI index and native **linux/arm64** manifest/config digests for
all seven selected images, including kind and the Canonical OpenJDK JDK/JRE pair.
Use their `reference` fields for deployment pins; tags identify what was reviewed.
Both Java images execute GA OpenJDK 25.0.4.1; the JDK passes a product build and
unit/integration checks, and the JRE starts all six skeleton JARs. Final launcher
images, image OS advisory scans and infrastructure behavior remain later
qualification work. Other CPU architectures require their own qualification.

Use CNPG's maintained `18.6-standard-trixie` image for the three shard clusters
and single-instance ledger, and official `postgres:18.6-trixie` for Testcontainers.
The deprecated CNPG `system` variant is unnecessary for the planned logical
`pg_dump` backups.
The official RabbitMQ image uses **Ubuntu 24.04 LTS** (standard maintenance through
May 2029); preserve that vendor build. The Ubuntu 26.04 choice applies to the
application/build images. [CNPG image variants](https://github.com/cloudnative-pg/postgres-containers),
[Ubuntu lifecycle](https://ubuntu.com/about/release-cycle).

The kind 0.33.0 release's tested Kubernetes **1.36.4** image is selected under
`images.kind_node`. Its published digest matches the registry bytes. A fresh
`kindest/node:v1.36.5` lookup returned **404 / MANIFEST_UNKNOWN** on 2026-09-28;
the user explicitly authorized **1.36.4 if 1.36.5 is unavailable**. This closes
the artifact gap with a documented patch exception within CNPG's supported 1.36
minor. kubectl 1.36.5 remains compatible within that minor. Recheck available
compatible node images by 2026-10-28; kind's default 1.37 is not selected.
[Release](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0).

RabbitMQ and CNPG are the nearest maintenance deadlines. An older broker is not
a long-support workaround: an EOL community branch stays EOL, and pre-4.3 versions
also require a different retry design. Qualify a maintained compatible community
successor before EOL. If none is available, stop deployment past EOL or design
and validate a supported community replacement; commercial support is outside scope.

## 2. Java libraries and build tools

Use one explicitly pinned Spring Boot BOM for the six applications. The following
is the reviewed **4.1.1 BOM snapshot**, not a set of independent overrides or LTS
claims. A maintained BOM provides a tested dependency baseline; each dependency
still has its own lifecycle. Check upstream maintenance and advisories for every
resolved artifact. [Managed coordinates](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html),
[Spring support policy](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions).

| Dependency family | Reviewed BOM selection / rule |
|---|---|
| Spring Framework, MVC, JDBC, transactions, validation, actuator, embedded server | Framework **7.0.9**; use the Boot-managed compatible family, including its managed server and validation libraries |
| Spring AMQP and RabbitMQ Java client | **4.1.1** and **5.30.0**; verify required reject/confirm behavior against the selected broker |
| PostgreSQL JDBC and HikariCP | **42.7.13** and **7.0.2**; exercise timeouts, TLS, failover, and pool recovery |
| Flyway core and PostgreSQL database module | **12.4.0** together; verify PostgreSQL 18 and Java 25 support and use community-available migration features |
| Jackson, SLF4J, Logback | Jackson 3 **3.1.5**, SLF4J **2.0.18**, Logback **1.5.38**; keep the BOM's coherent dependency set |
| JUnit, Mockito, Testcontainers | **6.0.3**, **5.23.0**, **2.0.5**; use current maintained compatible releases, and document any justified override |
| Lombok annotation processor, if retained from the repository configuration | **1.18.46**; processor and dependency must agree and support Java 25 |
| HTTP client, Micrometer, transitive dependencies, optional future libraries | Prefer JDK-provided or BOM-managed functionality. Record every resolved library in the dependency inventory, including test scope; avoid adding a second overlapping library without need |

### Explicit Snowflake library selection

Pin **`de.mkammerer.snowflake-id:snowflake-id:0.0.2`** outside the Boot BOM in
ShardShop's dependency management. It is the latest published release found in
Maven Central as of the review date, published **2023-01-15**; it is a pre-1.0
release with **no published LTS lifecycle or future maintenance commitment**.
Use it as the user's explicit selection, not as evidence that every dependency
has long-term support. Record this exact exception in `versions.lock.yaml` and
the SBOM, and recheck upstream releases/advisories monthly, first by **2026-10-28**.
[Maven metadata](https://repo.maven.apache.org/maven2/de/mkammerer/snowflake-id/snowflake-id/maven-metadata.xml),
[upstream changelog](https://github.com/phxql/snowflake-id/blob/main/CHANGELOG.md).

The tagged POM declares Java 11 minimum, LGPLv3 licensing, and no runtime
dependencies. Verify Java 25 operation, distribution/license obligations, artifact
origin, and the generator's concurrency/time behavior before the application
milestone. Add the dependency only to modules that generate IDs or derive the
product dataset; receiving an ID does not require a live generator.
Do not invent a supported successor version or silently substitute a different
generator library. A critical unresolved defect requires a documented remediation
before deployment. [Tagged POM](https://github.com/phxql/snowflake-id/blob/v0.0.2/pom.xml),
[tagged generator](https://github.com/phxql/snowflake-id/blob/v0.0.2/src/main/java/de/mkammerer/snowflakeid/SnowflakeIdGenerator.java).

The fixed 41/10/12 profile, epoch, process allocation, overflow handling, decimal
wire/storage types, and tests are defined in [ARCHITECTURE.md](ARCHITECTURE.md).
Requalify that contract on a library update; changing library configuration can affect uniqueness even when
the public numeric representation is unchanged.

### Build plugins and other dependencies

Build plugins are **not** version-managed merely by importing the Boot dependency
BOM. Pin compiler, Surefire/Failsafe, Boot packaging, JaCoCo, Enforcer, resource,
jar, clean, install/deploy, dependency/reporting, and any other executed plugins
separately. The lock now records the following published GA artifacts, including
their actual JAR SHA-256, upstream checksum/metadata URLs, and declared Java/Maven
requirements. Prerelease Maven 4 plugins and Boot milestones were excluded.
[Boot Maven guidance](https://docs.spring.io/spring-boot/maven-plugin/using.html),
[Maven plugin releases](https://maven.apache.org/plugins/),
[JaCoCo releases](https://github.com/jacoco/jacoco/releases).

| Plugin / tool | Locked version |
|---|---|
| Maven Compiler | 3.16.0 |
| Maven Surefire / Failsafe | 3.6.0 / 3.6.0 |
| Maven Resources / JAR | 3.5.0 / 3.5.1 |
| Maven Clean / Install / Deploy | 3.5.0 / 3.2.0 / 3.2.0 |
| Maven Dependency / Help | 3.11.0 / 3.5.2 |
| Maven Enforcer / Toolchains | 3.6.3 / 3.3.0 |
| Spring Boot packaging | 4.1.1 |
| JaCoCo / CycloneDX SBOM | 0.8.15 / 2.9.3 |
| Mojo Versions / Exec, if the inherited Exec profile is retained | 2.22.0 / 3.6.4 |

The declared minimum versions fit Maven 3.9 and Java 25; this does not prove
every plugin classpath/provider combination works. Step 0.3 qualified the executed
goals and providers and added checksum-verified security overrides; see the audit record. The lock also hashes the Boot BOM and the
Snowflake JAR; its BOM snapshot is not a resolved dependency graph. Maven itself
is not pinned; it comes from the command line. Pinning install/deploy does not
require any publication step or new plugin execution.

For a library/tool without a published support-end date, record **"no published
fixed EOL; upstream maintenance checked on DATE"** with the source and the next
review date. Do not copy the JDK, Boot, or PostgreSQL EOL onto its dependencies.
Except for the named Snowflake selection above, an unsupported or unmaintained
dependency needs a compatible maintained version or replacement before adoption.
Optional additions such as springdoc, Cucumber,
metrics servers, or backup plugins must pass the same gate when introduced;
they are not dependencies merely because another repository module uses them.
Terraform, its AWS provider, and the EKS Kubernetes version are not selected yet:
PLAN milestone 7 qualifies them under this policy, and the local lab never depends
on them.

## 3. Repository inheritance and reproducible pins

The current root POM is an input to review, not an automatic approval of versions:

- Java release 25 matches this baseline, but root Boot **4.1.0** must advance to
  the selected patch within ShardShop's own dependency management.
- The root Enforcer accepts any JDK at least 25 and Maven at least 3.9.0.
  ShardShop keeps both minimums; its Toolchains step compiles and tests with the
  `JAVA_HOME` JDK, and Maven comes from the command line.
- Root JUnit **6.1.2** and Netty BOMs precede Boot's BOM. Inspect the effective
  model and explicitly resolve their precedence for ShardShop; importing another
  BOM alone does not prove that inherited managed entries were replaced.
- Align the Mockito startup-agent jar with resolved `mockito-core`, and align
  Surefire/Failsafe JUnit Platform provider dependencies with the selected JUnit
  family. Audit Lombok processing and JaCoCo compatibility with Java 25.
- Override the inherited floating `postgres:17` test image locally. Testcontainers
  and the Kubernetes deployment must use the chosen PostgreSQL 18 patch with
  recorded image identities; broker tests use the selected broker/runtime pair.
- Keep these choices scoped to the ShardShop parent/children. Changes to unrelated
  repository modules require separate work.

[versions.lock.yaml](versions.lock.yaml) is the source for selected
infrastructure/tool pins and dated support metadata. Its `unresolved` section
records the resolved node/OpenJDK-image decisions and remaining later-step checks. Maven POMs and the BOM
provide scoped dependency/build enforcement. No script checks the POMs against
this inventory; update both together.
Include direct and transitive runtime/test libraries, plugin and processor
dependencies, image OS packages, and bundled broker/node runtimes in the SBOM.
Record exact version/digest/checksum, origin, support model, source URL, checked
date, EOL if published, and upgrade/review deadline. Unknown or missing entries
are not a completed deployment audit. The `audit` Maven profile regenerates resolved
Maven graphs and SBOMs under each application's `target/audit`; image and bundled
runtime inventories remain explicit prerequisites to the corresponding
infrastructure milestones.

Reject external snapshots, milestones, alpha/beta/RC releases, floating image
tags, Maven version ranges, and unreviewed dependency overrides. Internal reactor
`1.0-SNAPSHOT` artifacts are allowed during development; that exception does not
permit snapshot third-party dependencies. Check plugin classpaths as well as
application classpaths. Use Maven Central and official vendor images/artifacts;
verify checksums/signatures and record native CPU-architecture compatibility.

## 4. Maintenance and acceptance gates

- Review support status and updates monthly and before deployment. Take stable
  security/bug-fix updates promptly, rebuild pinned images for OS fixes, and test
  the result. A digest pin ensures reproducibility, not indefinite maintenance.
- Start supported-branch migration planning at least 90 days before EOL where
  feasible. Components already inside that window, currently RabbitMQ and CNPG,
  need an active upgrade item immediately. Complete upgrades before EOL; do not
  substitute an unreleased successor or count a roadmap date as availability.
- Qualify RabbitMQ's supported successor by **2026-11-15**, before its November
  EOL. Review the next stable CNPG release immediately on availability and upgrade
  before 1.30's published EOL, currently approximately December 2026. If no suitable
  successor is available, stop deployment past EOL or qualify a supported community
  replacement; do not silently run an unsupported version or assume paid coverage.
- Plan the Kubernetes 1.36 migration before **2027-06-28** and Boot 4.1 migration
  before **2027-07-31**. Re-evaluate the complete compatibility matrix for each
  change; updating Kubernetes alone can leave the operator unsupported.
- Gate milestone 0 on an exact, compatible GA lock and a reviewed support inventory
  for the selected components, with the explicit published Snowflake exception.
  Gate the application milestone on the resolved
  Maven graph, SBOM/advisory check, and contract/unit/integration tests. Gate broker,
  database, and operator upgrades on the relevant replay, capacity, failover, and
  restore scenarios in [ARCHITECTURE.md](ARCHITECTURE.md) that are implemented at
  that point.

### Step 0.1 verification record

On 2026-09-28, all seven selected image indexes were fetched from their official
registries. The kind candidate was promoted to the selected 1.36.4 fallback;
Canonical stable OpenJDK images replaced the missing build/runtime selections. Index, arm64 manifest, and config bytes matched
their SHA-256 digests. The plugin JARs, the Boot BOM and the Snowflake JAR were
downloaded, hashed, and compared to their published checksum files. The CNPG installer YAML hash was computed from the official release
download; the upstream checksum file does not include that YAML. Step 1.2 later
replaced that YAML with the official chart; see its verification record below.
Kind, kubectl, and Docker installer checksums came from official metadata;
those executables/installers were not downloaded or run. The replacement OpenJDK
25 reference archive checksum comes from its official sidecar; the existing local
OpenJDK 25 installation was used for the step 0.2 checks.
Both OpenJDK images were pulled by digest for native ARM64 checks. The product
build/test run and six JRE startup checks passed.
Signatures, complete image OS advisory scans and infrastructure runtime behavior
remain outside this artifact qualification.

The lock has `status: complete` for the inventory and `deployment_ready: false`
until later deployment checks pass. From the repository root, inspect the resolved
decisions and remaining milestone prerequisites with:

```bash
ruby -ryaml -e 'v = YAML.safe_load(File.read(ARGV.fetch(0))); abort "wrong schema" unless v.fetch("schema_version") == 1; puts v.fetch("status"); v.fetch("unresolved").each { |i| puts "#{i.fetch("id")}: #{i.fetch("status")}" }' microservices/shardshop/versions.lock.yaml
```

Reproduce artifact checks by fetching each lock entry's `artifact_url`/`url` and
comparing `shasum -a 256` with its `sha256`, plus its published checksum where
recorded. For OCI images, verify the raw index and platform manifest digests and
the referenced config digest; formatted/re-serialized JSON will not preserve an
OCI digest. A current tag may move: rechecks must use the locked digest URLs.

Module-scoped inspection commands:

```bash
mvn -f microservices/shardshop/shardshop-order/pom.xml help:effective-pom -Doutput=target/effective-pom.xml
mvn -f microservices/shardshop/shardshop-order/pom.xml dependency:tree -Dverbose -DoutputFile=target/dependency-tree.txt
mvn -f microservices/shardshop/pom.xml clean verify
mvn -f microservices/shardshop/pom.xml -Pintegration verify
```

Run the first two for every deployable module, and separately inspect plugin
dependencies. Dependency/plugin report versions must also be pinned. The build
and integration runs cover only ShardShop, never the repository root reactor.

### Step 0.3 verification record

See the [module commands](README.md#dependency-and-test-validation). On
2026-09-28 the ShardShop build passed its 20 unit tests on JDK 25 and 26; there are
no integration tests yet. Its `-Paudit` reports show that in all six applications
the effective POMs match the lock's 17 plugin pins, plugin dependency overrides
and test-image digests, with 50 third-party application/test dependencies each and
no prereleases.

Scoped plugin overrides replace prerelease Sisu/BeanShell transitives and fix the
advisories named in the parent POM. Application dependencies still follow the
Boot BOM. No business behavior, containers or unrelated reactor builds were added.

### Step 1.1 verification record

The host update from Docker Desktop 4.69.0 to selected **4.92.0** completed on
2026-09-28. The official installer matched its lock checksum, the installed app
passed signature/notarization checks, and the running engine plus bundled
components were inventoried. The kind and kubectl releases recorded in the lock
also matched their checksums and ran natively; both now come from the user's
`PATH`.

The pinned kind node yielded 160 OS packages and a 597-package Scout inventory.
Its advisory scan found 155 distinct advisories (10 critical, 42 high), with
primary-source confirmation of installed versions preceding security fixes.
The host bundle also contains affected containerd 2.3.5. These findings were
accepted on 2026-09-28 for the loopback-only, disposable lab.
`HOST-RUNTIME-UPDATE` is resolved. On 2026-09-28 the cluster booted with three
Ready nodes on Kubernetes 1.36.4, and local-path storage provisioned a volume. On
2026-09-29 it was rebuilt with four nodes (three workers) for the quorum topology;
see PLAN step 1.1 for the evidence.

### Step 1.2 verification record

On 2026-09-29 the CloudNativePG install moved from the release YAML to the
official Helm chart, so the local lab and a later cloud target share one install
path. Chart **0.29.1** (operator 1.30.1) is vendored as
`infra/helm/cnpg/cloudnative-pg-0.29.1.tgz`. Its SHA-256 matched the official
index digest, the GHCR OCI copy was byte-identical, and cosign verified that copy's
keyless signature from the cloudnative-pg/charts release workflow, including its
transparency-log entry. `values.yaml` pins the operator by tag and digest, and the
chart renders that reference for both the container image and
`OPERATOR_IMAGE_NAME`. Its 11 CRDs carry `helm.sh/resource-policy: keep`, so
uninstalling the release cannot delete them or the database clusters that depend on
them. Helm **4.3.0** runs from the user's `PATH` as a Homebrew build; the lock
records the official release checksum. See PLAN step 1.2 for the evidence.
