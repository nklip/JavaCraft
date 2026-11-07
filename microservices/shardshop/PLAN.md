# ShardShop implementation plan

[ShardShop](README.md) › **Implementation plan** · [Changelog](CHANGELOG.md) · [Decisions](DECISIONS.md) · [Architecture](ARCHITECTURE.md)

This plan lists the implementation steps of ShardShop and their status. The
[CHANGELOG](CHANGELOG.md) has the result of each done step, and
[DECISIONS.md](DECISIONS.md) has the reasons for the main choices. The acceptance
condition of each done step is in Git history: milestones 0–6 in the tag
`shardshop-docs-full-2026-10-31`, and milestone 7 in commit `3baa38da`.

## Contents
1. [Status](#1-status)
2. [Rules for a step](#2-rules-for-a-step)
3. [Milestones 0–6](#3-milestones-06)
4. [Milestone 7: AWS deployment](#4-milestone-7-aws-deployment)

## 1. Status
<sub>[Back to Contents](#contents)</sub>

- **Milestones 0–6 are done.** Step 6.9 passed the complete acceptance run on
  2026-10-31 ([record](CHANGELOG.md#step-69--complete-demo-and-acceptance-run)).
- **Step 6.8 ended as research.** HTTP/2 gave no performance gain, thus ShardShop
  keeps HTTP/1.1 ([decision](DECISIONS.md#keep-http11)).
- **Milestone 7 (AWS deployment) is done.** Steps 7.1 to 7.10 are in
  [section 4](#4-milestone-7-aws-deployment).
- **Version deadlines:** the version review of 2026-11-04 is still due. The local
  risk acceptance ends and the CNPG upgrade is due on 2026-11-30
  ([open findings](VERSIONS.md#3-open-findings-and-risk-acceptance)).

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
     ([seller profit](shardshop-product/ARCHITECTURE.md#4-seller-profit)).
   - The placement is `shard-a=US`, `shard-b=EU` and `shard-c=ASIA`
     ([regional placement](database/ARCHITECTURE.md#1-sharding-and-replication)).
   - Flyway SQL has no SQL functions or triggers. Retained baselines are
     immutable, thus schema changes use fix-forward migrations
     ([migrations](database/ARCHITECTURE.md#4-migrations)).
7. Test Java behavior only with Maven tests. Where IO matters, use the
   `integration` profile with Testcontainers and pinned images. Use the opt-in
   `cluster` profile for checks on a deployment. Do not add a Bash or Python check for
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
| [0.1 Version and artifact inventory](CHANGELOG.md#step-01--version-and-artifact-inventory) | — | Done |
| [0.2 Maven skeleton](CHANGELOG.md#step-02--maven-skeleton) | 0.1 | Done |
| [0.3 Dependency and test configuration](CHANGELOG.md#step-03--dependency-and-test-configuration) | 0.2 | Done |
| [0.4 Shared shard-routing module](CHANGELOG.md#step-04--shared-shard-routing-module) | 0.3 | Done |
| [0.5 Quarkus 3.40.1 migration](CHANGELOG.md#step-05--quarkus-3401-migration) | 0.4, 2.4 | Done |

### Milestone 1: Local cluster and one replicated shard

| Step | Depends on | Status |
|---|---|---|
| [1.1 Local Kubernetes bootstrap](CHANGELOG.md#step-11--local-kubernetes-bootstrap) | 0.1 | Done |
| [1.2 CloudNativePG installation](CHANGELOG.md#step-12--cloudnativepg-installation) | 1.1 | Done |
| [1.3 First quorum shard cluster](CHANGELOG.md#step-13--first-quorum-shard-cluster) | 1.2 | Done |

### Milestone 2: Storage, ownership, and migrations

| Step | Depends on | Status |
|---|---|---|
| [2.1 Remaining shard clusters](CHANGELOG.md#step-21--remaining-shard-clusters) | 1.3 | Done |
| [2.2 Separate ledger database](CHANGELOG.md#step-22--separate-ledger-database) | 1.3 | Done |
| [2.3 Database change management and catalog schema](CHANGELOG.md#step-23--database-change-management-and-catalog-schema) | 2.1 | Done |
| [2.4 Single shard inventory and configured routing](CHANGELOG.md#step-24--single-shard-inventory-and-configured-routing) | 2.3, 0.4 | Done |
| [2.5 Ordering schema and catalog access](CHANGELOG.md#step-25--ordering-schema-and-catalog-access) | 2.3, 2.4 | Done |
| [2.6 Ledger schema and permanent decisions](CHANGELOG.md#step-26--ledger-schema-and-permanent-decisions) | 2.2, 2.3 | Done |

### Milestone 3: Product contracts, IDs, and workloads

| Step | Depends on | Status |
|---|---|---|
| [3.1 HTTP and message contracts](CHANGELOG.md#step-31--http-and-message-contracts) | 0.5 | Done |
| [3.2 Common utilities, ID validation and currency selection](CHANGELOG.md#step-32--common-utilities-id-validation-and-currency-selection) | 3.1 | Done |
| [3.3 Bounded Snowflake generation](CHANGELOG.md#step-33--bounded-snowflake-generation) | 0.5 | Done |
| [3.4 Generator allocation at JVM startup](CHANGELOG.md#step-34--generator-allocation-at-jvm-startup) | 1.1, 3.3 | Done |
| [3.5 Workload-owned datasets and service-issued IDs](CHANGELOG.md#step-35--workload-owned-datasets-and-service-issued-ids) | 3.1, 3.2 | Done |
| [3.6 Seller and product API on primaries](CHANGELOG.md#step-36--seller-and-product-api-on-primaries) | 0.4, 2.3, 3.2, 3.4, 3.5 | Done |
| [3.7 Product seeder application](CHANGELOG.md#step-37--product-seeder-application) | 3.5, 3.6 | Done |
| [3.8 Product reader application](CHANGELOG.md#step-38--product-reader-application) | 3.5, 3.6 | Done |
| [3.9 Product deployment and seeding gate](CHANGELOG.md#step-39--product-deployment-and-seeding-gate) | 3.7, 3.8 | Done |
| [3.10 Maven integration tests with Testcontainers](CHANGELOG.md#step-310--maven-integration-tests-with-testcontainers) | 3.6, 3.7, 3.8, 3.9 | Done |
| [3.11 Generator IDs in the application JVM](CHANGELOG.md#step-311--generator-ids-in-the-application-jvm) | 3.4, 3.9, 3.10 | Done |
| [3.12 Contract checks in Maven and one image script](CHANGELOG.md#step-312--contract-checks-in-maven-and-one-image-script) | 3.10 | Done |
| [3.13 Build current sources and qualify images through up.sh](CHANGELOG.md#step-313--build-current-sources-and-qualify-images-through-upsh) | 3.12 | Done |
| [3.14 Create, resume and reconcile the cluster through up.sh](CHANGELOG.md#step-314--create-resume-and-reconcile-the-cluster-through-upsh) | 3.13 | Done |
| [3.15 Replace live verification scripts with Maven scenarios](CHANGELOG.md#step-315--replace-live-verification-scripts-with-maven-scenarios) | 3.10, 3.11, 3.14 | Done |
| [3.16 Migrate and deploy current image digests through up.sh](CHANGELOG.md#step-316--migrate-and-deploy-current-image-digests-through-upsh) | 3.15 | Done |
| [3.17 Complete up.sh with a deployment success gate](CHANGELOG.md#step-317--complete-upsh-with-a-deployment-success-gate) | 3.15, 3.16 | Done |
| [3.18 Stop the entire cluster through down.sh](CHANGELOG.md#step-318--stop-the-entire-cluster-through-downsh) | 3.14 | Done |
| [3.19 Replace the offline dataset generator with Java tooling](CHANGELOG.md#step-319--replace-the-offline-dataset-generator-with-java-tooling) | 3.12 | Done |
| [3.20 Retire old scripts and qualify the explicit lifecycle workflow](CHANGELOG.md#step-320--retire-old-scripts-and-qualify-the-explicit-lifecycle-workflow) | 3.17, 3.18, 3.19 | Done |

### Milestone 4: Complete order-to-ledger saga

| Step | Depends on | Status |
|---|---|---|
| [4.1 Order validation and atomic acceptance](CHANGELOG.md#step-41--order-validation-and-atomic-acceptance) | 2.5, 3.6, 3.11, 3.12 | Done |
| [4.2 Stock reservation step](CHANGELOG.md#step-42--stock-reservation-step) | 2.5, 4.1 | Done |
| [4.3 RabbitMQ topology and policies](CHANGELOG.md#step-43--rabbitmq-topology-and-policies) | 0.5, 1.1, 3.1 | Done |
| [4.4 Order outbox CDC relay](CHANGELOG.md#step-44--order-outbox-cdc-relay) | 4.2, 4.3 | Done |
| [4.5 Ledger command consumer](CHANGELOG.md#step-45--ledger-command-consumer) | 2.6, 3.2, 4.3 | Done |
| [4.6 Ledger result publisher](CHANGELOG.md#step-46--ledger-result-publisher) | 4.5 | Done |
| [4.7 Order result consumer](CHANGELOG.md#step-47--order-result-consumer) | 4.4, 4.6 | Done |
| [4.8 Order producer application](CHANGELOG.md#step-48--order-producer-application) | 3.5, 4.1 | Done |
| [4.9 Saga deployment and first end-to-end run](CHANGELOG.md#step-49--saga-deployment-and-first-end-to-end-run) | 3.20, 4.7, 4.8 | Done |
| [4.10 Read-only regional product page](CHANGELOG.md#step-410--read-only-regional-product-page) | 4.9 | Done |
| [4.11 Read-only regional order page](CHANGELOG.md#step-411--read-only-regional-order-page) | 4.10 | Done |
| [4.12 Regional and country currency policy](CHANGELOG.md#step-412--regional-and-country-currency-policy) | 3.5, 3.6, 4.5, 4.8, 4.11 | Done |
| [4.13 Workload traffic-control webpages](CHANGELOG.md#step-413--workload-traffic-control-webpages) | 4.12 | Done |
| [4.14 Concurrent order writes and a separate saga process](CHANGELOG.md#step-414--concurrent-order-writes-and-a-separate-saga-process) | 4.13 | Done |
| [4.15 Architecture overview, module design documents and sustainable capacity](CHANGELOG.md#step-415--architecture-overview-module-design-documents-and-sustainable-capacity) | 4.14 | Done |

### Milestone 5: Recovery, retention, and backpressure

| Step | Depends on | Status |
|---|---|---|
| [5.1 Durable saga reconciliation](CHANGELOG.md#step-51--durable-saga-reconciliation) | 4.14 | Done |
| [5.2 Safe transport cleanup](CHANGELOG.md#step-52--safe-transport-cleanup) | 5.1 | Done |
| [5.3 Manual reconciliation reset](CHANGELOG.md#step-53--manual-reconciliation-reset) | 5.2 | Done |
| [5.4 Retry and capacity drills](CHANGELOG.md#step-54--retry-and-capacity-drills) | 5.3 | Done |
| [5.5 Crash recovery and observability](CHANGELOG.md#step-55--crash-recovery-and-observability) | 5.4 | Done |

### Milestone 6: Read profiles, failover, restore, and operation

| Step | Depends on | Status |
|---|---|---|
| [6.0 Application maintenance and retained-upgrade evidence](CHANGELOG.md#step-60--application-maintenance-and-retained-upgrade-evidence) | 5.5 | Done |
| [6.1 Strict replica-read drill](CHANGELOG.md#step-61--strict-replica-read-drill) | 6.0, 3.9, 4.13 | Done |
| [6.2 Synchronous failover drills](CHANGELOG.md#step-62--synchronous-failover-drills) | 6.0, 5.5, 6.1 | Done |
| [6.3 Coordinated backup and isolated restore](CHANGELOG.md#step-63--coordinated-backup-and-isolated-restore) | 5.3, 6.2 | Done |
| [6.4 Restore audit and recovery](CHANGELOG.md#step-64--restore-audit-and-recovery) | 6.3 | Done |
| [6.5 Order-write p95 below 100 ms](CHANGELOG.md#step-65--order-write-p95-below-100-ms) | 6.0, 4.14, 5.5 | Done |
| [6.6 Sustained 30 new orders/s](CHANGELOG.md#step-66--sustained-30-new-orderss) | 6.0, 6.5, 4.14 | Done |
| [6.7 Qualify 1,000 product reads/s](CHANGELOG.md#step-67--qualify-1000-product-readss) | 6.0, 4.15 | Done |
| [6.8 HTTP/2 transport](CHANGELOG.md#step-68--http2-transport) | 6.4, 6.6, 6.7 | Research Done → Downgrade in performance → Decision to keep HTTP/1.1 |
| [6.9 Complete demo and acceptance run](CHANGELOG.md#step-69--complete-demo-and-acceptance-run) | 6.4, 6.8 | Done |

## 4. Milestone 7: AWS deployment
<sub>[Back to Contents](#contents)</sub>

This milestone adds the AWS deployment without a change to the local deployment:
every earlier step must still run and pass on kind. Qualify each new tool,
provider and service under [VERSIONS.md](VERSIONS.md) before use. Before an AWS
run, give the cost estimate and get the approval of the user (`AGENTS.md`
section 11). Record the costs and the teardown commands.

| Step | Depends on | Status |
|---|---|---|
| [7.1 Terraform AWS foundation](CHANGELOG.md#step-71--terraform-aws-foundation) | 6.9 | Done |
| [7.2 EKS values and operators](CHANGELOG.md#step-72--eks-values-and-operators) | 7.1 | Done |
| [7.3 Cloud durability](CHANGELOG.md#step-73--cloud-durability) | 7.2 | Done |
| [7.4 Local and AWS architecture overview](CHANGELOG.md#step-74--local-and-aws-architecture-overview) | 7.3 | Done |
| [7.5 Argo CD delivery of the database layer](CHANGELOG.md#step-75--argo-cd-delivery-of-the-database-layer) | 7.4 | Done |
| [7.6 Applications and gates through Argo CD](CHANGELOG.md#step-76--applications-and-gates-through-argo-cd) | 7.5 | Done |
| [7.7 Cloud acceptance](CHANGELOG.md#step-77--cloud-acceptance) | 7.6 | Done |
| [7.8 Stable AWS webpage URLs and access control](CHANGELOG.md#step-78--stable-aws-webpage-urls-and-access-control) | 4.10, 7.7 | Done |
| [7.9 Sustained 30 new orders/s on EKS](CHANGELOG.md#step-79--sustained-30-new-orderss-on-eks) | 7.8 | Done |
| [7.10 Test packages by deployment](CHANGELOG.md#step-710--test-packages-by-deployment) | 7.9 | Done |
