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
sole application-library exception to the maintained-release requirement below. Its
publication does not establish LTS or ongoing support. The requested Quarkus
plugin also requires the narrowly scoped XML-library exception described below.

The user-selected Java baseline is **OpenJDK 25** as a minimum, with no vendor or
exact-patch restriction for local builds; newer JDKs also work. The installed GA
build `25+36-3489` and JDK 26.0.2 are verified for the local skeletons. Containers use Canonical's maintained OpenJDK 25 stable
tracks on Ubuntu 26.04, currently verified as `25.0.4.1+1-1-26.04.4-Ubuntu`.
This selection includes Java migration tools: Flyway uses the same Canonical
JRE, with its Java libraries copied from the official Flyway image; that source
image's Temurin runtime is not included in the final image. The existing upstream
OpenJDK `25-open` local installation is unchanged, and Maven still selects the
JDK through `JAVA_HOME` without a vendor or exact-patch gate. Review updates and
requalify image digests monthly and before deployment. [OpenJDK 25](https://jdk.java.net/25/),
[reference downloads](https://jdk.java.net/archive/).

### Quarkus target selected on 2026-10-03

The application target is **Quarkus 3.40.1**, the latest stable release at this
review, on the **3.40 LTS** line with community maintenance through
**2027-09-30**. Pin the platform BOM and Maven plugin to 3.40.1. Retain the Java 25
baseline and JVM deployment model. [Quarkus release status](https://quarkus.io/releases/),
[3.40 getting started](https://quarkus.io/version/3.40/guides/getting-started/).

**PLAN step 0.5 is Done, verified on 2026-10-04.** All six skeletons now use
Quarkus. Their independent integration/audit builds and JVM launches pass;
the lock records verified BOM/plugin checksums and the resolved dependency
families. Earlier dated records describe their original implementation.
Review Quarkus patches monthly, next by **2026-11-04**, and qualify a supported
successor before the LTS deadline.

## 1. Runtime and infrastructure baseline

| Component | Planned stable baseline | Maintenance window and selection constraint |
|---|---|---|
| JDK and application runtime | **JDK 25 or newer** for builds (verified with **25+36-3489** and **26.0.2**); OpenJDK 25 runtime images | Compile and test with one JDK, 25 or newer; bytecode targets release 25. No vendor or exact-patch requirement and no preview features. No fixed vendor support horizon is assumed. [OpenJDK 25](https://jdk.java.net/25/), [archive](https://jdk.java.net/archive/) |
| PostgreSQL, including ledger and backup tools | **18.6**, then maintained 18.x updates | PostgreSQL 18 maintenance ends **2030-11-14**. Use the same major in Kubernetes and Testcontainers; keep `pg_dump`/restore tools aligned. [Version policy](https://www.postgresql.org/support/versioning/) |
| Quarkus | **3.40.1** with matching platform BOM and Maven plugin; verified in step 0.5 | 3.40 LTS community maintenance ends **2027-09-30**. Review maintained patches monthly and requalify Java 25 builds/runtime images on upgrades. [Release status](https://quarkus.io/releases/), [Java 25 support](https://quarkus.io/blog/quarkus-3-31-released/) |
| Maven | Command-line `mvn`; ShardShop minimum **3.9.16**, as declared by the Quarkus plugin | Maintained stable 3.9 series, no promised multi-year fixed-version LTS. Review future GA releases; exclude Maven 4 release candidates. [Release history](https://maven.apache.org/docs/history.html) |
| Kubernetes and kubectl | **Kubernetes 1.36.4**, user-authorized kind fallback; **kubectl 1.36.x** from the user's `PATH` | 1.36 maintenance ends **2027-06-28**. This is the newest series supported by the selected CNPG release; match kubectl to the server minor. [Patch lifecycle](https://kubernetes.io/releases/patch-releases/), [release artifacts](https://github.com/kubernetes/kubernetes/releases) |
| CloudNativePG | **1.30.1**, current maintained patch, installed from its official Helm chart **0.29.1** | 1.30.x supports Kubernetes 1.34-1.36 and PostgreSQL 14-18, with EOL approximately **December 2026**. Its short lifecycle requires operator upgrades. Kubernetes 1.37 is only tested, not supported by this operator version. [Support matrix](https://cloudnative-pg.io/docs/1.30/supported_releases/), [release](https://cloudnative-pg.io/releases/cloudnative-pg-1-30.1-released/) |
| kind | **0.33.0** tested; `kind` from the user's `PATH` | Stable local-development tool with no fixed LTS term. Explicitly choose a compatible 1.36 node image; its default 1.37 image does not meet the CNPG matrix. [Release and node images](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0) |
| Helm | **4.3.0** tested; `helm` from the user's `PATH` | Maintained stable 4.x with no fixed LTS term; 4.3.x supports Kubernetes 1.34–1.37, covering the 1.36 lab. It installs only vendored, verified third-party charts, starting with CloudNativePG; a later cloud target reuses the same charts and values. [Version skew](https://helm.sh/docs/topics/version_skew/), [release](https://github.com/helm/helm/releases/tag/v4.3.0) |
| RabbitMQ | **4.3.6**, maintained GA patches and subsequent supported series | Community support ends **2026-11-30**. This is a rolling-support exception, not LTS; no newer stable series is listed at review time. Native quorum delayed retry requires 4.3+. [Support timeline](https://www.rabbitmq.com/release-information), [retry feature](https://www.rabbitmq.com/docs/quorum-queues#delayed-retry) |
| Erlang/OTP | **27.3.4.17**, bundled by the selected official RabbitMQ image | This corrects the draft's 28.x assumption. OTP 27 is supported by RabbitMQ 4.3.6; upgrade the broker/runtime image together. No fixed-date LTS is assumed. [Compatibility](https://www.rabbitmq.com/docs/which-erlang), [immutable image recipe](https://github.com/docker-library/rabbitmq/blob/a2d49841fbcf81713cf0e1c279facbce545e2292/4.3/ubuntu/Dockerfile), [OTP security policy](https://github.com/erlang/otp/security) |
| PostgreSQL/operator image OS | Vendor-maintained **Debian 13 (trixie)** images | Full support through **2028-08-09**, Debian LTS through **2030-06-30**, subject to package/architecture coverage. The database/operator support window can expire first. [CNPG image baseline](https://cloudnative-pg.io/docs/1.30/release_notes/v1.30/), [Debian lifecycle](https://www.debian.org/releases/trixie/) |
| Application and migration image OS | **Canonical OpenJDK 25** on **Ubuntu 26.04 LTS (resolute)**; `ubuntu/jdk` and `ubuntu/jre` stable tracks, pinned by digest | Canonical advertises these tracks through **May 2031**. Native ARM64 base-image build/runtime checks passed on Java **25.0.4.1**. Requalify derived images separately; the runtime is shell-free and final images must explicitly select a non-root user. [JDK support](https://hub.docker.com/r/ubuntu/jdk), [JRE support](https://hub.docker.com/r/ubuntu/jre) |
| Schema migrations | **Flyway OSS 13.8.1** libraries on the selected Canonical OpenJDK 25 JRE; local image defined by `infra/images/flyway/Dockerfile`, pinned by manifest digest | The official Flyway image supplies libraries, drivers, configuration and licenses only. Frequent releases have no published fixed EOL; review monthly, first by **2026-10-30**, and requalify the final image and `scripts/migrate.sh` on the lab. Application BOMs do not select the external migration runtime. [Distribution source](https://hub.docker.com/r/flyway/flyway), [release notes](https://help.red-gate.com/help/flyway-cli13/help_8.aspx?topic=release-notes-and-older-versions/release-notes-for-flyway-engine) |
| Host container runtime and node internals | Docker Desktop **4.92.0** for macOS arm64 with a 12 GB VM (12 CPUs, 2 GB swap); node internals updated with kind | The host now runs 4.92.0 after checksum/signature verification; its component versions are recorded in the lock. Its containerd 2.3.5 and the node image have advisory findings, accepted for the local lab. Docker has rolling support, not fixed-version LTS. [Release notes](https://docs.docker.com/desktop/release-notes/), [macOS support](https://docs.docker.com/desktop/setup/install/mac-install/), [kind base-image contract](https://kind.sigs.k8s.io/docs/design/base-image/) |

The lock records OCI index and native **linux/arm64** manifest/config digests for
the selected upstream images, including kind and the Canonical OpenJDK JDK/JRE pair.
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
application, build and Java migration images. [CNPG image variants](https://github.com/cloudnative-pg/postgres-containers),
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

Use `io.quarkus.platform:quarkus-bom:3.40.1` for all six applications, scoped
to the ShardShop parent. Quarkus extensions use `io.quarkus` coordinates without
independent versions. Select CDI, REST/Jackson, validation, PostgreSQL JDBC/Agroal,
health, metrics and testing extensions as [PLAN section 4](PLAN.md#quarkus-framework-migration)
requires. Keep the RabbitMQ Java client and Debezium explicitly reviewed; if the
platform does not manage an artifact, pin a compatible maintained version locally.
Do not carry the previous Jackson, logging, JUnit or pool versions forward without
checking the Quarkus graph. [Platform BOM](https://quarkus.io/guides/platform/).

### Quarkus dependency selections

| Dependency family | Target selection and qualification rule |
|---|---|
| Dependency injection and configuration | Quarkus CDI and SmallRye Config; preserve constructor injection and eager routing validation |
| HTTP and JSON | `io.quarkus:quarkus-rest-jackson`; use platform-managed Jackson versions and verify decimal-string ID contracts |
| Validation | `io.quarkus:quarkus-hibernate-validator`; retain the architecture's error precedence |
| PostgreSQL JDBC and connection pools | `io.quarkus:quarkus-jdbc-postgresql` with Agroal; qualify bounded pools, timeouts, TLS and failover |
| Health and metrics | `io.quarkus:quarkus-smallrye-health` and `io.quarkus:quarkus-micrometer-registry-prometheus` when their milestones add observability |
| Application tests | `io.quarkus:quarkus-junit` and, where needed, `io.quarkus:quarkus-junit-mockito`; qualify the resolved JUnit/Mockito family, agents and coverage |
| RabbitMQ and CDC | `com.rabbitmq:amqp-client` and embedded Debezium Engine; separately qualify exact versions and the architecture's publication/acknowledgement contract |
| Schema migrations | External Flyway Jobs from section 1; no application migration extension |

The platform selects compatible library versions; each artifact still needs an
upstream maintenance and advisory review. Step 0.5 recorded the resolved versions
and checksums in the lock: Quarkus **3.40.1**, JUnit **6.1.3**, Mockito **5.21.0**,
JBoss Log Manager **3.2.2.Final** and SLF4J **2.0.18**. Netty **4.1.138.Final** is
platform-managed but is not a resolved skeleton dependency.
Keep plain domain/routing tests independent of the framework, pin Testcontainers
images, and audit any retained Lombok processor against Java 25. Add only the
dependencies a module uses. [REST](https://quarkus.io/guides/rest/),
[datasources](https://quarkus.io/guides/datasource/),
[testing](https://quarkus.io/guides/getting-started-testing/).

### Explicit Snowflake library selection

Pin **`de.mkammerer.snowflake-id:snowflake-id:0.0.2`** outside the framework BOM in
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
milestone. Add the dependency only to `shardshop-product` and `shardshop-order`,
the only modules allowed to generate IDs, including deterministic fixture IDs.
Workloads consume service-issued IDs and ledger consumes order-reserved result
IDs; neither may depend on Snowflake or access the generator allocator.
Do not invent a supported successor version or silently substitute a different
generator library. A critical unresolved defect requires a documented remediation
before deployment. [Tagged POM](https://github.com/phxql/snowflake-id/blob/v0.0.2/pom.xml),
[tagged generator](https://github.com/phxql/snowflake-id/blob/v0.0.2/src/main/java/de/mkammerer/snowflakeid/SnowflakeIdGenerator.java).

The fixed 41/10/12 profile, epoch, process allocation, overflow handling, decimal
wire/storage types, and tests are defined in [ARCHITECTURE.md](ARCHITECTURE.md).
Requalify that contract on a library update; changing library configuration can affect uniqueness even when
the public numeric representation is unchanged.

### Build plugins and other dependencies

Build plugins are **not** version-managed merely by importing a dependency BOM.
Step 0.5 pins `io.quarkus.platform:quarkus-maven-plugin:3.40.1`, updates the
packaging audit allowlist, and qualifies inherited plugin dependencies and test
agents. The table records the selected plugins.
Pin compiler, Surefire/Failsafe, Quarkus packaging, JaCoCo, Enforcer, resource,
jar, clean, install/deploy, dependency/reporting, and any other executed plugins
separately. The current lock records actual JAR SHA-256 values, upstream
checksum/metadata URLs and declared Java/Maven requirements for the existing
build, including the Quarkus plugin. Exclude prerelease artifacts except for the
three exact plugin-internal XML libraries documented below.
[Quarkus Maven guidance](https://quarkus.io/guides/maven-tooling/),
[Maven plugin releases](https://maven.apache.org/plugins/),
[JaCoCo releases](https://github.com/jacoco/jacoco/releases).

| Plugin / tool | Version and status |
|---|---|
| Maven Compiler | 3.16.0 |
| Maven Surefire / Failsafe | 3.6.0 / 3.6.0 |
| Maven Resources / JAR | 3.5.0 / 3.5.1 |
| Maven Clean / Install / Deploy | 3.5.0 / 3.2.0 / 3.2.0 |
| Maven Dependency / Help | 3.11.0 / 3.5.2 |
| Maven Enforcer / Toolchains | 3.6.3 / 3.3.0 |
| Quarkus Maven plugin | 3.40.1 — verified in step 0.5 |
| JaCoCo / CycloneDX SBOM | 0.8.15 / 2.9.3 |
| Mojo Versions / Exec, if the inherited Exec profile is retained | 2.22.0 / 3.6.4 |

The declared minimum versions fit Maven 3.9.16 and Java 25. Step 0.5 qualified
the executed goals, providers and agents after migration; unused goals remain
unqualified. The lock hashes the Quarkus BOM/plugin and the Snowflake JAR; the
resolved application/test graphs and SBOMs are generated under `target/audit`. Maven itself
is not pinned; it comes from the command line. Pinning install/deploy does not
require any publication step or new plugin execution.

For a library/tool without a published support-end date, record **"no published
fixed EOL; upstream maintenance checked on DATE"** with the source and the next
review date. Do not copy the JDK, Quarkus, or PostgreSQL EOL onto its dependencies.
Except for the named Snowflake selection above, an unsupported or unmaintained
dependency needs a compatible maintained version or replacement before adoption.
Optional additions such as SmallRye OpenAPI, Cucumber,
metrics servers, or backup plugins must pass the same gate when introduced;
they are not dependencies merely because another repository module uses them.
Terraform, its AWS provider, and the EKS Kubernetes version are not selected yet:
PLAN milestone 7 qualifies them under this policy, and the local lab never depends
on them.

### Contract validation tools

Step 3.1 review checks on **2026-10-06** used Python **3.14.7** from the user's
`PATH`, with **OpenAPI Spec Validator 0.9.0**, **JSON Schema 4.26.0** and
**PyYAML 6.0.3** in a disposable virtual environment outside the repository.
The [requirements file](scripts/contract-validation-requirements.txt) pins all
20 direct/transitive packages used by that environment. It vendors no tools and
adds no application dependency. The verifier needs Python 3.11 or newer; only
3.14.7 was qualified here. [Commands](README.md#http-and-message-contracts-step-31).
Package origins: [OpenAPI Spec Validator](https://pypi.org/project/openapi-spec-validator/0.9.0/),
[JSON Schema](https://pypi.org/project/jsonschema/4.26.0/),
[PyYAML](https://pypi.org/project/PyYAML/6.0.3/).
These tools have no claimed fixed LTS/EOL in this qualification; review the pinned
environment on the next contract/tool change, no later than **2026-11-06**.

The committed verifier checks schemas, all HTTP/message examples, response example
presence, ID/status-URL bounds, precision and Unicode boundaries, exact integer
normalization, example Snowflake profiles, fingerprint/total equality and message
correlation. It is not evidence that the future providers enforce those rules.

### Quarkus plugin XML libraries

The official **Quarkus Maven plugin 3.40.1** resolves
`org.apache.maven:maven-xml-impl`, `maven-api-xml` and `maven-api-meta`, all at
**4.0.0-alpha-7**, through its build-tool dependency graph. These three exact
artifacts are a compatibility exception for the requested stable plugin; they
are absent from application runtime/test graphs and SBOMs. They do not change
the Maven command-line requirement to Maven 4. Maven Central metadata checked
on **2026-10-04** lists no GA versions of these artifacts, so a speculative
override would not satisfy the GA policy or establish compatibility.

Keep the official plugin's versions, record their verified checksums in the lock,
and recheck on every Quarkus upgrade, next by **2026-11-04**. The exception covers
only these three plugin dependencies; it does not allow prerelease application
libraries or plugins. [Quarkus plugin POM](https://repo.maven.apache.org/maven2/io/quarkus/platform/quarkus-maven-plugin/3.40.1/quarkus-maven-plugin-3.40.1.pom),
[XML implementation metadata](https://repo.maven.apache.org/maven2/org/apache/maven/maven-xml-impl/maven-metadata.xml),
[XML API metadata](https://repo.maven.apache.org/maven2/org/apache/maven/maven-api-xml/maven-metadata.xml),
[metadata API metadata](https://repo.maven.apache.org/maven2/org/apache/maven/maven-api-meta/maven-metadata.xml).

## 3. Repository inheritance and reproducible pins

The current root POM is an input to review, not an automatic approval of versions:

- Java release 25 matches this baseline. Apply Quarkus **3.40.1** within
  ShardShop's own dependency management in step 0.5. The repository's unrelated
  framework management must not select incompatible application dependencies.
- ShardShop retains Java 25 as its minimum and raises Maven's minimum to 3.9.16
  for the Quarkus plugin. Its Toolchains step compiles and tests with the
  `JAVA_HOME` JDK, and Maven comes from the command line.
- Root JUnit **6.1.2**, Netty and framework BOMs can influence inherited management.
  Inspect every application's effective model and resolve precedence for the
  Quarkus platform, including inherited explicit overrides; importing another
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
tags, Maven version ranges, and unreviewed dependency overrides, except for the
three exact plugin-only XML artifacts documented above. Internal reactor
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
- Plan the Kubernetes 1.36 migration before **2027-06-28** and qualify a
  supported Quarkus 3.40 successor before **2027-09-30**. Re-evaluate the complete
  compatibility matrix for each change; updating Kubernetes alone can leave the operator unsupported.
- Gate milestone 0 on an exact, compatible GA lock and a reviewed support inventory
  for the selected components, with the explicit Snowflake and plugin XML exceptions.
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

### Step 0.5 verification record

On **2026-10-04**, all six applications independently passed the following command
from the repository root (product shown; repeat for each application path in the README):

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-product -am -Pintegration,audit clean verify
```

Maven **3.9.16** and OpenJDK **25+36-3489** ran **62 unit/startup tests and 10
packaged startup integration tests** across the seven Java modules. JaCoCo
reported **100% line coverage** in each module. The original routing vectors and
Java-only shared library are unchanged. Effective POMs, resolved graphs and SBOMs
agree on Quarkus/JUnit/Mockito versions, with no Spring runtime/test artifacts.
After the **2026-10-06** removal of unused application Mockito dependencies,
fresh audit builds report **140** SBOM components for product/order and **139** for
ledger and each workload. Mockito **5.21.0** remains a test-agent plugin dependency;
Mockito, Byte Buddy **1.18.8** and Objenesis **3.3** are absent from application
dependency graphs/SBOMs (the separate Byte Buddy agent remains through Quarkus).
All six applications pass `-Paudit clean verify`: **62 unit/startup tests plus 10
packaged routing tests**, no warnings, and 100% Java line coverage. Default `verify`
runs the packaged routing tests; product/order also pass `-Pintegration verify`
with each routing suite executed only once. Their missing-file checks require
the complete unique external-file URI. The three plugin-only XML prereleases are the documented exception;
the six verification logs contain no warnings. BOM and plugin bytes match Maven
Central's published SHA-512 checksums; the XML-library JARs match their published
SHA-1 checksums, and the lock records independently computed SHA-256 values.

All six `quarkus-app` directories launched successfully on the existing pinned
Canonical OpenJDK **25.0.4.1** JRE as UID/GID **584792**, with networking disabled,
a read-only root filesystem and all capabilities dropped. Product also passed
`-pl shardshop-product -am -Pintegration clean verify` in an isolated copy on the
pinned JDK image, using the installed Maven 3.9.16 and an offline dependency cache.
No image digest, database, migration or Kubernetes resource changed. Final
application images and their advisory inventory still belong to step 3.9.

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

### Current migration runtime

The active migration image is assembled by `infra/images/flyway/Dockerfile` from
the pinned official Flyway OSS 13.8.1 distribution and pinned Canonical OpenJDK 25
JRE. Only Flyway's core and PostgreSQL plugin libraries, the PostgreSQL JDBC driver
and the licenses cross into the final Ubuntu 26.04 image; other database plugins
and drivers, Azure AD and Netty libraries, the upstream Temurin JRE and Ubuntu
24.04 base do not.
The launcher calls Java directly, and the image declares UID/GID 10001.

`scripts/migrate.sh` automatically calls `scripts/build-migration-image.sh` after
its topology guard. The helper builds the local ARM64 image using Docker Buildx
with `SOURCE_DATE_EPOCH=0`, loads it into kind, and verifies the pinned CRI manifest
digest on every node before migration resources can be applied. It can also be
run separately after cluster provisioning. Docker, Buildx and kind are therefore
requirements for this local migration workflow; Maven and application artifacts
are not. Review changed build inputs and the resulting digest together.

The final ARM64 image reports `java.vendor=Ubuntu` and OpenJDK
`25.0.4.1+1-1-26.04.4-Ubuntu`. A Docker Scout scan of the trimmed image on
2026-09-30 indexed 74 packages and reported no findings; the earlier untrimmed
image had 417 packages and 12 findings (4 high, 8 medium), all in copied libraries
the migrations never used. Recheck the final image on the next Flyway/base
refresh, by 2026-10-28. The lock records the actual CRI and config digests. The
historical upstream-image scan below is separate evidence for the superseded image.

### Historical step 2.3 upstream-image verification (superseded)

On 2026-09-30, catalog migrations moved from a custom launcher (Boot-managed Flyway
12.4.0 in an image built from the product JAR) to the official Flyway OSS CLI image
**13.8.1**. This earlier runtime selection is superseded by the Canonical OpenJDK
image above. Step 2.3 changed no Java code, POM or dependency: the migration SQL and
`flyway.toml` live in `database/`, outside the Maven modules, and the migrations are
verified on the lab.

Flyway 13 deprecates `initSql`, and an `afterConnect` callback cannot hold a
`SET ROLE`, because Flyway restores each connection's original role after callbacks.
The streams therefore pass `-c role=<schema>_owner` in the JDBC `options` property,
so every session acts as the owner from its first statement and Flyway treats that
role as the original one.

The former image's raw OCI index, ARM64 manifest and config hashes matched the
recorded upstream pins. It bundled Temurin Java 25.0.4.1 on Ubuntu 24.04 and
declared no user; its Job passed restricted Pod Security admission as UID/GID
10001 with a read-only root filesystem,
`HOME=/tmp` and telemetry disabled through
[`REDGATE_DISABLE_TELEMETRY`](https://documentation.red-gate.com/fd/reference/environment-variables/redgate-disable-telemetry-environment-variable).
Docker Scout indexed 548 packages (150 deb, 397 maven) and reported 31 advisories:
0 critical, 5 high, 19 medium and 7 low. The high findings affect the bundled SQL
Server driver, HttpCore 5, jackson-databind and Ubuntu's openssl; the PostgreSQL
migration path used none of them. They were accepted for that image in the
loopback-only lab; this acceptance does not transfer to the rebuilt image.
The former image was 406 MiB, mostly Flyway's own 309 MiB distribution, so its
Alpine variant would have saved only about 25 MiB.
Flyway 13.8.1 validated the catalog histories that 12.4.0 wrote without changing them.

A local trial ran the same image and settings against the pinned PostgreSQL 18.6
test image, with the SQL mounted in a ConfigMap-style folder. It applied V1, reran
without changes, and failed as expected on checksum drift, runtime credentials and a
missing schema. A 20-second statement was cancelled at the 15-second limit and
rolled back.
