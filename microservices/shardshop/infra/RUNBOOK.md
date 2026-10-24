# Infrastructure runbook

[ShardShop](../README.md) › [Infrastructure](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

This runbook describes the phases of the lab lifecycle, the application release,
the broker, and the stop, destruction and recovery of the lab. The
[README](README.md) shows how to start the lab and how to monitor it.

## Kind topology

[`infra/kind.yaml`](kind.yaml) defines one control plane and three workers,
one per instance of each three-instance shard, on the digest-pinned Kubernetes
1.36.4 node image, with the API server on 127.0.0.1. kind cannot add nodes, so a
cluster created with fewer workers must be deleted and recreated once.
`up.sh` compares the retained node names, roles, image pins and API port binding
before starting stopped containers. It rejects other kind creation settings,
including changed networking, mounts or ports; it never recreates retained nodes.
Manifests follow the [portability rules](../PLAN.md#portability-beyond-kind): a
shared chart in `infra/helm/shardshop/` with kind settings in `kind-values.yaml`.

The node image's advisories were reviewed on 2026-09-28 and accepted for this
loopback-only, disposable lab.

## Cluster creation

`cluster-create.sh` owns creation. It first caches the repository and microservices
parent POM metadata with non-recursive `install:install-file` calls, without
building either parent reactor. It builds, verifies and installs only the safety observer and its shared common
test-helper dependencies with
`mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Dshardshop.infrastructureOnly=true clean install -DinstallAtEnd=true`,
validates the pinned kind configuration, creates the four nodes, and prepares the namespace, credentials,
CloudNativePG, databases, RabbitMQ and allocator identity. It verifies DNS,
admission, database readiness and the broker's effective delivery topology.
It does not build/load application images, run Flyway or deploy
application workloads. On an existing cluster it resumes stopped nodes and safely
reconciles supported infrastructure changes after retained-state guards pass; it
never reinitializes retained allocator history.

Fresh allocator initialization belongs exclusively to this creation invocation;
`up.sh` never creates a cluster or initializes/replaces allocator history. If creation
fails after nodes exist, inspect its failed phase. Once both allocator objects exist,
`cluster-create.sh` can validate retained state and reconcile the foundation. Missing/partial
allocator state fails closed: recover independent backups for retained data, or
explicitly destroy and recreate a demonstrably unused failed bootstrap. Never treat
an existing cluster as fresh to bypass this guard.

Both creation and startup snapshot the shared chart, database migration files, inventory, environment
values, kind configuration, operator chart/values and Helmfile configuration once per
invocation; startup also snapshots Skaffold configuration. On retained clusters they
start only stopped nodes. After API readiness, both reject
changed published/reserved topology or invalid retained allocator state before
protected mutations. For Helm-owned databases, the retention check compares
protected settings against the stored infrastructure release manifest and checks
the live declared values for drift. API-added defaults are not treated as requested
changes. Missing/mismatched Helm ownership or history, pending revisions and a
revision changing during the check fail closed; completed failed revisions remain
retryable when their protected settings match. `cluster-create.sh` creates the namespace and missing credential
Secrets outside Helm, then syncs the infrastructure stage of
[`infra/helmfile.yaml.gotmpl`](helmfile.yaml.gotmpl). Helm waits up to 600
seconds for each release, installs CloudNativePG 1.30.1 from the vendored chart,
and reconciles the shared infrastructure chart. `cluster-create.sh` creates the `shardshop`
namespace with the `restricted` Pod Security label; the infrastructure release creates three
three-instance clusters (`shard-a`/US, `shard-b`/EU, `shard-c`/ASIA), and the single-instance
`ledger-db` cluster, plus the persistent [broker](#rabbitmq-broker).
It waits up to 300 seconds for every database cluster to be
Ready and for every database pod to use the requested PostgreSQL image, operator
image and resource budget, accounting for Kubernetes defaulting an omitted resource
request to its limit. Explicit requests still take precedence. It verifies every
`-rw` Service, every configured PostgreSQL setting on every instance (including
pending restarts), and the exact
two streaming quorum standbys of each shard. It also creates random password Secrets only if absent: `catalog-migrator`,
`ordering-migrator`, `product-app`, `order-app`, `ordering-cdc` and
`ordering-operator` are shared by
the shards; `ledger-migrator` and `ledger-app` belong to the separate ledger.
It applies CNPG `DatabaseRole`s and `Database` resources for catalog, ordering,
and ledger, waits for roles at their current generation, then waits up to 180
seconds each for a Database's current generation and `applied` status. `up.sh`'s Flyway
Jobs then create tables and other schema contents ([migrations](../database/RUNBOOK.md#database-schemas-and-migrations)).
Resource budgets, PostgreSQL settings and reviewed operator/same-major PostgreSQL
updates can converge in place. Changes to storage, placement, bootstrap settings,
replica count or PostgreSQL major version fail before apply; the local storage
class does not support expansion. New schemas and roles use current-generation
operator checks. `up.sh` observes these infrastructure readiness checks before migration, application
deployment and the final `kind-release | kind-deployment` acceptance; standalone `kind-deployment` remains available for compatibility and
focused reruns.
`cluster-create.sh` owns node creation, infrastructure reconciliation, credentials
and fresh allocator initialization; `up.sh` owns current application builds,
Maven/Scout, migration and application image delivery. Both may resume retained
nodes and share snapshot, lock, rendering, verifier and readiness functions in
`scripts/lib/lifecycle.sh`. This sourced helper is not another entry point.
`down.sh` and `cluster-destroy.sh` use it only for the lock and the signal traps. Skaffold owns image
build configuration. Helmfile owns release dependencies and phase hooks; Helm
owns declarative resources and migration/seeder hook Jobs. Kubernetes and
CloudNativePG own reconciliation. Namespace, DNS probes and immutable allocator
identity are rendered by Helm but applied as bootstrap resources outside release
ownership. The opt-in Maven `kind` profile owns the configuration, retained-state,
admission, DNS and database assertions, including resource quantity comparison.
There is no quantity parser or embedded Python in `up.sh`. Flyway continues to own
schema migrations and the Java applications own allocation, seeding and reader gating.

Both scripts run safety scenarios at their phase boundaries using the same private
snapshot throughout. Creation uses `shardshop.infrastructureOnly=true` to disable
application reactor-order dependencies for the observer build and direct Failsafe
gates, so it neither builds applications nor requires previously installed
application POMs. Default builds keep those dependencies. Startup's initial
`clean install -DinstallAtEnd=true` includes all verification and installs
the successful reactor artifacts into the local Maven cache. It then invokes Failsafe
directly against those current packages without repeating the build.

## Deploy with up.sh

`up.sh` takes no arguments. It requires the prepared cluster and reports the
creation command if it is missing. It resumes stopped nodes, builds current sources, validates/scans images,
checks the prepared infrastructure, migrates and deploys the applications. It never
creates namespaces or credentials or syncs infrastructure releases.
Every deployment invocation replaces product, order, ledger, reader and order producer pods,
even when code and configuration are unchanged. Helm applies a fresh shared restart
annotation to all five Deployment templates; its seeder hook and final acceptance gate verify the
new workload.
Each restarted product or order JVM consumes a fresh generator ID; the retained
lab has 1,023 non-reusable slots, so an ordinary rollout uses at least three.
Changed code is rebuilt and deployed through this same command.

`up.sh` resolves the ShardShop POM from its own location and runs the full
`mvn -B -ntp -f microservices/shardshop/pom.xml clean install -DinstallAtEnd=true` build,
which includes verification and caches the verified artifacts for later kind checks. The lifecycle commands
use repository-relative paths; invoking `up.sh` by its absolute path works
from any directory. The Maven build is the source for the seven freshly built
images; no separate Maven or image-build command is required. Audit builds remain
optional. The POM sets `project.build.outputTimestamp`, thus the same sources give
the same packages. Docker then uses its cached image layers again, and an unchanged
rebuild keeps its image IDs. Thus `kind load` skips the nodes, and the digests stay
the same.

`up.sh` uses Skaffold for image builds and Helmfile for real Helm releases.
`cluster-create.sh` syncs `cnpg` and `shardshop-infrastructure`. After allocator
validation and image delivery, `up.sh` syncs only `shardshop-migrations` and
`shardshop-application`.
Helm waits for the application resources and its seeder hook. The application
release then runs the native Helmfile `postsync` acceptance hook with
`kind-release | kind-deployment`. `kind-release` checks the snapshot images,
rendered desired configuration, current deployed Helm revision and Deployment
generations, pod ownership and runtime digests, the current successful seeder, and
ready product EndpointSlices. When kind reports an archive-index runtime digest,
the observer reads its single platform descriptor through containerd and requires
the qualified manifest; `kind-deployment` retains the traffic through both
product pods, the accepted reader load and the deployed saga outcomes. A failed acceptance hook fails Helmfile
and `up.sh`; Helm may still show the release as deployed. Acceptance failure does
not roll back the release: correct the cause and rerun startup. The observer never
repairs resources. Diagnostics are bounded and contain no secrets.
The reader and order producer remain running and disruptive drills remain opt-in. A new
`kind-release` run requires `images.json` in the snapshot, including the deployed
`restartToken` alongside `images`; standalone legacy
`kind-deployment` runs do not. The four maintained lifecycle entry points are
`cluster-create.sh`, `up.sh`, `down.sh` and `cluster-destroy.sh`. Create the foundation
with `cluster-create.sh`, then use `up.sh` for application startup and deployment.
Every `up.sh` restarts application pods, including unchanged builds.
`cluster-create.sh` applies and verifies the retention admission policy, then creates
the generator registry and immutable identity on its new cluster. `up.sh` only checks
existing allocator state: missing, partial, malformed, or mismatched
allocator state stops startup and requires independent allocator recovery. `up.sh` never
resets retained generator state.

Each `up.sh` invocation rebuilds and qualifies the enabled images before cluster mutation.
On each run, kind refreshes and selects the `kind-shardshop` context in the caller's
`KUBECONFIG` files, or `~/.kube/config` when unset. The recovered endpoint remains
available to subsequent `kubectl` commands and standalone Maven checks. The script
passes that context on every command and never deletes a cluster, namespace, or
volume. Check the result:

### Application images

`infra/images/application/Dockerfile` is the one recipe for the six application
images. Its context is the complete `target/quarkus-app` directory of one
application. All images use the pinned Canonical OpenJDK 25 JRE, the working
directory `/app`, user `10001:10001` and `HOME=/tmp`, and their entrypoint starts
`quarkus-run.jar`. The images contain no kubectl: product and order
reserve their generator IDs with Java code in their own JVM.

`up.sh` builds and qualifies `flyway`, `product`, `product-seeder`, `product-reader`,
`order`, `ledger` and `order-producer`. It:

1. Uses [`skaffold.yaml`](../skaffold.yaml) to build the seven ARM64 artifacts from
   current packaged Maven outputs and the reviewed Dockerfiles. Skaffold owns the
   artifact list, Docker recipes and SHA-256 tag policy. Its local build uses Docker
   BuildKit, disables provenance/SBOM output for reproducible manifests, and writes
   a private build-result JSON file. An empty invocation-local artifact cache forces
   fresh builds while keeping Docker layer reuse and avoiding Skaffold's asynchronous
   pruning of rebuilt images. Flyway uses the
   [migration image](../database/RUNBOOK.md#database-schemas-and-migrations) recipe in
   `infra/images/flyway`; applications use `infra/images/application/Dockerfile`.
2. Prints the OS package inventory (`docker scout sbom`) and the advisories of all
   package types and severities (`docker scout cves`). Docker Scout does not index
   six Ubuntu packages of the JRE base, glibc among them. Thus the inventory and the
   gate do not include them. [VERSIONS.md](../VERSIONS.md#os-packages-without-an-advisory-check)
   records this gap.
3. Stops if Docker Scout finds a critical or high advisory without a reviewed
   statement in `infra/images/application/vex/`. Statements identify the named
   image recipes and exact package versions independently of Skaffold's content tags.
   A failed gate prevents loading the image.
   All seven enabled images are built and scanned before any image is loaded into
   kind.
4. After provisioning, loads each qualified image into the kind nodes, one node at a
   time, and requires the same
   loaded platform manifest on each node. Then it registers that digest under the image name.
   `kind load` skips a node that already has the same image ID. Then the check uses
   the loaded platform manifest digest of the earlier import on that node.
   For `flyway`, the loaded platform manifest must also be the pinned digest; otherwise the script
   stops before it registers the digest, and the image pin needs a review.
   It also tags the image `sha256-<digest>` on each node. kind names each import
   `import-<date>@<digest>`, without a registry. Without the extra tag, the CRI uses
   that name after the original tag changes or is removed. It cannot find the earlier image,
   thus new containers of the deployed release fail with `CreateContainerError`.

Skaffold is used in build-only mode: it has no deployer in this workflow and does
not load images into kind itself. The small native `kind load` adapter in `up.sh`
performs delivery and checks each loaded config ID against the qualified build;
Helmfile owns deployment.

`up.sh` captures the loaded platform manifest digests of this invocation's qualified builds on
every node before running migrations or applying a new application release. A
failure while loading or registering nodes can leave a partial image load, but
prevents the new release. Docker Scout
needs a network connection. [VERSIONS.md](../VERSIONS.md#step-39-application-images)
records the scan results and the reviewed statements.

### Application release

The application Helm release deploys two product pods, two order HTTP pods and one
pod each for order saga, ledger, the reader, the order producer and the interactive
seeder, plus its bootstrap seeder Job.
The HTTP Deployment uses `RollingUpdate`; `shardshop-order-saga` uses one replica
and `Recreate` so its three embedded CDC readers never overlap during a rollout.
Both use the same image, selected by `shardshop.order.process=http|saga` (default
`http`). Only saga runs stock/profit workers, CDC and the result consumer. Each
JVM reserves its own generator slot.
Its offsets and reader history remain in the shard databases; no pod-local volume
holds CDC progress. Ledger consumes commands and publishes saved results against
its separate database. Its internal HTTP Service exposes `/q/metrics` on port
8080; command and result traffic still uses RabbitMQ. The saga has an independent
internal metrics Service, while the order API Service selects only HTTP pods.

Order mounts the routing snapshot and the shard CA certificates. It gets the
`order-app` and `ordering-cdc` passwords and its RabbitMQ credential from
`secretKeyRef` environment variables, not from mounted files. It uses the existing
`shardshop-order` service account to reserve a generator ID. Ledger mounts only its
database CA certificate and gets its database and RabbitMQ passwords from
`secretKeyRef` environment variables. Both load generated configuration from
`/app/config/application.properties`. CA projections expose `ca.crt`, never
`ca.key`. The producer receives only HTTP settings and has no service-account
token, database credential or persistent volume.

## RabbitMQ broker

`cluster-create.sh` provisions one persistent RabbitMQ 4.3.6 broker through the
infrastructure Helm release. `shardshop-rabbitmq.shardshop.svc:5672` serves AMQP;
port 15672 serves management inside the cluster. The `shardshop` vhost contains
the durable direct exchanges `ledger.commands`, `order.saga-results` and
`shardshop.dlx`, with these single-member quorum queues:

| Queue | Complete policy | Dead-letter destination |
|---|---|---|
| `ledger.record-order` | `ledger-command-delivery` | `ledger.record-order.dlq` |
| `order.ledger-results` | `order-result-delivery` | `order.ledger-results.dlq` |
| Both `.dlq` queues | `parking-capacity` | None |

Source policies use failed-delivery retry, delays from 1000 to 5000 ms,
`delivery-limit=5`, and at-least-once dead-lettering. All queues use reject-publish
limits of 1000 messages and 16 MiB; quorum admission can briefly overshoot these
thresholds. When a full parking queue rejects a dead letter, the source retains
it until the broker's next internal confirmation retry (180 seconds by default).
Parking queues disable the delivery limit
with `x-delivery-limit=-1`, with no TTL, expiry or automatic consumer. The broker
accepts messages up to 1 MiB, has a 512 MiB memory watermark, 100 MB free-disk
threshold and a 1 GiB container limit. `stream_queue` is enabled by the broker's
stable feature defaults and checked by acceptance tests.

The StatefulSet retains its 2 GiB PVC across restarts and deletion. A stable
node name keeps the same broker database. `rabbitmq.runAsUser` can be any positive
UID; `fsGroup` makes storage writable, and cookie/plugin/runtime files live in
`/tmp`. The root filesystem is read-only and all Linux capabilities are dropped.
Startup copies only the two named definition files out of Kubernetes projected
volumes, avoiding their metadata directories. A ConfigMap checksum rolls the
broker when public configuration changes. One broker provides persistence but
no availability during broker or node downtime.

Creation generates the `shardshop-rabbitmq` Secret once. Its `users.json`,
`admin-password`, `order-password`, `ledger-password` and `erlang-cookie` keys are
preserved on reruns. `shardshop-admin` owns topology; `shardshop-order` can publish
commands and read results; `shardshop-ledger` can read commands and publish
results. Service users cannot configure resources or use management. Back up
this Secret with broker data. If the Secret disappears while the StatefulSet or
PVC remains, creation stops: restore the original Secret before retrying. A
partial Secret also fails without printing credentials. Credential rotation
requires a coordinated broker restart and consumer update; it is not performed
by ordinary startup.

Run provisioning and the read-only live readiness/topology gate:

```bash
bash microservices/shardshop/scripts/cluster-create.sh
```

`up.sh` also runs that gate before migrations. To verify the chart, lifecycle
preservation, non-image UID, permissions, native retry boundary, persistent
message recovery and full-queue backpressure in isolated containers:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am \
  -Dshardshop.infrastructureOnly=true -Pintegration \
  -Dcucumber.filter.tags=@rabbitmq verify
```

The scenarios publish persistent mandatory messages and require publisher
confirms. They verify that `basic.nack(requeue=true)` preserves the failure
budget, while `basic.reject(requeue=true)` spends it, and malformed-message
rejection parks immediately. Test containers and their temporary volumes are
removed on completion. The test-only Java client uses its socket transport.

The [retry and DLQ capacity drills](../shardshop-testing/RUNBOOK.md#retry-and-dlq-capacity-drills)
exercise shard-outage recovery and real application outbox backpressure under an
unbound or full DLQ, using the shared guard to restore temporary settings.

## Stop and resume the cluster

```bash
bash microservices/shardshop/scripts/down.sh
bash microservices/shardshop/scripts/up.sh
```

`down.sh` first turns off the restart policy of every ShardShop kind node with one
`docker update --restart=no` call. kind creates its nodes with `on-failure:1`, and a
node that Docker kills at the stop timeout exits with a failure code. Without this
step, the next Docker daemon start restarts the stopped nodes, for example when Docker
Desktop's Resource Saver starts its VM again for a later Docker command.
Then `down.sh` stops every node, including the control plane and all workers, with
one `docker stop --timeout 10` call. Docker stops the nodes in parallel.
It sends SIGKILL to a node process that did not stop after ten seconds. It returns
an error if a node does not stop. Docker does not change a node that is already
stopped, and a missing cluster needs no Docker call. A failed stop returns nonzero
and releases the lifecycle lock. Inspect the nodes and run `down.sh` again. If
Docker Desktop does not respond, `down.sh` waits until you stop it with Ctrl+C. `up.sh` sets kind's `on-failure:1` policy again, resumes the same containers, checks
the prepared infrastructure and redeploys applications, preserving databases, PVCs,
Secrets and allocator identity/history. `cluster-create.sh` also sets the policy again
when it resumes an existing cluster.
Each restarted product JVM still reserves a new generator ID.

It stops the four node containers without deleting them; volumes, databases and
cluster state stay inside. PostgreSQL shuts down cleanly within seconds, and Docker
stops the rest after its default 10-second timeout. Rerun `up.sh` to start the same
containers; it waits for the API server and for every `-rw` Service, because the
stored Ready statuses predate the restart. Before it starts the nodes, it builds,
tests and scans the images again, thus it needs network access for Docker Scout.
Only `kind delete cluster --name shardshop` deletes the lab's data.

## Permanently destroy the cluster

```bash
bash microservices/shardshop/scripts/cluster-destroy.sh
```

This command immediately and irreversibly deletes the fixed `shardshop` kind
cluster, running or stopped, with all its databases, PVC data, Secrets, Helm
releases, Kubernetes configuration and generator history. Use `down.sh` when you
want to retain data. The script takes the lifecycle lock and runs
`kind delete cluster --name shardshop` with kind's Docker provider. kind removes the
node containers with `docker rm -f -v`, which also removes their anonymous `/var`
volumes. Then kind removes the `kind-shardshop` context, cluster and user entries
from the selected `KUBECONFIG` (or the default kubeconfig). kind returns an error if
one of these steps fails. A missing cluster is safe to repeat. Docker keeps a volume
that another container uses. If Docker cannot remove a node volume, `docker volume ls`
still shows it after the deletion. Remove it with `docker volume rm`.
Exported kubeconfig copies outside that selection and external backups must be
managed separately.

The supported lab stores its data in Docker node volumes. `infra/kind.yaml` adds no
host mounts. Destruction preserves repository
source/configuration, Docker Desktop, unrelated containers, shared images/build
caches and the shared kind network. Run `cluster-create.sh` and then `up.sh` to create a fresh lab;
previous entity IDs and allocator history cannot be recovered by restarting it.

## Recover retained data or failed startup

A failed `up.sh` can leave completed migrations and a partially updated release.
Inspect its bounded diagnostics and the retained migration/seeder Job logs before
retrying. Correct the source configuration or forward migration that caused the
failure, then run `bash microservices/shardshop/scripts/up.sh` again. Flyway validates
actual schema history on every run; the seeder retries the same durable keys and
payloads, preserving existing entity IDs. Do not edit an applied versioned migration,
clear Flyway history, or delete topology guards to force a retry.

For a stopped lab, run `up.sh` to resume the retained nodes. If prepared
infrastructure needs reconciliation, run `cluster-create.sh` first; it validates
retained topology and allocator state and never resets their history. Neither
command reconstructs deleted rows or lost storage. Do not destroy the cluster as a
recovery step when those data must survive.

Missing, partial or mismatched allocator state requires an independent record of
the original registry identity and latest high-water mark. Keep ID-producing
services stopped until those facts are established. An older database dump is not
allocator recovery evidence. The admission guard pins the original registry UID,
so creating a replacement ConfigMap or applying an old snapshot cannot safely
repair it. There is no automated allocator reconstruction or supported generic
restore command; follow the [allocator recovery constraints](../shardshop-core/idgen/RUNBOOK.md#generator-allocation-at-jvm-startup)
and preserve the latest mark without rollback.

Coordinated database backups, isolated restores and restore audits remain PLAN
steps 6.3–6.4; these lifecycle commands create no backups. If storage is lost,
retain any surviving volumes and independent recovery artifacts before attempting
restoration. The [backup policy](../PLAN.md#7-replication-and-backup-policy) requires
external dumps with routing/schema identities and separate latest allocator
history, and the [restore audit](../shardshop-order/ARCHITECTURE.md#orders-lost-by-asynchronous-failover-or-restore)
defines reconciliation for future order/ledger state. Without usable recovery
artifacts, recovery of deleted history is unavailable. A deliberate fresh lab
requires retiring all prior emitters, data and replay inputs, then running
`cluster-destroy.sh`, `cluster-create.sh` and `up.sh` in that order; it starts a new
identity history rather than recovering the old one.

## Lifecycle lock

`cluster-create.sh`, `up.sh`, `down.sh` and `cluster-destroy.sh` share the local exclusive directory
lock `${TMPDIR:-/tmp}/shardshop-up.lock`. Use the same `TMPDIR` for all four. A
concurrent call fails before changing the lab; retry it after the first finishes.
After successful deployment, `up.sh` releases this lock and its temporary snapshot
and exits. The separate, argument-free `webpages.sh` replaces local page listeners
and supervises its tunnels on the deployed lab without taking the deployment
lock, rebuilding images or restarting applications. See
[Open the webpages](../README.md#3-open-the-webpages) for access and port-conflict recovery.
Ordinary failure and INT/TERM release the lock after the active command ends.
After SIGKILL or a machine failure, verify that no invocation remains active and
inspect retained Jobs before removing a stale lock directory.

## Node networking

A node start can give the nodes new IP addresses. `up.sh` restarts kindnet after
starting any stopped node, then checks DNS from all four nodes and verifies both streaming
standbys of every shard. If automatic repair or the database-parameter wait fails,
`up.sh` stops with recovery guidance; retained data and allocator state remain
untouched. CoreDNS runs on the control plane, so a missing route prevents Service
resolution and can stop streaming, with an error such as
`<cluster>-rw does not accept connections from <pod>`. Each node must have a route to
the pod network (`CIDR`) of each other node:

```bash
kubectl --context kind-shardshop get nodes -o 'custom-columns=NAME:.metadata.name,IP:.status.addresses[0].address,CIDR:.spec.podCIDR'
docker exec shardshop-control-plane ip route
```

If automatic kindnet repair does not restore the route, inspect the reported node and
resolve the condition before rerunning `up.sh`. To refresh a stale kindnet process
on a running node, replace only that node's kindnet pod:

```bash
kubectl --context kind-shardshop -n kube-system delete pod -l app=kindnet --field-selector spec.nodeName=shardshop-control-plane
```

Do not delete allocator state or
database volumes as a workaround.

## CloudNativePG operator

[`infra/helm/cnpg/`](helm/cnpg) holds the official CloudNativePG chart
0.29.1, verified against the lock's SHA-256 and its cosign signature, and
`values.yaml`, which pins the operator by tag and digest. The chart uses that one
reference for both the operator image and `OPERATOR_IMAGE_NAME`, and it keeps its
CRDs if the release is ever uninstalled, so the database clusters survive. The
operator installs before the shared database resources so its APIs and webhooks are
ready for database resources. To upgrade, review the lock's support deadline, then
vendor and verify the new chart and update `values.yaml` and the lock together;
a later cloud target installs the same chart and values.

## One-time procedures

### One-time adoption of an existing apply-managed lab

Fresh labs need no adoption. A retained lab created by the earlier `kubectl apply`
workflow must explicitly transfer the matching infrastructure and application
resources to `shardshop-infrastructure` and `shardshop-application` before the first
Helmfile sync. Existing immutable SQL ConfigMaps whose names and contents match the
current migration snapshot also transfer to `shardshop-migrations`; historical
ConfigMaps outside that exact set are not adopted. Normal `up.sh` does not use `--take-ownership` or steal resources
from another Helm release.

First run the retained topology, allocator and infrastructure safety checks against
the same chart/inventory snapshot. Inspect current Helm ownership and compare the
exact resource names/kinds in the rendered infrastructure/application manifests
and current immutable SQL ConfigMaps. Stop if any resource already belongs to a
different Helm release or another declared manager. Check the full resource set
before changing any metadata. For only that reviewed
resource set, add `app.kubernetes.io/managed-by=Helm`,
`meta.helm.sh/release-name=<matching-release>` and
`meta.helm.sh/release-namespace=shardshop`. Use metadata-only JSON patches that
test the observed UID and resourceVersion and preserve other labels/annotations;
never change specifications or data during adoption. This preserves resource UIDs
and PVC bindings. The application set includes `shardshop-routing`.
Do not adopt the namespace, credential Secrets, generator registry/identity,
operator-created Pods/PVCs, or legacy migration/seeder Jobs. The existing `cnpg`
release retains its own ownership. Keep an inventory of UIDs, PVC bindings and
allocator high-water before and after the transfer, then run `up.sh`.

ShardShop releases use Helm's three-way merge (`serverSide: false`) for the
previously apply-managed resources. The existing `cnpg` release retains
server-side updates.

### First upgrade from a v1-only ledger

For this one-time retained-lab cutover, the ledger must become v2-capable before
new order pods publish v2 commands. Pause the **order Deployment rollout** before
running `up.sh`; existing order pods continue serving and emitting v1:

```bash
kubectl --context kind-shardshop -n shardshop rollout pause deployment/shardshop-order
bash microservices/shardshop/scripts/up.sh
```

While `up.sh` waits for application readiness, use another terminal to verify that
the ledger Deployment and its ready pod use the **new ledger image digest printed
by this invocation**, with its updated generation observed and no old ledger pod.
Then complete the order rollout:

```bash
kubectl --context kind-shardshop -n shardshop rollout status deployment/shardshop-ledger --timeout=120s
kubectl --context kind-shardshop -n shardshop rollout resume deployment/shardshop-order
```

A successful rollout status for the old digest is insufficient. If the build or
deployment fails, resume the order rollout before leaving the cutover. Preserve an
operator's preexisting paused state instead of taking ownership of it. After this
upgrade both services understand v2; subsequent deployments use ordinary `up.sh`.
No stored message or ledger decision is replaced during the transition.
