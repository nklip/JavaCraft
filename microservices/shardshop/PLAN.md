# ShardShop implementation plan

[ShardShop](README.md) › **Implementation plan** · [Changelog](CHANGELOG.md) · [Decisions](DECISIONS.md) · [Architecture](ARCHITECTURE.md)

This plan lists the implementation steps of ShardShop and their status. The
[CHANGELOG](CHANGELOG.md) has the result of each done step, and
[DECISIONS.md](DECISIONS.md) has the reasons for the main choices. The full plan
before the documentation compression, with the acceptance condition of each done
step, is in the Git tag `shardshop-docs-full-2026-10-31`.

## Contents
1. [Status](#1-status)
2. [Rules for a step](#2-rules-for-a-step)
3. [Milestones 0–6](#3-milestones-06)
4. [Milestone 7: AWS deployment](#4-milestone-7-aws-deployment)

## 1. Status
<sub>[Back to Contents](#contents)</sub>

- **Milestones 0–6 are done.** Step 6.9 passed the complete acceptance run on
  2026-10-31 ([record](CHANGELOG.md#step-69)).
- **Step 6.8 ended as research.** HTTP/2 gave no performance gain, thus ShardShop
  keeps HTTP/1.1 ([decision](DECISIONS.md#keep-http11)).
- **Milestone 7 (AWS deployment):** steps 7.1 to 7.7 are **Done**
  ([7.1](CHANGELOG.md#step-71), [7.2](CHANGELOG.md#step-72),
  [7.3](CHANGELOG.md#step-73), [7.4](CHANGELOG.md#step-74),
  [7.5](CHANGELOG.md#step-75), [7.6](CHANGELOG.md#step-76),
  [7.7](CHANGELOG.md#step-77)). The other steps are **Planned**.
- **Version deadlines:** the local risk acceptance ends and the CNPG upgrade is
  due on 2026-11-30 ([open findings](VERSIONS.md#3-open-findings-and-risk-acceptance)).

## 2. Rules for a step
<sub>[Back to Contents](#contents)</sub>

1. Before a step, read `AGENTS.md`, this plan, the root and module documents,
   [VERSIONS.md](VERSIONS.md) and [DECISIONS.md](DECISIONS.md).
2. Do the steps in dependency order. "Depends on" lists the direct prerequisites.
   Implement only the selected step. If a prerequisite is missing, record the
   blocker.
3. Set the status to **In Progress** when work starts. Set it to **Done** only
   after the acceptance condition and the relevant checks pass. Use **Blocked**
   with a concrete reason. Partial work is not done.
4. Record each done step in a short [CHANGELOG](CHANGELOG.md) entry: the change,
   the key result and the lesson. Keep generated reports, logs and temporary tools
   outside version control.
5. Keep the changes inside ShardShop. Keep the six applications, the schema
   ownership of `database/`, the Snowflake contracts and public APIs without shard
   selectors.
6. Obey the architecture constraints:
   - Only product and order create IDs
     ([ID ownership](shardshop-core/README.md#goal-and-module-boundaries)).
   - Workload definitions have no IDs
     ([datasets](shardshop-workload/shardshop-datasets/README.md#definitions-and-keys)).
   - Seller profit is the confirmed margin in each currency
     ([seller profit](shardshop-product/ARCHITECTURE.md#seller-profit)).
   - The placement is `shard-a=US`, `shard-b=EU` and `shard-c=ASIA`
     ([regional placement](database/ARCHITECTURE.md#sharding-and-replication)).
   - Flyway SQL has no SQL functions or triggers. Retained baselines are
     immutable, thus schema changes use fix-forward migrations
     ([migrations](database/ARCHITECTURE.md#migrations)).
7. Test Java behavior only with Maven tests. Where IO matters, use the
   `integration` profile with Testcontainers and pinned images. Use the opt-in
   `kind` profile for checks on the lab. Do not add a Bash or Python check for
   Java behavior. A change of prose only needs a link check and a content check,
   not a build or a lab run.
8. Use the retained lab for checks that do not disrupt it. For a fresh lab, obey
   the [disposable test cluster rules](infra/RUNBOOK.md#7-disposable-test-clusters).
   Never run two four-node labs at the same time. Name the kube context in every
   command, and use bounded waits for Kubernetes checks.
9. Take Maven, Node.js, kind, kubectl, Helm, Helmfile, Skaffold and Docker from
   the `PATH` of the user. Do not download, vendor, wrap or pin tool binaries in
   the repository. Vendor a third-party chart only with a verified checksum in the
   lock.
10. Keep each installed component inside its support window. Complete a due
    review before a step. The user approves each update
    ([update rules](VERSIONS.md#2-update-rules)).
11. Give each behavior to the tool that owns it. A script is the last choice
    ([decision](DECISIONS.md#tool-ownership)). Do not add lifecycle entry points,
    Python wrappers, custom JSON or YAML parsing in Bash, or reusable assertions
    in Bash.
12. Milestones 0–6 run only on this machine: on the kind lab or in local
    containers. A local step never depends on milestone 7 or on a cloud service.

## 3. Milestones 0–6
<sub>[Back to Contents](#contents)</sub>

Each step name links to its CHANGELOG entry. Step 0.5 ran after step 2.4.

### Milestone 0: Supported baseline and project skeleton

| Step | Depends on | Status |
|---|---|---|
| [0.1 Version and artifact inventory](CHANGELOG.md#step-01) | — | Done |
| [0.2 Maven skeleton](CHANGELOG.md#step-02) | 0.1 | Done |
| [0.3 Dependency and test configuration](CHANGELOG.md#step-03) | 0.2 | Done |
| [0.4 Shared shard-routing module](CHANGELOG.md#step-04) | 0.3 | Done |
| [0.5 Quarkus 3.40.1 migration](CHANGELOG.md#step-05) | 0.4, 2.4 | Done |

### Milestone 1: Local cluster and one replicated shard

| Step | Depends on | Status |
|---|---|---|
| [1.1 Local Kubernetes bootstrap](CHANGELOG.md#step-11) | 0.1 | Done |
| [1.2 CloudNativePG installation](CHANGELOG.md#step-12) | 1.1 | Done |
| [1.3 First quorum shard cluster](CHANGELOG.md#step-13) | 1.2 | Done |

### Milestone 2: Storage, ownership, and migrations

| Step | Depends on | Status |
|---|---|---|
| [2.1 Remaining shard clusters](CHANGELOG.md#step-21) | 1.3 | Done |
| [2.2 Separate ledger database](CHANGELOG.md#step-22) | 1.3 | Done |
| [2.3 Database change management and catalog schema](CHANGELOG.md#step-23) | 2.1 | Done |
| [2.4 Single shard inventory and configured routing](CHANGELOG.md#step-24) | 2.3, 0.4 | Done |
| [2.5 Ordering schema and catalog access](CHANGELOG.md#step-25) | 2.3, 2.4 | Done |
| [2.6 Ledger schema and permanent decisions](CHANGELOG.md#step-26) | 2.2, 2.3 | Done |

### Milestone 3: Product contracts, IDs, and workloads

| Step | Depends on | Status |
|---|---|---|
| [3.1 HTTP and message contracts](CHANGELOG.md#step-31) | 0.5 | Done |
| [3.2 Common utilities, ID validation and currency selection](CHANGELOG.md#step-32) | 3.1 | Done |
| [3.3 Bounded Snowflake generation](CHANGELOG.md#step-33) | 0.5 | Done |
| [3.4 Generator allocation at JVM startup](CHANGELOG.md#step-34) | 1.1, 3.3 | Done |
| [3.5 Workload-owned datasets and service-issued IDs](CHANGELOG.md#step-35) | 3.1, 3.2 | Done |
| [3.6 Seller and product API on primaries](CHANGELOG.md#step-36) | 0.4, 2.3, 3.2, 3.4, 3.5 | Done |
| [3.7 Product seeder application](CHANGELOG.md#step-37) | 3.5, 3.6 | Done |
| [3.8 Product reader application](CHANGELOG.md#step-38) | 3.5, 3.6 | Done |
| [3.9 Product deployment and seeding gate](CHANGELOG.md#step-39) | 3.7, 3.8 | Done |
| [3.10 Maven integration tests with Testcontainers](CHANGELOG.md#step-310) | 3.6, 3.7, 3.8, 3.9 | Done |
| [3.11 Generator IDs in the application JVM](CHANGELOG.md#step-311) | 3.4, 3.9, 3.10 | Done |
| [3.12 Contract checks in Maven and one image script](CHANGELOG.md#step-312) | 3.10 | Done |
| [3.13 Build current sources and qualify images through up.sh](CHANGELOG.md#step-313) | 3.12 | Done |
| [3.14 Create, resume and reconcile the cluster through up.sh](CHANGELOG.md#step-314) | 3.13 | Done |
| [3.15 Replace live verification scripts with Maven scenarios](CHANGELOG.md#step-315) | 3.10, 3.11, 3.14 | Done |
| [3.16 Migrate and deploy current image digests through up.sh](CHANGELOG.md#step-316) | 3.15 | Done |
| [3.17 Complete up.sh with a deployment success gate](CHANGELOG.md#step-317) | 3.15, 3.16 | Done |
| [3.18 Stop the entire cluster through down.sh](CHANGELOG.md#step-318) | 3.14 | Done |
| [3.19 Replace the offline dataset generator with Java tooling](CHANGELOG.md#step-319) | 3.12 | Done |
| [3.20 Retire old scripts and qualify the explicit lifecycle workflow](CHANGELOG.md#step-320) | 3.17, 3.18, 3.19 | Done |

### Milestone 4: Complete order-to-ledger saga

| Step | Depends on | Status |
|---|---|---|
| [4.1 Order validation and atomic acceptance](CHANGELOG.md#step-41) | 2.5, 3.6, 3.11, 3.12 | Done |
| [4.2 Stock reservation step](CHANGELOG.md#step-42) | 2.5, 4.1 | Done |
| [4.3 RabbitMQ topology and policies](CHANGELOG.md#step-43) | 0.5, 1.1, 3.1 | Done |
| [4.4 Order outbox CDC relay](CHANGELOG.md#step-44) | 4.2, 4.3 | Done |
| [4.5 Ledger command consumer](CHANGELOG.md#step-45) | 2.6, 3.2, 4.3 | Done |
| [4.6 Ledger result publisher](CHANGELOG.md#step-46) | 4.5 | Done |
| [4.7 Order result consumer](CHANGELOG.md#step-47) | 4.4, 4.6 | Done |
| [4.8 Order producer application](CHANGELOG.md#step-48) | 3.5, 4.1 | Done |
| [4.9 Saga deployment and first end-to-end run](CHANGELOG.md#step-49) | 3.20, 4.7, 4.8 | Done |
| [4.10 Read-only regional product page](CHANGELOG.md#step-410) | 4.9 | Done |
| [4.11 Read-only regional order page](CHANGELOG.md#step-411) | 4.10 | Done |
| [4.12 Regional and country currency policy](CHANGELOG.md#step-412) | 3.5, 3.6, 4.5, 4.8, 4.11 | Done |
| [4.13 Workload traffic-control webpages](CHANGELOG.md#step-413) | 4.12 | Done |
| [4.14 Concurrent order writes and a separate saga process](CHANGELOG.md#step-414) | 4.13 | Done |
| [4.15 Architecture overview, module design documents and sustainable capacity](CHANGELOG.md#step-415) | 4.14 | Done |

### Milestone 5: Recovery, retention, and backpressure

| Step | Depends on | Status |
|---|---|---|
| [5.1 Durable saga reconciliation](CHANGELOG.md#step-51) | 4.14 | Done |
| [5.2 Safe transport cleanup](CHANGELOG.md#step-52) | 5.1 | Done |
| [5.3 Manual reconciliation reset](CHANGELOG.md#step-53) | 5.2 | Done |
| [5.4 Retry and capacity drills](CHANGELOG.md#step-54) | 5.3 | Done |
| [5.5 Crash recovery and observability](CHANGELOG.md#step-55) | 5.4 | Done |

### Milestone 6: Read profiles, failover, restore, and operation

| Step | Depends on | Status |
|---|---|---|
| [6.0 Application maintenance and retained-upgrade evidence](CHANGELOG.md#step-60) | 5.5 | Done |
| [6.1 Strict replica-read drill](CHANGELOG.md#step-61) | 6.0, 3.9, 4.13 | Done |
| [6.2 Synchronous failover drills](CHANGELOG.md#step-62) | 6.0, 5.5, 6.1 | Done |
| [6.3 Coordinated backup and isolated restore](CHANGELOG.md#step-63) | 5.3, 6.2 | Done |
| [6.4 Restore audit and recovery](CHANGELOG.md#step-64) | 6.3 | Done |
| [6.5 Order-write p95 below 100 ms](CHANGELOG.md#step-65) | 6.0, 4.14, 5.5 | Done |
| [6.6 Sustained 30 new orders/s](CHANGELOG.md#step-66) | 6.0, 6.5, 4.14 | Done |
| [6.7 Qualify 1,000 product reads/s](CHANGELOG.md#step-67) | 6.0, 4.15 | Done |
| [6.8 HTTP/2 transport](CHANGELOG.md#step-68) | 6.4, 6.6, 6.7 | Research Done → Downgrade in performance → Decision to keep HTTP/1.1 |
| [6.9 Complete demo and acceptance run](CHANGELOG.md#step-69) | 6.4, 6.8 | Done |

## 4. Milestone 7: AWS deployment
<sub>[Back to Contents](#contents)</sub>

This milestone adds the AWS deployment without a change to the local deployment:
every earlier step must still run and pass on kind. Qualify each new tool,
provider and service under [VERSIONS.md](VERSIONS.md) before use. Record the costs
and the teardown commands.

### 7.1 Terraform AWS foundation

Depends on 6.9. Status: **Done** ([record](CHANGELOG.md#step-71)).

- `infra/terraform/` provisions these resources: a VPC across three availability
  zones, an EKS cluster on a Kubernetes minor version inside the CNPG support
  matrix, workers in all zones, ECR repositories, an encrypted S3 bucket for
  backups, and least-privilege IAM for the workloads.
- The Terraform state is in a remote backend with a lock.
- `terraform destroy` removes everything.

### 7.2 EKS values and operators

Depends on 7.1. Status: **Done** ([record](CHANGELOG.md#step-72)).

- The same vendored CloudNativePG chart and `values.yaml` install the operator on
  EKS.
- `infra/helm/shardshop/eks-values.yaml` renders the shared chart and inventory
  with gp3 storage and one instance of each shard in each zone.
- Keep the separate order HTTP and saga Deployments, their process modes and their
  rollout strategies.
- The topology drills of steps 1.3 and 2.1 pass on EKS.

### 7.3 Cloud durability

Depends on 7.2. Status: **Done** ([record](CHANGELOG.md#step-73)).

- Every shard and the ledger back up to S3 with continuous WAL archiving, and a
  point-in-time restore passes.
- RabbitMQ runs three brokers with three-member quorum queues.
- Runtime secrets come from AWS Secrets Manager.
- The local profile keeps its single broker and logical dumps.

### 7.4 Local and AWS architecture overview

Depends on 7.3. Status: **Done** ([record](CHANGELOG.md#step-74)).

- [`microservices/README.md`](../README.md#modules) lists ShardShop, and the
  ShardShop README has the same navigation link back as the other modules.
- [ARCHITECTURE.md](ARCHITECTURE.md) explains what the local deployment and the
  AWS deployment share, what only the local deployment has, and what the AWS
  deployment adds.
- Mermaid diagrams show this: the high-level design first, then the low-level
  design of the delivery, the network and placement, the backups, the secrets and
  the broker.
- The text and the diagrams use ASD-STE100. Use a diagram instead of long text,
  and clarity instead of small details.

### 7.5 Argo CD delivery of the database layer

Depends on 7.4. Status: **Done** ([record](CHANGELOG.md#step-75)).

- A self-managed Argo CD 3.5 from a vendored, verified Helm chart runs on EKS.
- Argo CD applies the [database layer](database/ARCHITECTURE.md#database-change-management)
  from the EKS values: the CloudNativePG operator with the same vendored chart, the
  backup plugin, the infrastructure, the migrations and the routing ConfigMap.
- The source is an OCI artifact in ECR with a digest pin. Only the migrations use
  the immutable chart version, because Argo CD puts the revision into the names of
  generated hook Jobs. The runbook packages the charts from the working tree, thus
  a run needs no Git push.
- Implement an explicit delivery contract that replaces the Helmfile stage
  dependencies and the Helm revision-based Job identities. Argo
  [renders with `helm template`](https://argo-cd.readthedocs.io/en/stable/user-guide/helm/),
  thus `.Release.Revision` cannot identify a new sync. Use
  [sync phases and waves](https://argo-cd.readthedocs.io/en/stable/user-guide/sync-waves/)
  with readiness checks.
- Give each migration attempt an identity without collisions, and safe retention
  and deletion policies. Never delete an active or interrupted migration to retry
  it.
- Verify a resync without changes, changed migrations, a failure before the
  publication, interrupted attempts and recovery. Do not cache a completed Job, and
  do not claim an SQL rollback.
- The kind renders and the local Helmfile workflow do not change.

### 7.6 Applications and gates through Argo CD

Depends on 7.5. Status: **Done** ([record](CHANGELOG.md#step-76)).

- Argo CD deploys the six application artifacts, with both order processes, from
  ECR with digest pins. The applications layer uses the immutable chart version, as
  the migrations do, because it has generated hook Jobs.
- The current Maven safety and acceptance checks run in pre-sync and post-sync
  verification Jobs with a gates image. Checks that read kind nodes get an EKS
  variant.
- Give each seeder attempt an identity without collisions, and verify seeder
  reruns.
- Prevent a selective sync that bypasses the required hooks or gates.

### 7.7 Cloud acceptance

Depends on 7.6. Status: **Done** ([record](CHANGELOG.md#step-77)).

- All eleven scenarios pass on EKS with evidence and costs. A check that cannot
  run on EKS has a documented reason.
- The local Helmfile workflow still passes step 6.9 without changes.

### 7.8 Stable AWS webpage URLs and access control

Depends on 4.10 and 7.7. Status: **Planned**.

- Qualify Envoy Gateway as the Gateway API controller
  ([decision](DECISIONS.md#envoy-gateway-behind-cloudfront)). The qualification
  must prove that a `SecurityPolicy` rule lets only the Cognito operator group
  change a workload. If it cannot, use the AWS Load Balancer Controller with
  oauth2-proxy.
- Deliver the `GatewayClass`, `Gateway`, `HTTPRoute` and `SecurityPolicy`
  resources through GitOps in the EKS overlay. The AWS Load Balancer Controller
  makes an internal NLB for the Envoy Service. Run the Envoy pods in each zone,
  with a disruption budget and a drain at shutdown.
- Before the AWS run, test the routes and the role rules in Maven on a local
  Kubernetes API where possible.
- Five webpages and the APIs that they need use the default `*.cloudfront.net`
  hostname of one CloudFront distribution. The webpages are the product and order
  pages and the three
  [workload-control pages](shardshop-core/workload-control/README.md#pages-and-api).
  CloudFront supplies the certificate and the HTTP-to-HTTPS redirect. There is no
  domain, Route 53 zone or ACM certificate
  ([decision](DECISIONS.md#cloudfront-default-hostname)).
- A CloudFront VPC origin connects the distribution to the internal load
  balancer. If no trusted certificate can match the load balancer name, the origin
  connection uses HTTP on this private path. Document the result.
- Terraform owns the distribution, the VPC origin and a Cognito user pool with a
  group for each role. The AWS Load Balancer Controller creates the NLB, thus
  Terraform applies the origin after Argo CD creates the `Gateway`. The user attaches the IAM
  permissions for these services in
  [`operator-policy-webpages.json`](infra/terraform/operator-policy-webpages.json).
- The access is public through CloudFront, with a login. Enforce authentication
  and role-based authorization on the webpages and the APIs. A viewer role can
  read all five pages. Only an operator role can change a workload: set the rate,
  pause, resume and start a seeder run. A cross-site request cannot change a
  workload.
- Expose only the intended routes and methods: `GET` on the product and order
  pages and APIs, and the methods of the
  [control contract](shardshop-core/workload-control/src/main/resources/contracts/openapi.yaml)
  on the workload pages and APIs. Only CloudFront can reach the load balancer.
  Other APIs, the databases and the brokers stay internal.
- The acceptance proves valid HTTPS, the redirect, and the rejection of
  unauthenticated and unauthorized requests. A viewer cannot change a workload.
  No direct backend access bypasses these controls.
- A copied URL such as `/products/?region=EU&page=3` shows the same view on a
  different authorized device, also after a login. The hostname stays after a pod
  replacement and an application redeployment, without port forwarding. A new
  AWS deployment can get a new hostname.
- Document the URLs, the access configuration, the costs and the teardown order:
  delete the `Gateway` before `terraform destroy`. Step 6.9 still passes locally
  without changes.
