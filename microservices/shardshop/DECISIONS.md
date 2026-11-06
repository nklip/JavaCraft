# ShardShop decisions

[ShardShop](README.md) › **Decisions** · [Implementation plan](PLAN.md) · [Changelog](CHANGELOG.md) · [Architecture](ARCHITECTURE.md)

This file records the main decisions of ShardShop and the reason for each one.
Each record gives the decision, the reason and the result for the system. The
linked [CHANGELOG](CHANGELOG.md) entry has the evidence. The Git tag
`shardshop-docs-full-2026-10-31` has the full history.

## Contents
1. [Scope and platform](#1-scope-and-platform)
2. [Data and consistency](#2-data-and-consistency)
3. [Build, test and delivery](#3-build-test-and-delivery)
4. [Operations and security](#4-operations-and-security)

## 1. Scope and platform
<sub>[Back to Contents](#contents)</sub>

### Local deployment first

**Decision.** Milestones 0–6 run and are verified only on one machine: on a kind
cluster with four nodes or in local containers. Milestone 7 adds the AWS
deployment.

**Reason.** The AWS deployment must add an environment, not change the local
deployment.

**Result.** A local step never depends on a cloud service, and step 6.9 must
still pass locally after the AWS work ([plan](PLAN.md#4-milestone-7-aws-deployment)).

### Community releases with regular upgrades

**Decision.** Use long-supported releases where a project offers them, and
maintained stable GA releases elsewhere. Do not use prereleases, floating tags,
external snapshots or paid extended support. There are two exceptions: the
pre-1.0 Snowflake library, which the user selected explicitly, and three
plugin-only XML prereleases of the Quarkus plugin.

**Reason.** Commercial extended support is outside the scope of the lab.

**Result.** Each component has a review date, and the user approves each update
([update rules](VERSIONS.md#2-update-rules)).

### AWS foundation with community modules

**Decision.** Terraform creates the AWS foundation with the community modules
`terraform-aws-modules/vpc` and `terraform-aws-modules/eks`. The workloads get AWS
roles through EKS Pod Identity. There is one shared NAT gateway and no customer
managed KMS key. The user selected the modules and the single NAT gateway on
2026-10-31.

**Reason.** The modules need much less custom code than plain AWS resources. Pod
Identity needs no OIDC provider and no service account annotation. A customer
managed key stays for at least 7 days after its deletion, thus `terraform destroy`
could not remove everything. One NAT gateway costs a third of three gateways, and
S3 traffic uses a free gateway endpoint.

**Result.** If the zone of the NAT gateway fails, the workers of all zones lose
their internet egress. `nat_gateway_per_zone = true` changes this
([AWS foundation](infra/terraform/README.md#design)).

### EKS database tier from the shared chart

**Decision.** EKS uses the same CloudNativePG chart, shared chart and inventory as
kind. `eks-values.yaml` changes only the storage and the zone placement, and makes
the shared chart create an encrypted gp3 StorageClass. Plain Helm and kubectl
commands in the [runbook](infra/terraform/README.md#deliver-shardshop-with-argo-cd)
installed the database tier, without a script or Helmfile, until Argo CD replaced
them. The user selected the runbook commands and an API endpoint open to all
addresses on 2026-11-01.

**Reason.** The EBS CSI add-on can create only a default class without an explicit
volume type or encryption. A class in the chart is explicit, testable and leaves
with the infrastructure release. Secrets Manager and Argo CD later replaced the
credential and delivery commands of the runbook. The exit address of the
operator's VPN changes for each connection, thus one `/32` filter blocks kubectl.

**Result.** IAM authentication and the access entries are the only protection of
an open endpoint. Destroy the foundation after each run.

### Argo CD delivery from OCI charts in ECR

**Decision.** A self-managed Argo CD 3.5 applies the database layer on EKS. The
source is OCI charts in ECR with digest pins, which the runbook packages from the
working tree. A root Application applies one child Application for each layer in
sync waves. The user selected the split of the GitOps step, the self-managed Argo
CD, the OCI source and gate Jobs in the cluster on 2026-11-01.

**Reason.** An AWS run then needs no Git push. Argo CD renders with
`helm template`, thus the Helm release revision cannot name a migration attempt.
`generateName` gives each attempt a new Job, and the delete policy `HookSucceeded`
keeps a failed or interrupted attempt. A wave starts only after the earlier waves
are healthy, thus a failed migration stops the routing publication. The managed
Argo CD of EKS needs IAM Identity Center.

**Result.** No child Application prunes resources or has a resources finalizer,
thus Argo CD never deletes a database. kind keeps Helmfile and the Helm names. The
migrations use the immutable chart version instead of the digest. Argo CD 3.5 puts
the start of the revision into the generated hook names, and `sha256:` is not valid
there. The infrastructure ignores `/spec/login` of `DatabaseRole`,
because the CNPG webhook removes `login: false`.

### Gates as Argo CD hook Jobs

**Decision.** On EKS, the Maven gates of the Helmfile workflow run as Argo CD PreSync
and PostSync hook Jobs. A gates image runs `ClusterIT` with the JUnit console launcher,
kubectl and the invocation snapshot of the run. A ValidatingAdmissionPolicy denies a
selective sync and the `apply` sync strategy of the ShardShop Applications. The user
selected the gate Jobs in the cluster on 2026-11-01, and the console launcher and
kubectl 1.37.2 on 2026-11-02.

**Reason.** A hook gate runs in each sync, also in an automated sync. A failed
PreSync gate stops the sync before Argo CD applies a resource. Argo CD runs no hook
in a selective sync or with the `apply` strategy, and its RBAC cannot tell these
syncs apart. The admission policy also stops them when they come through kubectl.
The console launcher uses the same tag expressions as the Failsafe groups, and the
image needs no Maven repository and no shell.

**Result.** The gates use the tags of the Helmfile hooks. `eks-release` replaces
`kind-release`, because `kind-release` reads the images on the kind nodes. The
seeder and the gates are generated hook Jobs, thus each sync has its own attempt.
The PostSync gate finds the seeder attempt in the sync result. The gates can exec
into the database and broker pods and read the broker management credential, as the
operator can on kind. They read no other Secret. The runbook builds
the gates image for each run, because the image contains the snapshot of the run.
Only the routing Application publishes the routing.

### Drills as Jobs in the cluster

**Decision.** On EKS, the drills and the lifecycle checks run as Jobs in the cluster,
from the gates image with the ServiceAccount `shardshop-drills`. The runbook creates
this identity before the drills and deletes it after them, and Argo CD does not
deliver it. The backup, restore, recovery and allocator checks stay on the computer
that runs the runbook. The user selected this on 2026-11-02, after the first
acceptance run on EKS.

**Reason.** A drill sends many kubectl commands. Through a VPN and the internet, TLS
handshakes took up to 13 seconds, and the failover drill failed two times on failed
commands, while the cluster and the data were correct. In the VPC, the API server is
near the drill. The backup checks need the local Docker and private files.

**Result.** The drill identity can change the database clusters, the pods, the
Deployments, the Jobs and named ConfigMaps. It reads only the broker credential and
the synced runtime Secrets. The gates image contains Helm 4.3.1, because the workload
drill renders its temporary seeder Job. The drills write their temporary files to a
work directory, because the snapshot of the image is read-only.

### EKS durability with managed add-ons

**Decision.** On EKS, the Barman Cloud Plugin archives the WAL and writes the base
backups of each database cluster to S3, with cert-manager as an EKS community
add-on. The AWS Secrets Store CSI add-on syncs the runtime secrets from AWS Secrets
Manager into the Kubernetes Secrets of the shared chart, through a holder pod.
RabbitMQ stays the StatefulSet of the shared chart with three brokers. The user
selected these three choices on 2026-11-01.

**Reason.** CloudNativePG 1.31.0 removes the in-tree `barmanObjectStore`, and the
CNPG upgrade is due. External Secrets Operator supports only its newest release,
which does not list Kubernetes 1.36. AWS selects the add-on versions for the EKS
version. The shared StatefulSet keeps one broker definition for kind and EKS.

**Result.** A synced Secret exists only while the holder pod mounts it. The
Terraform `users_json` repeats the users and permissions of `users.jq`. kind keeps
one broker, its logical dumps and the Secrets of `cluster-create.sh`.

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
([open findings](VERSIONS.md#3-open-findings-and-risk-acceptance)).

## 2. Data and consistency
<sub>[Back to Contents](#contents)</sub>

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
([failover drills](CHANGELOG.md#step-62--synchronous-failover-drills)).

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
([step 6.6](CHANGELOG.md#step-66--sustained-30-new-orderss)). Delivery is at least once, thus each handler is
idempotent, and reconciliation recovers lost results. Each CDC reader sends a
heartbeat every 10 seconds: without it, an idle outbox never confirmed WAL, and
the slot could pass `max_slot_wal_keep_size` (512 MB) ([step 4.4](CHANGELOG.md#step-44--order-outbox-cdc-relay)).

### Shard-concurrent stock and profit workers

**Decision.** The stock and profit workers process the buyer-home shards
concurrently, with one lane for each shard. The default is 3. The value 1 keeps
the serial scan.

**Reason.** In isolated A/B runs, the stock stage increased from 40.60 to 57.99
orders/s (+42.8%), and the profit stage from 182.29 to 299.16 intents/s (+64.1%).

**Result.** Each shard batch and the operations of each order stay sequential
([step 6.5](CHANGELOG.md#step-65--order-write-p95-below-100-ms)). The
[saga stages for cross-zone latency](#saga-stages-for-cross-zone-latency) added
concurrency inside the result and profit stages.

### Saga stages for cross-zone latency

**Decision.** On 2026-11-06 the user selected three changes, one for each measured
cap of the AWS deployment:

- The saga applies ledger results with 3 handlers, each on its own channel with
  prefetch 1. Its shard pools get one more connection for each handler.
- A stock lane starts its next batch at once while its last batch was full.
- A profit lane posts its batch in 2 groups at the same time. The credits of one
  seller stay in one group.

**Reason.** On EKS each SQL round trip and each synchronous commit crosses zones.
The sequential stages then had these limits at 30 orders/s
([evidence](shardshop-testing/evidence/2026-11-06-eks-order-capacity.json)):

| Stage | Measured time | Limit |
|---|---|---|
| One result handler | 35.9 ms for each result | 27.8 results/s |
| Stock lane: 8 sagas, then 250 ms | 72 ms for each saga of a remote shard | 9.7 orders/s for each buyer shard |
| Profit lane, one credit at a time | 35 ms for each credit of a remote shard | 26 credits/s for each home shard |

**Result.** The acceptance run on EKS passed at 29.87 orders/s with no backlog
growth. Results of one order still wait for its order lock, and a profit credit
still changes a balance only once. The extra pool connections did not change the
stock time, because the stock lanes were the limit. They stay, because with 4
connections each saga pool reached its maximum
([changelog](CHANGELOG.md#step-79--sustained-30-new-orderss-on-eks)).

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
([step 3.11](CHANGELOG.md#step-311--generator-ids-in-the-application-jvm)).

### Strict replica reads without primary fallback

**Decision.** A read with the replica profile uses only the standbys (`-ro`).
When no standby answers, it returns 503 within the four-second server budget. It
never falls back to a primary.

**Reason.** Acceptance scenarios 3 and 8 require explicit stale reads and no
primary fallback ([scenarios](shardshop-testing/ARCHITECTURE.md#1-acceptance-scenarios)).

**Result.** Product reads use the primaries (`-rw`) by default. The replica
profile is an optional load-test profile that permits stale or temporarily
missing products ([step 6.1](CHANGELOG.md#step-61--strict-replica-read-drill)).

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
[step 6.4](CHANGELOG.md#step-64--restore-audit-and-recovery).

## 3. Build, test and delivery
<sub>[Back to Contents](#contents)</sub>

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
only the removed logic ([step 4.3](CHANGELOG.md#step-43--rabbitmq-topology-and-policies)).

**Result.** `cluster-create.sh` owns the infrastructure, and `up.sh` owns the
applications, as the user requested. Each `up.sh` restarts the application pods.
The final acceptance gate is a Helmfile `postsync` hook, because Helm reports
`deployed` also when the acceptance fails ([step 3.17](CHANGELOG.md#step-317--complete-upsh-with-a-deployment-success-gate)).

### Reproducible images with digest tags

**Decision.** The build sets `project.build.outputTimestamp`, and `up.sh` tags
each image that it loads into kind as `sha256-<digest>` on each node.

**Reason.** Two clean builds of the same sources gave different JAR files, thus
each `up.sh` loaded new images. After `up.sh` moved the `:local` tags, the CRI did
not find the earlier images and failed with `CreateContainerError`
([step 3.13](CHANGELOG.md#step-313--build-current-sources-and-qualify-images-through-upsh)).

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
high advisories ([step 3.10](CHANGELOG.md#step-310--maven-integration-tests-with-testcontainers)).

**Result.** There is no Bash or Python check of Java behavior.

### Release gates independent of workloads

**Decision.** The `up.sh` gates create their own buyer, seller and products. They
never depend on the orders of the workload producer.

**Reason.** The user decided it. Confirmed producer orders use the seeded stock
permanently: approximately 16 h of producer run time empties the 50,000 USD units
of a region ([step 4.9](CHANGELOG.md#step-49--saga-deployment-and-first-end-to-end-run)).

**Result.** A gate passes or fails on its own data, also after a long producer run.

## 4. Operations and security
<sub>[Back to Contents](#contents)</sub>

### Image scan gate and explicit risk acceptance

**Decision.** `up.sh` scans every built image with Docker Scout before it changes
the cluster. A critical or high finding stops the run, unless a reviewed VEX
statement covers it. The user accepts each remaining risk explicitly, with an end
date.

**Reason.** No image reaches the cluster before its scan.

**Result.** The open findings and the current risk acceptance are in
[VERSIONS.md](VERSIONS.md#3-open-findings-and-risk-acceptance).

### Manual reconciliation reset as SQL

**Decision.** The manual reset is the database-owned
[`reset-reconciliation.sql`](database/operations/reset-reconciliation.sql), run
with `psql`. It takes one explicit order and saga ID pair. There is no wildcard
reset, shell entry point, HTTP endpoint or additional process.

**Reason.** The operator role has only the reads that the operation needs and
updates of the two retry columns. It cannot read payloads or change terminal
decisions.

**Result.** The checked-in operation keeps the pending-state protection; the
column grants alone cannot prevent ad hoc changes ([step 5.3](CHANGELOG.md#step-53--manual-reconciliation-reset)).

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
([step 6.8](CHANGELOG.md#step-68--http2-transport)).

### CloudFront default hostname

**Decision.** On 2026-11-03 the user selected the default `*.cloudfront.net`
hostname of a CloudFront distribution for the AWS webpages. ShardShop buys no
domain and uses no Route 53 zone or ACM certificate.

**Reason.**

- A domain costs approximately US$14–16 for a minimum of one year (Route 53
  prices of July 2026), without a refund. An AWS run uses it for less than one day.
- A free eu.org subdomain needs a manual approval that can take months. is-a.dev
  gives NS delegation only in rare cases.
- The load balancer name and IP-based names, for example nip.io, cannot get a
  valid public certificate.
- CloudFront supplies a valid certificate and has no fee for each hour.

**Result.**

- The hostname stays during an AWS deployment. A new AWS deployment can get a new
  hostname.
- The load balancer is internal, and only the CloudFront VPC origin can reach it.
- The ALB login is not available behind CloudFront
  ([Envoy Gateway behind CloudFront](#envoy-gateway-behind-cloudfront)).

### Envoy Gateway behind CloudFront

**Decision.** On 2026-11-03 the user selected Envoy Gateway as the Gateway API
controller of the AWS webpages. The AWS Load Balancer Controller only makes the
internal NLB for the Envoy Service.

**Reason.**

- ALB login actions need an HTTPS listener on the ALB. Behind CloudFront, the
  load balancer gets HTTP, because no trusted certificate matches its name.
- A `SecurityPolicy` of Envoy Gateway keeps the login and the role rules for each
  route and method in one place. The ALB option needs two oauth2-proxy instances
  and ALB method rules.
- The Gateway API resources of Envoy Gateway can be tested without AWS, before a
  paid run.

**Result.**

- The Envoy pods carry all the page traffic. They need replicas in each zone, a
  disruption budget and a drain at shutdown.
- The AWS deployment has two controllers: Envoy Gateway and the AWS Load Balancer
  Controller.
- The qualification passed on 2026-11-04, thus Envoy Gateway v1.9.3 stays. On a local
  test cluster, the rendered role rules let a token with the group `operator` change a
  workload, and gave 403 to `viewer`, to no group and to a group claim that is not a
  list. A write without the own `Origin` got 403, and each other path and method got
  404 ([changelog](CHANGELOG.md#step-78--stable-aws-webpage-urls-and-access-control)).

### One login policy on the Gateway

**Decision.** One SecurityPolicy on the Gateway gives the Cognito login, the role rules
and the CSRF check to all the webpage routes. The policy gives the validated ID token to
the JWT filter, and the role rules read its `cognito:groups` list. The routes and the
policy exist only after the Terraform webpages module, because the login needs the
CloudFront name.

**Reason.**

- Two policies with a login on one host break the login, because each policy has its own
  cookies ([envoyproxy/gateway#8649](https://github.com/envoyproxy/gateway/issues/8649)).
- The tokens in the cookies are encrypted. Envoy gives the ID token to the JWT filter
  after it validates the cookies, and it removes the `Authorization` header of the
  client. A client thus cannot give its own token.
- `SameSite=Lax` keeps the token cookies from cross-site requests. `Strict` would drop
  the login state on the redirect from Cognito. The CSRF filter also rejects a PUT or
  POST without the own `Origin`.

**Result.**

- Before the webpages module, the Gateway has no route, thus nothing is open.
- Cognito makes the client secret, thus it is in the Terraform state of the webpages
  module. The runbook copies it to a Kubernetes Secret that Argo CD does not deliver.
- The users have no known password after the apply. The operator sets each password.
- The automated checks have no password. The operator checks the two roles in a browser.
