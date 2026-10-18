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
| Helmfile | **1.8.0**, installed through Homebrew; `helmfile` from the user's `PATH` | Maintained stable 1.8 series with no fixed LTS term. Owns ordered Helm releases; the current upstream patch is 1.8.1. Review monthly, first by **2026-11-13**. Two complete native `up.sh` runs deployed all releases on **2026-10-14**; the unchanged rerun preserved application and database identities and allocator state. Official archive checksum and the installed Homebrew executable identity are recorded separately in the lock. [Selected release](https://github.com/helmfile/helmfile/releases/tag/v1.8.0), [configuration](https://helmfile.readthedocs.io/en/stable/configuration/) |
| Skaffold | **2.25.0**, installed through Homebrew; `skaffold` from the user's `PATH` | Maintained stable release with no fixed LTS term. Builds and tags the packaged Maven outputs using local Docker; native kind loads qualified images and Helmfile deploys them. Review monthly, first by **2026-11-13**. Two complete native `up.sh` runs on **2026-10-14** built all four artifacts, scanned them with Docker Scout and verified consistent qualified images on every kind node. Each run passed 1,034 unit/startup tests and 55 packaged routing checks. [Release](https://github.com/GoogleContainerTools/skaffold/releases/tag/v2.25.0), [Docker builder](https://skaffold.dev/docs/builders/builder-types/docker/) |
| RabbitMQ | **4.3.6**, maintained GA patches and subsequent supported series | Community support ends **2027-01-31** (rechecked 2026-10-16). This is a rolling-support exception, not LTS; no newer stable series is listed at review time. Native quorum delayed retry requires 4.3+. [Support timeline](https://www.rabbitmq.com/release-information), [retry feature](https://www.rabbitmq.com/docs/quorum-queues#delayed-retry) |
| Erlang/OTP | **27.3.4.19**, bundled by the selected official RabbitMQ image | This corrects the draft's 28.x assumption. OTP 27 is supported by RabbitMQ 4.3.6; upgrade the broker/runtime image together. No fixed-date LTS is assumed. [Compatibility](https://www.rabbitmq.com/docs/which-erlang), [immutable image recipe](https://github.com/docker-library/rabbitmq/blob/a2d49841fbcf81713cf0e1c279facbce545e2292/4.3/ubuntu/Dockerfile), [OTP security policy](https://github.com/erlang/otp/security) |
| Debezium Engine, PostgreSQL connector and JDBC offset store | **3.7.0.Final**, embedded only in order | Latest stable series checked **2026-10-18**; PostgreSQL 18 and Java 17+ connectors are in the upstream matrix. No fixed community LTS horizon is assumed. Review monthly, next by **2026-11-18**, and qualify with the selected Quarkus, PostgreSQL and RabbitMQ versions. [Release and compatibility matrix](https://debezium.io/releases/3.7/) |
| PostgreSQL/operator image OS | Vendor-maintained **Debian 13 (trixie)** images | Full support through **2028-08-09**, Debian LTS through **2030-06-30**, subject to package/architecture coverage. The database/operator support window can expire first. [CNPG image baseline](https://cloudnative-pg.io/docs/1.30/release_notes/v1.30/), [Debian lifecycle](https://www.debian.org/releases/trixie/) |
| Application and migration image OS | **Canonical OpenJDK 25** on **Ubuntu 26.04 LTS (resolute)**; `ubuntu/jdk` and `ubuntu/jre` stable tracks, pinned by digest | Canonical advertises these tracks through **May 2031**. Native ARM64 base-image build/runtime checks passed on Java **25.0.4.1**. Requalify derived images separately; the runtime is shell-free and final images must explicitly select a non-root user. [JDK support](https://hub.docker.com/r/ubuntu/jdk), [JRE support](https://hub.docker.com/r/ubuntu/jre) |
| Schema migrations | **Flyway OSS 13.8.1** libraries on the selected Canonical OpenJDK 25 JRE; local image defined by `infra/images/flyway/Dockerfile`, pinned by manifest digest | The official Flyway image supplies libraries, drivers, configuration and licenses only. Frequent releases have no published fixed EOL; review monthly, first by **2026-10-30**, and requalify the final image and migration Jobs through `scripts/up.sh` on the lab. Application BOMs do not select the external migration runtime. [Distribution source](https://hub.docker.com/r/flyway/flyway), [release notes](https://help.red-gate.com/help/flyway-cli13/help_8.aspx?topic=release-notes-and-older-versions/release-notes-for-flyway-engine) |
| Host container runtime and node internals | Docker Desktop **4.92.0** for macOS arm64 with a 12 GB VM (12 CPUs, 2 GB swap); node internals updated with kind | The host now runs 4.92.0 after checksum/signature verification; its component versions are recorded in the lock. Its containerd 2.3.5 and the node image have advisory findings, accepted for the local lab. Docker has rolling support, not fixed-version LTS. [Release notes](https://docs.docker.com/desktop/release-notes/), [macOS support](https://docs.docker.com/desktop/setup/install/mac-install/), [kind base-image contract](https://kind.sigs.k8s.io/docs/design/base-image/) |

The lock records OCI index and native **linux/arm64** manifest/config digests for
the selected upstream images, including kind and the Canonical OpenJDK JDK/JRE pair.
Use their `reference` fields for deployment pins; tags identify what was reviewed.
Both Java images execute GA OpenJDK 25.0.4.1; the JDK passes a product build and
unit/integration checks, and the JRE starts all six skeleton JARs. Final application
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

### Step 3.9 application images

`infra/images/application/Dockerfile` builds the application images on the pinned
Canonical OpenJDK 25 JRE (`application_jre`, unchanged). The images use user
`10001:10001`, and all six start `quarkus-run.jar`. Until step 3.11, the product and
order images also contained kubectl **1.36.5** from `registry.k8s.io/kubectl` for
the generator launcher. Step 3.11 removed kubectl, its image pin, its VEX document
and its advisory records: product and order now reserve their generator IDs with Java
code in their own JVM.

On **2026-10-10**, Docker Scout **1.24.0** (Docker Desktop 4.92.0) scanned the
product, seeder and reader images. It indexed 265, 70 and 73 packages. On
**2026-10-11**, the rebuilt reader image with fault tolerance indexed 89 packages.
On **2026-10-13**, the images of step 3.11 indexed 187, 70 and 89 packages. The
product image lost the Go modules of kubectl and gained the three HttpComponents JARs
of step 3.11. Its only Go binary is now `/usr/bin/pebble`, and `/usr/bin/kubectl`
does not exist. Scout reports
no advisory for the Maven packages and for the six Ubuntu packages that it indexes:
nspr, nss, openjdk-25, pcsc-lite, sqlite3 and zlib. It does not index six other
Ubuntu packages of the base image ([gap](#os-packages-without-an-advisory-check)).
All critical and high advisories that Scout reports are in the Go binary of the base
image:

| Binary | Images | Package | Critical | High | Other |
|---|---|---|---|---|---|
| `/usr/bin/pebble` of the JRE base | All | Go stdlib 1.26.7 | 2 | 9 | 2 medium |

The scans until 2026-10-13 gave 2 critical, 8 high and 3 unrated advisories. On
**2026-10-18**, Scout reported 2 critical, 9 high and 2 medium advisories for the same
package in all four current images. The new high advisory CVE-2026-97032
(GO-2026-6617, an HTTP/2 server crash in `net/http`) is fixed in Go 1.26.9 and 1.27.2.

The newest `ubuntu/jre:25-26.04_stable` digest
(`sha256:a27b7c6734762d8c7d373018b6dd921a7d42a021490c1497ee9904ca06f21853`) changes
only pebble, to 1.33.1 with Go 1.27.1, and that pebble still has 2 critical and 8
high advisories. Thus the base pin stays the same as for the migration image.
Go 1.27.1 is also in the affected range of CVE-2026-97032, thus this digest does not
remove it.

Reviewed OpenVEX statements cover these findings. The document
`infra/images/application/vex/jre-pebble.openvex.json` contains 11 `not_affected`
statements for 11 CVE IDs. Each statement names the images and the exact package
version: the images replace the pebble entrypoint with Java, thus pebble never runs.

`up.sh` applies only statements with the author
`ShardShop image review`. `--vex-author` is a pattern, and the text alone also
accepts authors that contain it, for example `Not the ShardShop image review`. Thus
the gate uses the anchored pattern `^ShardShop image review$`. A new package version
or a new advisory has no statement, thus the gate stops again. Review the statements
with the JRE base by **2026-10-28**, and when the base image digest changes. Since
step 3.12, the same gate also examines the [migration image](#current-migration-runtime),
which contains the same pebble binary. Version 3 (2026-10-14) uses named Docker
product PURLs without a tag, so Scout matches Skaffold content tags as well as the
older fixed tags. The exact Go package version and ten CVEs stay unchanged.
A newly built image passed; wrong product-name and package-version controls failed.
Version 4 (2026-10-18) adds CVE-2026-97032 with the same products and justification.
The vulnerable HTTP/2 server code is in `/usr/bin/pebble`, which never runs. Before
this statement, the gate stopped `up.sh` with exit 2. After it, all four current
images passed the gate.
Review these recipe-specific statements whenever the Java entrypoint changes too.

#### OS packages without an advisory check

The JRE base records 12 Ubuntu packages in `/usr/share/rocks/dpkg.query`.
`/var/lib/chisel/manifest.wall` records 11 of them, without the JRE package
`openjdk-25-jdk-headless`, thus a complete check reads both files. Docker Scout does
not index six of the 12: libc6
**2.43-2ubuntu2.4** (glibc), libgcc-s1, libstdc++6 and gcc-16-base
**16-20260322-1ubuntu1**, base-files **14ubuntu6.2** and base-passwd **3.6.8**.
Thus the inventory of `up.sh` does not show them, and its gate
does not examine them.

On **2026-10-10**, OSV's Ubuntu 26.04 data listed 14 advisories for these images.
Docker Scout reports none of them:

| Source package | Version | Advisories |
|---|---|---|
| glibc | 2.43-2ubuntu2.4 | 6 |
| openjdk-25 | 25.0.4.1+1-1~26.04.4 | 7 |
| zlib | 1:1.3.dfsg+really1.3.1-1ubuntu3.1 | 1 |

Ubuntu gives all 14 the priority medium, and none has a fix for 26.04. Their CVSS 3.1
vectors give six of them a high base score of 7.0–8.1: five openjdk-25 advisories
and the zlib advisory. OSV listed no advisory for gcc-16, base-files, base-passwd,
nspr, nss, pcsc-lite and sqlite3. No reviewed statement covers these advisories.
`SBOM-AND-RUNTIME` in the lock stays open and blocks step 4.9, which builds the other
images with the same script. It lists the advisory IDs. Close it
only when a check covers every package of the base image and a review handles these
advisories. [OSV](https://osv.dev/),
[Ubuntu CVE tracker](https://ubuntu.com/security/cves).

### Step 6.5 request balancer candidates

Step 6.5 needs a request-level balancer for HTTP/2, because a Kubernetes Service
balances only TCP connections. The review on 2026-10-08 found these candidates:

- Istio ambient mode with a waypoint: Istio 1.30 supports Kubernetes 1.32–1.36.
  This is the candidate.
- Envoy Gateway had no supported release for Kubernetes 1.36.
- Stable Linkerd releases are commercial.
- Cilium replaces the kind network plugin. Thus it needs a new cluster without the
  current PVCs.

Step 6.5 selects the component and records its versions, support window and
resource use here.

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
JBoss Log Manager **3.2.2.Final** and SLF4J **2.0.18**. The original skeletons did
not resolve platform-managed Netty **4.1.138.Final**. Step 3.6 enables the product
HTTP runtime with the family override described below.
Keep plain domain/routing tests independent of the framework, pin Testcontainers
images, and audit any retained Lombok processor against Java 25. Add only the
dependencies a module uses. [REST](https://quarkus.io/guides/rest/),
[datasources](https://quarkus.io/guides/datasource/),
[testing](https://quarkus.io/guides/getting-started-testing/).

### Step 4.1 order provider qualification

On **2026-10-16**, order adds the already-qualified Quarkus REST/Jackson and
PostgreSQL JDBC/Agroal extensions, with Mockito only for tests. No artifact
versions or lock pins change. Its packaged runtime contains **126 JARs**, all
byte-identical to the corresponding product runtime JARs. The `audit` profile
passes and emits the effective model, dependency tree, plugin report and a
244-component SBOM under `shardshop-order/target/audit/`. The
[OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/) reports no
advisories for its **123 third-party runtime coordinates** on this date; the
three internal ShardShop libraries are excluded from that public lookup.
The existing family review deadlines still apply.

After the validation fixes, qualification passes 165 order unit/provider tests, 32
packaged startup checks and all 51 Cucumber scenarios, including nine order
scenarios. The bounded JDBC phase engine that product and order now share in
`shardshop-common` uses only the JDK's `java.sql` module, thus common gains no
dependency. HTTP/1.1 is explicit; HTTP/2 qualification remains step 6.5. No order
container image or Kubernetes deployment is introduced before step 4.9.
The CycloneDX goal emitted Maven's existing blocked HTTP snapshot-metadata
warning for the internal parent POM; SBOM generation and validation succeeded.
See [step 4.1 evidence](CHANGELOG.md#step-41) for the commands.

### Step 3.6 provider dependencies

The product provider adds the platform's REST/Jackson and PostgreSQL JDBC/Agroal
extensions. On **2026-10-07**, its runtime selects Vert.x **4.5.34**, Agroal
**3.2.1**, pgJDBC **42.7.13** and Jackson **2.21.7**. Mockito **5.21.0** is test-only.
Quarkus remains **3.40.1**; no application migration extension is added. The lock
records Maven Central checksums and local SHA-256 hashes of the new runtime
artifacts. Review these families again by **2026-11-04**.
Product disables the inherited Lombok annotation processor because its sources
are plain Java; Quarkus still performs its normal build-time augmentation.

ShardShop imports the Netty **4.1.139.Final** BOM before the Quarkus BOM to apply
the October 6 HTTP/HTTP2 security fixes consistently across the runtime family.
The previous 4.1.138 pin belonged to the earlier CDI-only skeletons, which did
not expose this HTTP stack. Netty 4.1 maintenance ends **2027-07-01**; qualify a
compatible maintained successor before that date.
[Netty release and security notes](https://github.com/netty/netty/releases/tag/netty-4.1.139.Final).

Vert.x 4.5.34 is beyond the affected ranges of the reviewed redirect-header and
SNI-cache advisories. Its upstream policy supports the previous major for up to
two years after the next major's release; review the Quarkus-compatible line
monthly. pgJDBC 42.7.13 includes the channel-binding enforcement fix from 42.7.12.
Agroal publishes no fixed EOL and had no published GitHub advisories at review;
its use remains tied to the maintained Quarkus platform and monthly review.
[Vert.x support policy](https://github.com/eclipse-vertx/vert.x/security),
[redirect advisory](https://github.com/eclipse-vertx/vert.x/security/advisories/GHSA-5x9c-p4p5-8v9w),
[SNI advisory](https://github.com/eclipse-vertx/vert.x/security/advisories/GHSA-3g76-f9xq-8vp6),
[pgJDBC security](https://jdbc.postgresql.org/security/),
[Agroal releases](https://agroal.github.io/),
[Agroal advisories](https://github.com/agroal/agroal/security).

### Step 3.2 ID parsing dependency

On **2026-10-06**, `shardshop-common` centralized ID validation and the shared
SHA-256 calculation. All six applications and `shardshop-sharding` depend on
this ordinary Java library. Common holds the direct dependency on
`com.fasterxml.jackson.core:jackson-core`, managed at **2.21.7** by the existing
Quarkus BOM. Only the streaming API is needed for strict JSON token validation;
HTTP providers and their framework extensions remain later steps. The artifact
matches Maven Central's published SHA-1; its independently computed SHA-256 is
recorded in the lock.

Jackson 2.21 is an open LTS branch; **no published fixed EOL; upstream maintenance
checked on 2026-10-06**, with review by **2026-11-04**.
[Release status](https://github.com/FasterXML/jackson/wiki/Jackson-Releases) and
the [Core advisory list](https://github.com/FasterXML/jackson-core/security)
were checked. Version 2.21.7 includes the September fixes for
[unbounded error-token growth](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf)
and [numeric-pattern backtracking](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89).
The plugin-only Jackson
2.22.3 override remains separate from this application dependency.

At step 3.2, only common declared the existing BOM-managed Mockito **5.21.0** test dependency
to exercise unavailable SHA-256. Consumer tests cover routing and currency behavior
without mocking common's internals; the inherited plugin test agent remains.
No runtime contains Mockito. The step 3.2 audit build passed **186 unit/startup
tests and 10 packaged routing tests**, without warnings and with **100% line and
branch coverage** across all eight Java modules. The parser regression suite ran
once in common; routing and currency suites still tested their own behavior.
The step 3.2 SBOMs contained 14 components for common, 11 for routing, 142 for product/order,
and 141 for ledger/seeder/reader/producer (including test dependencies).
Every packaged application contains the common JAR. At step 3.2 common had only
Jackson at runtime; ledger and workloads contain neither sharding nor Snowflake.
The exact build command is in [README](README.md#id-validation-and-currency-selection-step-32).

### Step 3.5 workload dataset dependencies

`shardshop-datasets` is an ordinary Java library under `shardshop-workload` with
no runtime dependencies. Only the three workload applications consume it.
Common adds Jackson Databind **2.21.7** for strict tree decoding; its annotations
resolve to **2.21**. Workloads use the JDK HTTP client for creation and key-based
lookups. No REST extension, Vert.x or Netty runtime is introduced at this step.
The client bounds response bytes and the complete asynchronous exchange, and
rejects duplicate JSON members, malformed UTF-8 and coerced identifiers.

The September Databind advisories are patched in 2.21.7:
[unknown type-ID retention](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-wv8q-qhhj-9h54)
and [forward-reference completion](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24).
The new clients use tree decoding without polymorphic binding. On **2026-10-07**,
the Databind and annotations artifacts in the lock's `step_3_5_workload_dependencies`
matched Maven Central's published SHA-1 checksums; independent SHA-256 values
are recorded there. Review these families with the platform by **2026-11-04**.
The [step 3.5 audit command](README.md#workload-owned-datasets-and-service-issued-ids-step-35)
rebuilds all six applications because common changed.

The catalog-v1 and buyers-v1 datasets keep the library free of runtime dependencies. Mockito
is test-only for resource IO failure checks (13 dataset SBOM components including
test dependencies). The 2026-10-07 audit passes 559 unit/client/startup tests and
48 packaged checks, with 100% line/branch coverage in ten Java modules. All 3,450
creation payloads pass their schemas, and regenerated buyer files retain their
hashes. Source artifacts, fixed regional pools and generated TSV checksums are
recorded in the workload module's
[`source-manifest.json`](shardshop-workload/shardshop-datasets/source-manifest.json).
Its [`SOURCES.md`](shardshop-workload/shardshop-datasets/SOURCES.md) records
attribution, ranking limits and the public-domain US Census, CC BY 4.0 Onomaverse
and MIT Faker name sources; these notices and source licenses are packaged with
the dataset JAR. Buyer contacts use reserved example domains and fictional NANPA
numbers. Both datasets are the initial unreleased v1; edits before their first
release or use keep that version. Source extraction tools do not become Java
build/runtime dependencies.

### Step 3.8 reader HTTP client

The product reader adds the platform-managed Apache HttpClient **5.6.4** (classic
API) with HttpCore **5.4.3** and HttpCore H2 **5.4.3**. Its SLF4J API resolves to
the platform's **2.0.18** and logs through JBoss Log Manager. The JDK HTTP client
has no setting for a connection limit or a connection lifetime, which the
[HTTP connection policy](ARCHITECTURE.md#http-connection-policy) requires. Only the
reader uses this client; the seeder and the order producer keep the JDK client in
common. No Vert.x or Netty runtime enters a workload package.

HttpClient 5.6.x and HttpCore 5.4.x are the current stable HttpComponents lines;
HttpClient 4.5.x receives only security fixes. On **2026-10-08**, OSV listed no
advisories for the three versions, and the cached artifacts matched Maven
Central's published SHA-1 checksums. The lock's `step_3_8_reader_dependencies`
records them with independent SHA-256 values. Review these families with the
platform by **2026-11-05**.
[HttpComponents status](https://hc.apache.org/status.html), [OSV](https://osv.dev/).

### Step 3.9 product metrics

Product adds the platform-managed `quarkus-micrometer-registry-prometheus`
**3.40.1** for its per-pod request counters. The runtime adds Micrometer **1.17.1**
(core, commons, observation and the simpleclient Prometheus registry), Prometheus
simpleclient **0.16.0** with its common and tracer artifacts, HdrHistogram **2.2.2**,
LatencyUtils **2.0.3** and Vert.x codegen **4.5.34**, which `quarkus-micrometer`
requires. The product SBOM has 251 components, 237 before.

Micrometer 1.17.x has community support until **2027-07**. Prometheus simpleclient
0.16.0 is from **2022-06-15** and is the last release of the 0.x line; the
Prometheus Java client continues as the 1.x line. The Quarkus 3.40 extension uses
the simpleclient registry, and the platform BOM selects these versions. Thus
simpleclient is not a maintained release under this policy. It stays only as a
transitive dependency of the platform extension, until a Quarkus release moves the
extension to the 1.x client. Vert.x codegen comes from the Vert.x 4.5.34 release that
[step 3.6](#step-36-provider-dependencies) qualified, thus that support policy
applies. On **2026-10-10**, OSV listed no advisories for the 14
artifacts, and the cached artifacts matched Maven Central's published SHA-1
checksums. The lock's `step_3_9_product_metrics` records them with independent
SHA-256 values. Review these families with the platform by **2026-11-04**.
[Micrometer support](https://micrometer.io/support/),
[Prometheus Java client](https://github.com/prometheus/client_java),
[OSV](https://osv.dev/).

### Step 3.9 reader fault tolerance

The reader adds the platform-managed `quarkus-smallrye-fault-tolerance` **3.40.1**
for its wait for the complete catalog. The runtime adds SmallRye Fault Tolerance
**6.11.4** (seven artifacts), the MicroProfile Fault Tolerance API **4.1.2**,
SmallRye Context Propagation **2.3.0** (three artifacts), Mutiny context propagation
**3.3.0**, the Quarkus Mutiny and context propagation extensions **3.40.1**, and the
Commons Logging bridge `commons-logging-jboss-logging` **2.0.0.Final**. Thus the
package has 66 artifacts, 50 before. The reader SBOM has 165 components, 150 before,
because it already listed the bridge as a test dependency. The package contains no
Vert.x or Netty.

SmallRye released Fault Tolerance 7.0.0 and the maintenance releases 6.11.4, 6.10.2
and 6.9.4 on **2026-09-16**, thus the 6.11 line is maintained. The MicroProfile
Fault Tolerance API 4.1.2 is the newest release of its specification line.
SmallRye Context Propagation 2.3.0 is from **2025-11-25**, and 2.4.0 followed on
**2026-09-24**. That project publishes no support term, and the Quarkus 3.40.1
platform selects 2.3.0. Thus it stays without an override until a Quarkus patch
moves it. The bridge 2.0.0.Final from **2026-01-26** is the newest release of
JBoss Logging's Commons Logging bridge. Product also contains it.
On **2026-10-11**, OSV listed no advisories for the 16 artifacts, and the
cached artifacts matched Maven Central's published SHA-1 checksums. The lock's
`step_3_9_reader_fault_tolerance` records them with independent SHA-256 values.
Review these families with the platform by **2026-11-04**.
[SmallRye Fault Tolerance releases](https://github.com/smallrye/smallrye-fault-tolerance/releases),
[SmallRye Context Propagation releases](https://github.com/smallrye/smallrye-context-propagation/releases),
[OSV](https://osv.dev/).

### Step 3.10 integration test dependencies

The test-only module `shardshop-testing` adds these test-scope dependencies. None
of them enters an application package or runtime.

| Family | Selection and qualification |
|---|---|
| Testcontainers | **2.0.5**, the version of the Quarkus 3.40.1 platform and the newest release (2026-04-20): `testcontainers`, `testcontainers-jdbc`, `testcontainers-database-commons` and `testcontainers-postgresql`, with docker-java **3.7.1** (API, transport and zero-dependency transport), duct-tape **1.0.8** and JetBrains annotations **26.1.0** |
| JNA | **5.8.0** (2021-03-22), which the platform selects. docker-java 3.7.1 declares 5.18.1, and 5.19.1 (2026-06-12) is the newest release. As with SmallRye Context Propagation in step 3.9, it stays without an override until a Quarkus patch moves it |
| Flyway Java API | **13.8.1** core and PostgreSQL plugin, the release of the lab's [migration image](#current-migration-runtime). The platform manages 12.0.0, thus the ShardShop parent pins `flyway.version` explicitly for the tests. The test classpath excludes the CockroachDB plugin, as the image does. Change the image and this property together |
| Cucumber JVM | **8.0.4** (2026-10-02, the newest release of the 8.0 line from 2026-09-24): Java 17 baseline and JUnit Platform 6.1.2. Its libraries declare version ranges for each other; the imported `cucumber-bom` 8.0.4 manages 31 exact versions, and 23 of them resolve in the module. The reports need Jackson 2 with the platform's `jackson-datatype-jdk8` **2.21.7**. The repository root keeps 7.34.6 for its other modules |
| PicoContainer | **2.16** (2026-08-15) through `cucumber-picocontainer`, which gives the step classes of one scenario the same state objects, as `cucumber-spring` does in the Spring modules |
| JUnit Platform Suite | **6.1.3**, managed by the platform |

The logging, JDBC and Jackson libraries of the module also resolve in other
ShardShop modules. On **2026-10-12**, the 39 artifacts that no other ShardShop
module resolves matched Maven Central's published SHA-1 checksums, and OSV listed
no advisories for them. The lock's `step_3_10_integration_test_dependencies`
records them with independent SHA-256 values. Review these families with the
platform by **2026-11-04**, and Flyway with its image by **2026-10-30**.
[Testcontainers releases](https://github.com/testcontainers/testcontainers-java/releases),
[Cucumber JVM releases](https://github.com/cucumber/cucumber-jvm/releases),
[OSV](https://osv.dev/).

The scenarios of step 3.10 use one image. The `postgresql_test` pin moves to the
2026-10-07 build of `postgres:18.6-trixie` (PostgreSQL `18.6-1.pgdg13+2`, gosu 1.19), with
newer Debian packages than the 2026-09-28 pin. Docker Scout reports 6 critical, 36
high, 28 medium and 49 low advisories for it: Go stdlib 1.24.6 of gosu (4 critical,
29 high), gnutls, libxml2, cyrus-sasl2 and zlib; no newer build fixes them. The user
accepted them on 2026-10-12 for local test containers that run only during an
integration build, with the port bound to `127.0.0.1`. Recheck them by
**2026-10-28**. Testcontainers runs without its Ryuk reaper
(`TESTCONTAINERS_RYUK_DISABLED=true`): Ryuk 0.14.0, the newest release, publishes
its port on all host interfaces, runs privileged with the Docker socket, and
contains Go stdlib 1.23.12 with 4 critical and 29 high advisories. A JVM shutdown
hook removes the container instead.

### Step 3.11 generator allocation dependencies

Product and order reserve their generator ID in their own JVM through the Kubernetes
API. The idgen library declares the platform-managed Apache HttpClient **5.6.4** (the
client of the reader, [step 3.8](#step-38-reader-http-client)) and
jackson-databind **2.21.7**. The product and order packages add only httpclient5
5.6.4, httpcore5 5.4.3 and httpcore5-h2 5.4.3; they already contained jackson-databind
and slf4j-api 2.0.18. On **2026-10-12**, the cached JARs matched the SHA-256 values
that the lock records for step 3.8, and OSV listed no advisories for them and for
jackson-databind 2.21.7. The lock's `step_3_11_generator_allocation` refers to these
records. The product and order images no longer contain kubectl
([images](#step-39-application-images)).

The generator scenarios of `shardshop-testing` need a real Kubernetes API. On
**2026-10-12**, the user selected k3s **v1.36.5+k3s1**
(`docker.io/rancher/k3s`, index
`sha256:057cfd5a9d51b89f1f0ee139c28d5d5fdd5aa7ddeac2742a0ba90c6dbc0329bb`, built
2026-09-30), the newest patch of the lab's Kubernetes 1.36 minor:

- A plain Testcontainers `GenericContainer` runs `server --disable-agent`. Without an
  agent, k3s runs no kubelet and no containerd, thus the container is not privileged.
  Its API port is bound to `127.0.0.1`. Each scenario starts its own container, which
  is ready in about 5 seconds with about 300 MiB of memory.
- The Testcontainers `K3sContainer` module (2.0.5, managed by the platform) is not
  used: it always sets privileged mode, the host cgroup namespace and a read-write
  `/sys/fs/cgroup` bind.
- Docker Scout 1.24.0 reports 12 critical, 31 high, 23 medium and 8 low advisories
  for the image. The alternative upstream images kube-apiserver v1.36.5 (3 critical,
  20 high) and etcd 3.6.15-0 (3 critical, 13 high) need a custom certificate and
  flag harness. The user accepted the findings for local test containers that run
  only during an integration build. Recheck them by **2026-10-28**.

The scenarios apply the real RBAC and retention policy of the chart. Thus the
`integration` profile of `shardshop-testing` runs `helm template` with the pinned
exec-maven-plugin **3.6.4**, and `helm` comes from the user's `PATH` (tested with
4.3.0), as for the lab scripts. Helm 4.3.0 does not combine `--show-only` with
`--output-dir`, thus it writes all infrastructure templates into `target/chart`.
[Testcontainers releases](https://github.com/testcontainers/testcontainers-java/releases),
[k3s releases](https://github.com/k3s-io/k3s/releases), [OSV](https://osv.dev/).

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
milestone. `shardshop-idgen`, in `shardshop-core/idgen`, declares the dependency
for its generator implementation and unit tests. Product/order use that library
and its shared CDI producer to generate IDs for all creation requests. Common and sharding have no generator dependency.
Workloads consume service-issued IDs and ledger consumes order-reserved result
IDs; neither may depend on Snowflake or access the generator allocator.
Do not invent a supported successor version or silently substitute a different
generator library. A critical unresolved defect requires a documented remediation
before deployment. [Tagged POM](https://github.com/phxql/snowflake-id/blob/v0.0.2/pom.xml),
[tagged generator](https://github.com/phxql/snowflake-id/blob/v0.0.2/src/main/java/de/mkammerer/snowflakeid/SnowflakeIdGenerator.java).

Step 3.3 qualified this exact JAR on OpenJDK **25+36-3489** on **2026-10-06**;
its SHA-256 matches the lock. The generator implementation and unit tests are
maintained once in `shardshop-idgen`, which resolves Snowflake and uses
BOM-managed Mockito **5.21.0** for controlled-clock tests. Product/order alone
package Snowflake in their application runtimes. The generator classes use the
`dev.nklip.javacraft.shardshop.idgen` package to match their module, with their
SmallRye mapping, CDI producer
and injection tests in `dev.nklip.javacraft.shardshop.idgen.config`. Sharding owns
routing and its `dev.nklip.javacraft.shardshop.sharding.config` wiring. Each library
owns its relevant test properties and `META-INF/beans.xml` for discovery from its
JAR. Neither library depends on the other; only sharding depends on common.
Routing and ID domain classes have no framework imports, and common has no Quarkus
or generator dependency. The three libraries live in the `common`, `idgen` and
`sharding` directories under the `shardshop-core` POM parent and aggregator.
Mockito remains absent from every runtime. The six-application clean audit
passes **215 unit/startup tests and 40 packaged startup
checks**, without warnings and with **100% line and branch coverage** across the
nine Java modules. Common contributes **79 tests**, idgen **30** and sharding **54**.
Concurrency, throwing sequence exhaustion, clock failures,
backwards time and timestamp boundaries pass. SBOMs contain **144** components
for product/order, **143** for idgen, **141** for sharding, ledger and each workload,
and **14** for common. Generator classes and Snowflake are absent from common,
sharding, ledger and workload classpaths/graphs/SBOMs. Compile probes reject
generator and configuration imports in ledger and every workload, and Maven
Enforcer rejects direct (including optional) and transitive idgen/sharding/Snowflake
dependencies in unauthorized consumers, including ledger and workloads. An isolated
optional-idgen consumer fails the direct dependency rule. Idgen's own isolation
rules reject common and sharding through direct, optional and transitive
dependencies; all six negative fixtures fail at the expected rule. The
[test JVM option reproduction](README.md#dependency-and-test-validation) records why the core
Quarkus tests retain `--add-opens`. Commands are in the
[step 3.3 runbook](README.md#bounded-snowflake-generation-step-33).

Step 3.4 adds JDK-only allocation/launcher code to the same idgen JAR, with no new
Java dependencies or artifact pins. Its Java 25 instance entrypoint is tested
both directly and through the actual `java` launcher. The **2026-10-07** checks use
OpenJDK **25+36-3489**, Maven **3.9.16**, host kubectl **1.36.3**, and the existing
Kubernetes **1.36.4** lab. The temporary container drill copies kubectl from the
locked kind node image into the locked Canonical OpenJDK 25 JRE image; both source
digests remain unchanged. That image exists only for verification and does not
replace the final service image qualification in steps 3.9/4.9. The
[runbook](README.md#generator-allocation-at-jvm-startup-steps-34-and-311) records
the scoped Maven and live verification commands. Step 3.11 replaced the launcher, the
kubectl calls and the drill with a reservation in the application JVM
([dependencies](#step-311-generator-allocation-dependencies)).

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

### Step 3.12 contract test dependencies

Step 3.12 replaced the Python contract tools (OpenAPI Spec Validator 0.9.0, JSON
Schema 4.26.0 and PyYAML 6.0.3 of step 3.1) with Maven tests. On **2026-10-13**, the
user selected the platform-managed Vert.x validator. These test-scope dependencies
enter no application package:

| Family | Selection and qualification |
|---|---|
| Vert.x JSON Schema and OpenAPI | **4.5.34**, the Vert.x release of the [step 3.6 runtime](#step-36-provider-dependencies): `vertx-json-schema` validates JSON Schema 2020-12, and `vertx-openapi` contributes the bundled OpenAPI 3.1 schema. 4.5.35 is the newest 4.5 patch; the platform selects 4.5.34 |
| SnakeYAML | **2.6** (2026-02-26), which the platform selects and `vertx-openapi` declares. 2.7 (2026-08-30) is the newest release. It stays without an override until a Quarkus patch moves it |
| Vert.x core and Netty | 4.5.34 and 4.1.139.Final, already in the product runtime. They enter the test classpath of common, order, ledger and `shardshop-testing` |
| Jackson | 2.21.7, the platform version, in the test scope of `shardshop-datasets` |

The test JAR of `shardshop-common` holds `ContractDocument` and `ExampleIds`. Product,
order, ledger and the scenarios of `shardshop-testing` use them. `ContractDocument`
loads the bundled meta-schemas through the class loader. Thus no Vert.x event loop
starts, and Netty logs no warning. It corrects four gaps of Vert.x 4.5.34, which a
scratch harness showed against the cases of the deleted `verify-contracts.py`:

- The `date-time` format check is a loose pattern that accepts 2026-02-30 and hour
  25. A strict ISO-8601 calendar parse, through the `JsonFormatValidator` hook of the
  repository, rejects them.
- The integer check compares the remainder of a `BigDecimal` with its scale. Thus
  `1E+3` and a normalized `2147483640.0` are not integers. Integral numbers are given
  to Vert.x as `BigInteger`.
- The `email` format check reads the first character, thus an empty string throws.
  The helper reports that value as invalid.
- `OpenAPIContract.from` rejects the relative server URL `/` of the product contract,
  and the OpenAPI 3.1 schema accepts any schema object. The helper validates each
  document against the bundled OpenAPI 3.1 schema, and each schema object against the
  bundled JSON Schema 2020-12 meta-schema. It also checks path parameters, repeated
  operation IDs and references outside the document.

Vert.x evaluates `pattern` with JDK regular expressions, in which `\s` matches only
ASCII whitespace. Thus a name of only U+00A0 matches the schema, although the
product provider rejects it, as Python did. No contract test uses such a value. The
networknt validator 2.0.8 was the alternative: it is outside the platform, needs a
vendored OpenAPI 3.1 schema and also uses JDK regular expressions.

The three artifacts that no other ShardShop module resolves (`vertx-json-schema`,
`vertx-openapi` and `snakeyaml`) matched Maven Central's SHA-1 checksums on
**2026-10-13**, and OSV listed no advisories for them and for `vertx-core` 4.5.34.
The lock's `step_3_12_contract_test_dependencies` records them with independent
SHA-256 values. Review them with the platform by **2026-11-04**.
[Vert.x support policy](https://github.com/eclipse-vertx/vert.x/security),
[SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml), [OSV](https://osv.dev/).

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
  feasible. CNPG is already inside that window and needs an active upgrade item;
  begin RabbitMQ migration planning by 2026-11-02. Complete upgrades before EOL; do not
  substitute an unreleased successor or count a roadmap date as availability.
- Qualify RabbitMQ's supported successor by **2027-01-15**, before its January
  2027 EOL. Review the next stable CNPG release immediately on availability and upgrade
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
No image digest, database, migration or Kubernetes resource changed. Step 3.9
qualifies the final application images and their advisory inventory
([record](#step-39-application-images)).

### Step 1.1 verification record

The 2026-10-13 lifecycle refactor also uses the host's `jq` from `PATH`
(tested with Apple's `jq-1.7.1-apple`) for selecting rendered inventory fields
and CRI image digests. No binary is downloaded or pinned by the repository;
Maven owns the semantic safety and readiness checks.

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

`scripts/up.sh` invokes Skaffold to build the local ARM64 image with
Docker BuildKit and `SOURCE_DATE_EPOCH=0`, scans it with the gate of the application
images, then loads it into kind. Before it registers the digest on a node, it verifies
the pinned loaded platform manifest digest of the chart values. Pin review compares
the same artifact's platform manifest and config digests, so an archive index change
does not by itself change the reviewed image. Helmfile then runs the migration
release only after the retained topology, allocator and infrastructure gates pass.
`scripts/cluster-create.sh` provisions that foundation before the first `up.sh`.
Docker, Buildx, Docker Scout, kind, Skaffold, Helm, Helmfile and Maven are requirements
for the local build and migration workflow. Review changed build inputs and the
resulting digest together.

The final ARM64 image reports `java.vendor=Ubuntu` and OpenJDK
`25.0.4.1+1-1-26.04.4-Ubuntu`. A Docker Scout scan of the trimmed image on
2026-09-30 indexed 74 packages and reported no findings; the earlier untrimmed
image had 417 packages and 12 findings (4 high, 8 medium), all in copied libraries
the migrations never used. On **2026-10-13**, the gate of step 3.12 scanned the same
image again: 74 packages, and only the Go stdlib 1.26.7 of `/usr/bin/pebble` (2
critical, 8 high, 3 unrated), which the reviewed [pebble statements](#step-39-application-images)
cover. On **2026-10-18**, the same image had 2 critical, 9 high and 2 medium advisories
in that package. Version 4 of the statements covers the new high advisory
CVE-2026-97032. Recheck the final image on the next Flyway/base refresh, by 2026-10-28. The
lock records the actual CRI and config digests. The historical upstream-image scan
below is separate evidence for the superseded image.

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


### Step 4.3 RabbitMQ qualification

On 2026-10-16, the [release timeline](https://www.rabbitmq.com/release-information)
still lists RabbitMQ **4.3.6** as the newest maintained GA and now gives **2027-01-31**
as community EOL. The selected management image was rebuilt on 2026-10-14; its
index, ARM64 manifest and config identities are recorded in the lock. It bundles
**Erlang/OTP 27.3.4.19** and Ubuntu 24.04. Recheck on **2026-11-16** and qualify a
supported successor by **2027-01-15**.

The AMQP client is **5.37.0**, published on 2026-10-01; step 4.4 also uses it in
the order runtime. Its
[release notes](https://github.com/rabbitmq/rabbitmq-java-client/releases/tag/v5.37.0)
confirm compatibility with 5.36.0 and the deprecated JSON-reader fix. Cached
Java-client overview pages still list 5.36.0; current Maven Central metadata and
the immutable release were used. Central JAR/POM SHA-1 sidecars match, SHA-256
hashes are in the lock, and the OSV query has no finding for 5.37.0. Tests use the
default socket transport; this does not qualify the optional Netty transport.

Docker Scout reports 238 packages and 97 advisories: 6 critical, 34 high,
53 medium and 4 low. Every critical/high finding is in Go 1.22.2 embedded in
`gosu`. The deployed command directly starts `rabbitmq-server` as an arbitrary
non-root UID; it never uses `gosu` or the vendor entrypoint. The image retains
that unused binary, so the unfiltered scan still reports these findings. This
assessment applies to the restricted local broker and must be revisited before
broader deployment. The remaining OS package findings are medium/low.


The broker acceptance suite verifies the runtime bundle under UID 1000780001
(absent from the image's passwd file), using the chart's startup command and
read-only mounts. Both source queues survive container replacement with
unacknowledged persistent messages and parked messages. The observed reject
sequence is five delayed redeliveries, then a sixth rejection parks the message;
`basic.nack` leaves the failure count unchanged. Capacity checks observe the
allowed one-message overshoot before negative confirmation and verify eventual
forwarding after a full parking queue recovers on the native 180-second retry.

The test-module audit generated a 93-component SBOM with client 5.37.0 and the
existing platform-managed Netty 4.1.139.Final. The audit succeeded; inherited
Flyway repository metadata checks returned 401 for local reactor snapshot POMs,
which were already resolved from the local cache. Artifact checksum and client
advisory checks used Maven Central and OSV directly.

### Step 4.4 embedded CDC dependencies

On **2026-10-18**, [Debezium's release matrix](https://debezium.io/releases/3.7/)
lists **3.7.0.Final** (released 2026-09-29) as the latest stable release, with
PostgreSQL 18 in its tested database matrix and Java 17+ for connectors. Order
pins the embedded engine, PostgreSQL connector, JDBC store and storage-common to
that version. The JDBC artifact declares storage-common as provided; the embedded
host must include it explicitly. No Debezium Server, Kafka broker or Quarkus CDC
extension is deployed. Review this rolling community release by **2026-11-18**.

The effective Quarkus 3.40.1 graph retains Kafka Connect/client **4.2.1**, Jackson
**2.21.7** and PostgreSQL JDBC **42.7.13**, with RabbitMQ client **5.37.0**. The order
audit produces a **304-component SBOM**. All 20 Debezium, Kafka and LZ4 JARs match
Maven Central's published SHA-1 sidecars; their independently computed SHA-256
hashes are in the lock. OSV queries for all **202 third-party runtime JAR
coordinates** return no findings after the order-local LZ4 override below.

Kafka introduced `at.yawk.lz4:lz4-java` **1.11.1** from platform management. OSV
reported GHSA-343h-94h5-c4wr, GHSA-4v53-57pg-c464, GHSA-6cx8-rjf8-pr8g,
GHSA-gm45-99xc-r7wv and GHSA-mcr4-qmvw-px4g. The order POM pins the maintained
**1.12.0** release, which includes the fixes through 1.11.4; other modules retain
their existing management. [Published releases](https://repo.maven.apache.org/maven2/at/yawk/lz4/lz4-java/maven-metadata.xml),
[upstream advisory](https://github.com/yawkat/lz4-java/security/advisories/GHSA-343h-94h5-c4wr).

The embedded host supplies a serial batch consumer, bounded queues and snapshot
fetches, a pre-provisioned publication, failover-enabled retained slots, and the
JDBC offset store. Its custom offset insert also records permanent initialization
history in the same transaction. A 10-second Debezium heartbeat runs
`pg_logical_emit_message` as its action query, so an empty snapshot and an idle
outbox still save and confirm positions. All fifteen CDC scenarios and the
accepted-order relay scenario pass using
the packaged order JVM on Java 25 with the pinned PostgreSQL 18.6 and RabbitMQ
4.3.6 test images; the engine and
storage APIs follow the [Engine documentation](https://debezium.io/documentation/reference/3.7/development/engine.html)
and [JDBC storage contract](https://debezium.io/documentation/reference/3.7/configuration/storage.html).
The application image rollout and synchronized-standby failover remain the later
deployment and failover milestones.

### Step 4.5 ledger consumer dependencies

On **2026-10-18**, ledger adds the already qualified RabbitMQ Java client **5.37.0**
and Quarkus **3.40.1**'s PostgreSQL JDBC extension, using JDBC **42.7.13** and the
platform-managed Agroal pool. It introduces no generator, shard-routing, Kafka or
Debezium dependency. The ledger audit produces a **193-component SBOM**; queries
to OSV for all **81 third-party runtime JAR coordinates** return no findings.
All five RabbitMQ, PostgreSQL JDBC and Agroal JARs match Maven Central's published
SHA-1 checksums; their SHA-256 hashes are recorded in the lock. RabbitMQ client
release and support evidence remains in the step 4.3 qualification above.
