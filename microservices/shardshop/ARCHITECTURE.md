# ShardShop architecture

[README](README.md) · [Runbook](RUNBOOK.md) · [Implementation plan](PLAN.md) · [Version policy](VERSIONS.md) · [Dated evidence](CHANGELOG.md)

ShardShop is a system-design lab. Its local deployment runs a regional product
catalog, sharded orders and an asynchronous ledger on one kind cluster. This overview
describes the implemented architecture. The AWS deployment runs the same system on
EKS with Argo CD ([section 5](#5-local-deployment-and-aws-deployment)). The [detailed documents](#6-detailed-design-documents) own each topic.

- **Shape:** six Quarkus applications, three regional PostgreSQL shards, one
  ledger database and one RabbitMQ broker.
- **Consistency:** each write is a local transaction. The order saga connects the
  shards and the ledger through CDC, RabbitMQ and compensation. There is no
  distributed transaction.
- **Qualified capacity on this lab:** 29.354 accepted orders/s and 995.111 product
  reads/s, measured separately ([section 4](#4-performance)).

## Contents
1. [System architecture](#1-system-architecture)
2. [Runtime topology](#2-runtime-topology)
3. [Sharding](#3-sharding)
4. [Performance](#4-performance)
5. [Local deployment and AWS deployment](#5-local-deployment-and-aws-deployment)
6. [Detailed design documents](#6-detailed-design-documents)

## 1. System architecture
<sub>[Back to Contents](#contents)</sub>

```mermaid
flowchart LR
    subgraph Clients["Workload applications · HTTP only"]
        Seed["Interactive seeder · 1 pod"]
        Read["Product reader · 1 pod"]
        Write["Order producer · 1 pod"]
    end
    subgraph Services["Application services"]
        Product["Product API · 2 pods"]
        Order["Order HTTP API · 2 pods"]
        Saga["Order saga · 1 pod"]
        Ledger["Ledger · 1 pod"]
    end
    Shards[("Regional PostgreSQL · 9 pods<br/>US / EU / ASIA<br/>Each: primary + 2 standbys")]
    MQ["RabbitMQ · 1 pod"]
    LDB[("Ledger PostgreSQL · 1 pod")]
    Seed -->|"Create sellers and products"| Product
    Read -->|"Read products"| Product
    Write -->|"Allocate, submit, observe orders"| Order
    Product <-->|"Catalog"| Shards
    Order <-->|"Buyers, orders and saga state"| Shards
    Shards -->|"Committed outbox via CDC"| Saga
    Saga -->|"Stock, status and seller profit"| Shards
    Saga -->|"Order commands"| MQ
    MQ -->|"Commands"| Ledger
    Ledger <-->|"Permanent decisions and results"| LDB
    Ledger -->|"Results"| MQ
    MQ -->|"Results"| Saga
```

| Component | Responsibility |
|---|---|
| Product API | Creates sellers and products, serves catalog reads and the `/products/` page, and applies the seller currency policy. |
| Order HTTP API | Creates buyers, issues order IDs, accepts orders atomically and serves the `/orders/` page. |
| Order saga | Reserves stock, relays committed commands through CDC, applies ledger results, credits seller profit, replays overdue ledger commands within a durable retry budget and removes expired transport after CDC checkpointing. |
| Ledger | Keeps one permanent decision for each order, publishes its result and removes expired transport. |
| Workloads | Seed the catalog, read products and produce orders through the public APIs only. |
| PostgreSQL and RabbitMQ | Regional shards, the separate ledger store and the message transport. |

Kubernetes Services spread HTTP connections across the product and order API pods.
They are network resources and add no pods. Workloads have no database access. A
one-shot bootstrap seeder Job fills the catalog; reader and producer traffic waits
until the complete catalog resolves. The interactive seeder stays available.

### Main flows

- **Read:** the product API derives the shard from the seller ID and reads that
  regional primary. An optional strict replica profile reads standbys, accepts
  stale data and fails when no standby is available.
- **Write:** the order API issues an order ID, validates the buyer and products,
  then commits the order and its initial saga state on the buyer's shard. It
  answers `202 Accepted`; confirmation comes later.
- **Saga:** a separate order process reserves stock on the seller shards,
  publishes the committed command through CDC and RabbitMQ, and applies the ledger
  decision. Confirmation creates seller-profit work; rejection releases the stock.
  Retries use durable identities, thus stock, ledger and profit effects occur once.

The saga and ledger processes clean up expired inbox and published outbox rows
after a configurable retention window (24 hours by default). Permanent decisions,
replay identities and business state remain available for recovery. Order outbox
cleanup also waits for a durable CDC checkpoint. These workers add no pods.
An operator can reset an exhausted retry budget for a selected pending saga;
terminal decisions remain unchanged.

Application metrics report pending work, exhausted retries, quarantine, database
pool pressure and CDC recovery state. RabbitMQ and PostgreSQL expose their queue
and replication pressure through native monitoring. These endpoints add no pods;
the [monitoring guide](infra/README.md#3-monitoring) owns their operational details.

```mermaid
sequenceDiagram
    autonumber
    participant P as Order producer
    participant H as Order HTTP API
    participant B as Buyer shard
    participant S as Order saga
    participant C as Seller shards
    participant Q as RabbitMQ
    participant L as Ledger
    P->>H: Allocate an order ID, then PUT the order
    H->>B: Commit the order and a PENDING_STOCK saga
    H-->>P: 202 Accepted
    S->>C: Reserve stock for each item
    S->>B: PENDING_LEDGER and a RecordOrder outbox row
    B-->>S: CDC delivers the committed outbox insert
    S->>Q: Publish RecordOrder
    Q->>L: Deliver the command
    L->>L: Save the permanent decision and its result
    L->>Q: Publish LedgerRecorded or LedgerRejected
    Q->>S: Deliver the result
    S->>B: CONFIRMED, or CANCELLED
    S->>C: Credit seller profit, or release the stock
    P->>H: GET the status until it is terminal
```

## 2. Runtime topology
<sub>[Back to Contents](#contents)</sub>

The kind lab runs **36 steady pods** in all namespaces. The `shardshop` namespace
contains 20 of them.

| Group | Pods | Contents |
|---|---:|---|
| Application services | 6 | Product API 2, order HTTP API 2, order saga 1, ledger 1 |
| Workloads | 3 | Product reader, order producer and interactive seeder |
| Regional PostgreSQL | 9 | Three shards, each with one primary and two standbys |
| Ledger PostgreSQL | 1 | One primary without a standby |
| RabbitMQ | 1 | One broker |
| **`shardshop` namespace** | **20** | Applications, databases and broker |
| CloudNativePG operator | 1 | Database operator |
| Kubernetes system | 14 | kind control plane, DNS and networking |
| Storage | 1 | Local-path provisioner |
| **All namespaces** | **36** | |

The counts exclude transient Jobs (bootstrap seeder, Flyway migrations, DNS probes
and drills), retained completed Job pods and rollout surge pods. The four kind
node containers (one control plane and three workers) are hosts for these pods,
not more pods. The counts describe this lab, not a production size. The broker and
the ledger database have one pod each, thus the lab gives them no high availability.

The [infrastructure design](infra/ARCHITECTURE.md#5-runtime-topology) owns the process
roles and the counting scope. Its [README](infra/README.md#pod-inventory) shows
how to count the pods of a running lab.

## 3. Sharding
<sub>[Back to Contents](#contents)</sub>

| Region | Shard | Owns |
|---|---|---|
| US | `shard-a` | US sellers and catalog, US buyers and orders |
| EU | `shard-b` | EU sellers and catalog, EU buyers and orders |
| ASIA | `shard-c` | ASIA sellers and catalog, ASIA buyers and orders |

**Routing.** Product and order route directly with a shared library; there is no
router service. Both hash a canonical seller or buyer ID into the fixed, ordered
shard inventory:

```text
shard(id) = inventory[unsignedBigEndian(SHA-256(UTF-8(id))) mod inventory.size]
```

At creation, the service generates an ID that routes to the requested home region.
The home region never changes. The Snowflake generator identity is independent of
database placement. A change of shard membership or order needs a data migration;
the retained lab refuses it.

**Ownership.** Each record lives on the home shard of its seller or its buyer:

| Data | Home | Key in each API call |
|---|---|---|
| Sellers, products, stock reservations, seller profit | Seller's shard | `sellerId` |
| Buyers, orders, items, saga state, order outbox and inbox | Buyer's shard | `buyerId` |
| Ledger decisions and entries | Separate ledger database | Not sharded |

A product ID or an order ID alone is not a routing key. API requests carry the
seller ID or the buyer ID.

**Cross-shard orders.** Buyers can buy from sellers in all regions. The order stays
on the buyer's shard, and stock and profit work runs on each seller's shard. Each
local transaction is atomic; the saga and its compensation connect them.

```mermaid
flowchart LR
    subgraph Buyer["shard-b · EU · buyer home"]
        O[("Order, items, saga state, outbox")]
    end
    subgraph Seller["shard-a · US · seller home"]
        S[("Product, stock reservation, seller profit")]
    end
    L[("Ledger database · not sharded")]
    O -->|"Saga: reserve or release stock, credit profit"| S
    O -->|"CDC, RabbitMQ, ledger consumer"| L
```

**Replication.** Each shard is its own group of one primary and two standbys.
Writes and default reads go to the primary. The synchronous profile waits for a
durable acknowledgement from one standby: writes continue with one standby absent
and block while both are absent. Replica reads can be stale. Replication copies
data inside a shard; it does not distribute rows between regions.

**Load skew.** Routing is not authorization. If many requests use the same sellers
or buyers, one shard gets more load than the others.

**Failover.** After a promotion, CloudNativePG moves each `-rw` Service to the new
primary. Open connections and transactions can still fail. See
[CloudNativePG architecture](https://cloudnative-pg.io/docs/1.30/architecture/).

See [routing](shardshop-core/sharding/README.md),
[ID generation](shardshop-core/idgen/README.md) and
[database consistency](database/ARCHITECTURE.md#consistency-and-failure-boundaries)
for the contracts. The [failover evidence](CHANGELOG.md#step-62--synchronous-failover-drills), the
[backup and restore evidence](CHANGELOG.md#step-63--coordinated-backup-and-isolated-restore) and the
[audit and repair evidence](CHANGELOG.md#step-64--restore-audit-and-recovery) are in the changelog.

### Implementation boundaries

- Keep an explicit shard handle from shard selection to commit. Do not use
  thread-local routing. Test concurrent requests for routing leakage.
- Before a release that needs a schema change, verify the version of each schema
  owner on every shard.

The [product](shardshop-product/ARCHITECTURE.md#product-api),
[order](shardshop-order/ARCHITECTURE.md#order-creation-and-ledger-saga) and
[infrastructure](infra/ARCHITECTURE.md#6-rabbitmq-and-delivery-reliability) designs
define the HTTP contracts, the validation order, the saga, reconciliation,
quarantine and message delivery. The repository [AGENTS.md](../../AGENTS.md)
defines the code layers.

## 4. Performance
<sub>[Back to Contents](#contents)</sub>

These figures are **deployment evidence, not a hardware-independent maximum**. For the
local deployment, one host ran the complete lab: 12 Docker CPUs, 12.65 GiB Docker
memory and 16 GB physical memory.

**Writes.** The fixed 10/20/30 orders/s sequence passed. Accepted rates were
**10.006 / 19.949 / 29.354 orders/s**, with final rolling write p95
**38.466 / 62.206 / 85.702 ms** and saga p95 **1.533 / 1.788 / 2.228 s**. Concurrent
reads held near 200/s. There were no new errors, cancellations or recoveries, no
backpressure and no stock exhaustion, and bounded backlogs drained. Host paging
was a diagnostic only. See the
[load-test evidence](CHANGELOG.md#step-65--order-write-p95-below-100-ms).

**Reads.** The primary read profile delivered **994.565 / 991.000 / 999.769 reads/s**
in three eligible 30-second windows, all OK, with p95 **3 / 3 / 2 ms**. The
aggregate was **995.111 reads/s** over 89.999985248 s, with writes paused and
drained. The setup was two product pods, 16 reader workers and at most 16 HTTP
connections. Mean connection acquisition was 0.0078 ms, no sampled pool waiter
appeared and the product and reader pods had no additional CPU throttling. See the
[read evidence](CHANGELOG.md#step-67--qualify-1000-product-readss).
HTTP/2 through Envoy gave no gain, thus the lab keeps HTTP/1.1
([decision](DECISIONS.md#keep-http11)).

**Writes on the AWS deployment.** Three `t4g.xlarge` workers, one in each zone, passed
30 orders/s with the `kind-order-capacity` check as a Job in the cluster:
**29.867 orders/s**, final write p95 **86.969 ms**, saga mean **2.582 s**, reads near
200/s, no errors, cancellations or backpressure, and no backlog growth. Cross-zone
round trips set the limits of the sequential saga stages, thus the result and profit
stages work in parallel and the stock lanes do not wait after a full batch
([decision](DECISIONS.md#saga-stages-for-cross-zone-latency)).

Each figure is an operating point of one deployment. They are not maximum capacity,
and they are not a simultaneous 1,000 reads/s and 30 orders/s. Write latency covers allocation through
PUT acceptance. Saga completion is separate.

Different kinds of figures answer different questions:

| Figure | Value | Meaning |
|---|---|---|
| Configured cap | Producer 30 new orders/s, reader 10,000 reads/s | Control limits, not measured capacity |
| Short burst | 829 allocation requests/s on one home shard, for less than one second | Allocation only, not the complete order workflow |
| Qualified operating points | 29.354 orders/s and 995.111 reads/s, measured separately | Pass the latency, error and backlog criteria |
| Saturation attempt | Target 40 orders/s (the producer at 30/s and a temporary producer at 10/s) gave 22.68 new orders/s, and the saga latency increased to approximately 15 s | Backpressure filled the combined 200-order bound; host contention and finite stock limit this result |
| Undersupplied read targets | 1,434/s at target 1,500 and 1,858/s at target 2,000, without errors, but below 95% of the target in some windows | The reader and the host did not supply the target; the read maximum is not established |

- **Write requests.** Each order needs two write API requests: allocation and
  submission. These are HTTP rates, not SQL writes or storage IOPS. Stock, saga,
  ledger and profit work add database writes on buyer and seller shards. The
  standard producer rotates the buyer regions equally, thus a buyer shard gets
  one third of the accepted rate.
- **Cancellations.** Finite stock can run out, and then the saga cancels orders.
  Thus the confirmed rate matters, not only the accepted rate.
- **Read skew.** The catalog has 50 US, 100 EU and 51 ASIA products, thus the read
  rates are unequal between shards.
- **Stock lanes.** A separate disposable PostgreSQL comparison observed
  40.60 -> 57.99 orders/s with one versus three stock lanes (+42.8% throughput,
  30.0% shorter drain). It excludes CDC, ledger, profit and replication. See the
  [evidence](CHANGELOG.md#step-65--order-write-p95-below-100-ms).
- **Profit lanes.** A separate prepared-intent comparison observed
  182.29 -> 299.16 intents/s with one versus three profit lanes (+64.1%
  throughput, 39.1% shorter drain). It uses independent sellers and the lab polling
  profile of 100 ms and 32 intents. See the
  [evidence](CHANGELOG.md#step-65--order-write-p95-below-100-ms). The gains of the
  two stages cannot be added.
- **No sum.** Peak reads and peak writes were measured separately. Do not add them
  as one simultaneous capacity.

The [measurement criteria](shardshop-testing/ARCHITECTURE.md#9-capacity-measurement)
define the protocol. The runtime defaults stay at 0.5 orders/s and 200 product reads/s.

## 5. Local deployment and AWS deployment
<sub>[Back to Contents](#contents)</sub>

ShardShop has two deployments. The local deployment on kind runs the complete
system, and each contract, drill and acceptance scenario must pass there. The AWS
deployment puts the same system on EKS, delivers it with Argo CD and adds cloud
durability. Each AWS run creates the resources, checks them and then destroys them.

### High-level design

Both deployments use one source. Each deployment adds only its environment values
and its tools.

```mermaid
flowchart LR
    subgraph Shared["Shared source in the repository"]
        Inventory["Shard inventory<br/>shards.yaml"]
        Chart["Shared Helm chart<br/>infra/helm/shardshop"]
        Operator["CloudNativePG chart<br/>and its values"]
        Schema["Flyway migrations<br/>database/"]
        Images["Pinned images<br/>PostgreSQL, RabbitMQ, Flyway"]
        Checks["Maven scenarios<br/>shardshop-testing"]
    end
    subgraph Local["Local deployment · kind"]
        KindValues["kind-values.yaml"]
        LocalTools["cluster-create.sh, up.sh<br/>Helmfile"]
    end
    subgraph Cloud["AWS deployment · EKS"]
        EksValues["eks-values.yaml"]
        CloudTools["Terraform, Argo CD<br/>runbook commands"]
    end
    Shared --> Local
    Shared --> Cloud
```

| Topic | Shared | Local deployment (kind) | AWS deployment (EKS) |
|---|---|---|---|
| Cluster | Kubernetes 1.36, namespace `shardshop` with the restricted Pod Security Standard | One control plane and three workers on one computer | Three workers, one in each zone of `eu-west-2` |
| Databases | CloudNativePG 1.30.1, three shards with three instances each, a ledger with one instance | One instance of each shard on each worker | One instance of each shard in each zone |
| Storage | | The `standard` class of kind | The encrypted gp3 class `shardshop-gp3` |
| Schema | The Flyway migrations and the same Flyway build | The image is loaded into kind | The image is copied to ECR |
| Broker | RabbitMQ 4.3.7 and its definitions | One broker | Three brokers with three-member quorum queues |
| Credentials | The same Secret names and keys | `cluster-create.sh` makes random passwords | AWS Secrets Manager, synced by the Secrets Store CSI driver |
| Backups | | Coordinated logical dumps outside kind | WAL archive and base backups in S3, point-in-time restore |
| Applications | The same sources and image recipe | Six applications and three workloads, loaded into kind | The same, copied to ECR with digest pins |
| Delivery | The shared Helm chart | Helmfile with Maven gates | Argo CD from pinned OCI charts in ECR, with the Maven gates as hook Jobs ([runbook](infra/terraform/README.md#deliver-shardshop-with-argo-cd)) |
| Webpages | The five pages and their APIs | `webpages.sh` forwards local ports, without a login | One CloudFront address with a Cognito login and role rules, through Envoy Gateway and an internal NLB ([runbook](infra/terraform/README.md#open-the-webpages)) |
| Checks | The Maven `kind` profile | All gates, drills and acceptance scenarios | The gates, the drills of the eleven acceptance scenarios and the `eks-*` scenarios, as Jobs in the cluster. The backup checks run on the developer computer. EKS variants replace the checks of kind nodes ([list](shardshop-testing/ARCHITECTURE.md#3-aws-deployment-acceptance)) |

The local deployment runs the complete system on one computer:

```mermaid
flowchart TB
    Dev["Developer computer<br/>Docker Desktop"]
    subgraph Kind["kind cluster · one control plane, three workers"]
        CNPG["CloudNativePG operator"]
        subgraph NS["Namespace shardshop"]
            Apps["Six applications<br/>three workloads"]
            DB[("Three shards × three instances<br/>ledger · one instance")]
            MQ["RabbitMQ · one broker"]
        end
    end
    Dumps[("Logical backup bundles<br/>outside kind")]
    Dev -->|"Build and load images<br/>deploy with Helmfile"| Kind
    DB -.->|"Coordinated dumps"| Dumps
```

The AWS deployment runs the same system with cloud services:

```mermaid
flowchart TB
    Dev["Developer computer<br/>Terraform, Helm, Maven"]
    Browser["Browser"]
    subgraph Account["AWS account · eu-west-2"]
        CF["CloudFront and Cognito<br/>login, HTTPS"]
        ECR["ECR<br/>images and charts"]
        S3[("S3 bucket<br/>WAL and base backups")]
        SM["Secrets Manager<br/>runtime secrets"]
        subgraph EKS["EKS cluster · three workers in three zones"]
            Ops["Argo CD, CloudNativePG, Barman Cloud Plugin<br/>cert-manager, Secrets Store CSI driver"]
            Envoy["Envoy Gateway · internal NLB<br/>one Envoy pod in each zone"]
            Apps2["Six applications<br/>three workloads"]
            Gates["Gate and drill Jobs"]
            DB2[("Three shards × three instances<br/>ledger · one instance")]
            MQ2["RabbitMQ · three brokers"]
        end
    end
    Dev -->|"terraform apply, then destroy"| Account
    Dev -->|"Root Application, drill Jobs, backup checks"| EKS
    ECR -->|"Images and charts"| EKS
    SM -->|"Credentials"| EKS
    DB2 -->|"WAL and base backups"| S3
    Browser -->|"HTTPS"| CF
    CF -->|"VPC origin"| Envoy
    Envoy -->|"Five pages and their APIs"| Apps2
```

### Low-level design

#### Delivery

The local deployment keeps its infrastructure and deploys new builds. An AWS run starts
from nothing and ends with nothing.

```mermaid
flowchart TB
    subgraph LocalFlow["Local deployment"]
        direction LR
        L1["cluster-create.sh<br/>kind cluster, credentials"] --> L2["Helmfile infrastructure<br/>operator, databases, broker"]
        L2 --> L3["up.sh<br/>build, scan, load images"]
        L3 --> L4["Helmfile application<br/>migrations, routing, applications"]
        L4 --> L5["Maven gates<br/>before and after each release"]
    end
    subgraph CloudFlow["AWS deployment"]
        direction LR
        A1["terraform apply<br/>state bucket, foundation"] --> A2["Build, scan and push<br/>the images, the gates image<br/>and the charts to ECR"]
        A2 --> A3["Helm<br/>install Argo CD"]
        A3 --> A4["Apply the root Application"]
        A4 --> A5["Argo CD sync waves<br/>with gate Jobs"]
        A5 --> A6["Drill Jobs<br/>and backup checks"]
        A6 --> A7["Remove ShardShop<br/>terraform destroy"]
    end
```

#### Argo CD sync waves on AWS

Each layer is one child Application with a pinned chart. The layers with generated
hook Jobs use the immutable chart version, because Argo CD puts the revision into the
generated Job names. The others use a digest. The root Application starts a wave only
after the earlier waves are synced and healthy. Each sync gives the hook Jobs new
names, and a failed or interrupted Job stays. No Application deletes a database.

```mermaid
flowchart LR
    Charts[("ECR<br/>OCI charts")] -.-> Root["Root Application<br/>delivery chart"]
    Root --> W0["Wave 0<br/>CloudNativePG"]
    W0 --> W1["Wave 1<br/>backup plugin"]
    W1 --> W2["Wave 2<br/>namespace"]
    W2 --> W3["Wave 3<br/>infrastructure<br/>ObjectStore before clusters"]
    W3 --> W4["Wave 4<br/>migration Jobs<br/>PostSync hooks"]
    W4 --> W5["Wave 5<br/>routing ConfigMap"]
    W5 --> W6["Wave 6<br/>applications"]
    W4 -.->|"A failed Job"| Stop["The routing and the applications<br/>stay at their earlier revision"]
```

The Maven gates of Helmfile run as hook Jobs around the migrations and the
applications. A failed PreSync gate stops the sync before Argo CD applies a resource.
Argo CD runs no hook in a selective sync, thus an admission policy denies it.

```mermaid
flowchart LR
    subgraph Migrations["Wave 4 · shardshop-migrations"]
        direction LR
        MPre["PreSync gate<br/>allocator, infrastructure,<br/>reservation, no active migration"] --> MJobs["PostSync<br/>Flyway Jobs"]
    end
    subgraph Applications["Wave 6 · shardshop-applications"]
        direction LR
        APre["PreSync gate<br/>publications, reservation,<br/>no drill"] --> ASync["Sync<br/>six applications"]
        ASync --> Seeder["PostSync<br/>seeder"]
        Seeder --> APost["PostSync gate<br/>pinned images, catalog,<br/>reads, orders"]
    end
    Migrations --> Applications
    Policy["Admission policy"] -.->|"Denies a selective sync<br/>and the apply strategy"| Applications
```

#### Network and placement on AWS

Required zone anti-affinity puts one instance of each shard and one broker in each
zone. An EBS volume stays in its zone, thus each instance stays in its zone. On
kind, the same rule uses the worker name instead of the zone.

```mermaid
flowchart TB
    Endpoint["EKS API endpoint<br/>IAM authentication"]
    subgraph VPC["VPC 10.40.0.0/16"]
        NAT["One shared NAT gateway"]
        S3E["S3 gateway endpoint"]
        subgraph ZA["Zone a"]
            WA["Worker t4g.xlarge<br/>one instance of each shard<br/>one broker<br/>application pods"]
        end
        subgraph ZB["Zone b"]
            WB["Worker t4g.xlarge<br/>one instance of each shard<br/>one broker<br/>application pods"]
        end
        subgraph ZC["Zone c"]
            WC["Worker t4g.xlarge<br/>one instance of each shard<br/>one broker<br/>application pods"]
        end
    end
    Endpoint --> WA & WB & WC
    WA & WB & WC -->|"Public images"| NAT
    WA & WB & WC -->|"Backups, ECR layers"| S3E
```

#### Backups and restore on AWS

Each database pod has a backup sidecar. The pod identity of a cluster can write
only below its own prefix. A restore cluster can only read the prefix of its
source. kind keeps its coordinated logical dumps
([backup policy](database/ARCHITECTURE.md#replication-and-backup-policy)).

```mermaid
flowchart LR
    subgraph Pod["Database pod, for example shard-a-1"]
        PG["PostgreSQL"]
        Side["Barman Cloud sidecar"]
    end
    Schedule["ScheduledBackup<br/>at the start and each day"]
    Bucket[("S3 bucket<br/>shard-a/ shard-b/<br/>shard-c/ ledger-db/")]
    Restore["Restore cluster shard-a-pitr<br/>at a target time"]
    PG -->|"Each WAL segment"| Side
    Schedule -->|"Base backup"| Side
    Side -->|"Role shardshop-backup-shard-a<br/>writes only shard-a/"| Bucket
    Bucket -->|"Role shardshop-restore-shard-a<br/>reads only shard-a/"| Restore
```

#### Runtime secrets

The databases, the Flyway Jobs and the brokers read the same Secret names and keys
on both targets. Only the source of the passwords is different.

```mermaid
flowchart LR
    TF["Terraform<br/>ephemeral passwords"] -->|"Write-only values"| SM["Secrets Manager<br/>shardshop/"]
    SM -->|"Role shardshop-runtime-secrets"| CSI["Secrets Store CSI driver<br/>AWS provider"]
    CSI --> Holder["Holder pod<br/>mounts the secrets"]
    Holder -->|"Sync"| KS["Kubernetes Secrets<br/>eight role Secrets, shardshop-rabbitmq"]
    Script["kind: cluster-create.sh<br/>random passwords"] -.->|"Local deployment"| KS
    KS --> Roles["CloudNativePG login roles"]
    KS --> Jobs["Flyway Jobs"]
    KS --> Brokers["RabbitMQ brokers"]
```

A synced Secret exists only while the holder pod mounts it. The Terraform state
contains no password.

#### Broker

Broker 0 is the seed, and it declares the queues before the other brokers join.
Membership reconciliation then adds a member on each new broker. If one broker
stops, each queue elects a leader on a broker that still runs.

```mermaid
flowchart LR
    subgraph LocalMQ["Local deployment"]
        B0["Broker 0"] --- Q1["Each quorum queue<br/>one member"]
    end
    subgraph CloudMQ["AWS deployment · one broker in each zone"]
        C0["Broker 0 · seed"]
        C1["Broker 1"] -->|"Joins"| C0
        C2["Broker 2"] -->|"Joins"| C0
        C0 & C1 & C2 --- Q3["Each quorum queue<br/>three members, one leader"]
    end
```
---
## 6. Detailed design documents
<sub>[Back to Contents](#contents)</sub>

Each topic has one owning document. Runbooks keep the commands, and the
[changelog](CHANGELOG.md) keeps the dated evidence.

| Area | Document | Owns |
|---|---|---|
| Services | [Product](shardshop-product/ARCHITECTURE.md) | Catalog API, creation keys, currency policy, read profiles and stock storage |
| | [Order](shardshop-order/ARCHITECTURE.md) | Order acceptance, saga, stock reservation, CDC relay, reconciliation and restore requirements |
| | [Ledger](shardshop-ledger/ARCHITECTURE.md) | Permanent decisions, replay identities and result publication |
| Libraries | [Core](shardshop-core/README.md) | Module boundaries, dependency rules and wire contracts |
| | [Common](shardshop-core/common/README.md) | Shared validation, JSON, JDBC and RabbitMQ utilities |
| | [ID generation](shardshop-core/idgen/README.md) | Snowflake IDs and generator allocation |
| | [Shard routing](shardshop-core/sharding/README.md) | Routing rule, shard topology and data placement |
| | [Workload control](shardshop-core/workload-control/README.md) | Live rate, pause and status API of the workloads |
| Workloads | [Workloads](shardshop-workload/README.md) | Workload lifecycle and catalog gate |
| | [Datasets](shardshop-workload/shardshop-datasets/README.md) | ID-free seller, product and buyer definitions |
| | [Product seeder](shardshop-workload/shardshop-product-seeder/README.md) | Finite, idempotent catalog seeding |
| | [Product reader](shardshop-workload/shardshop-product-reader/README.md) | Read load and HTTP connection policy |
| | [Order producer](shardshop-workload/shardshop-order-producer/README.md) | Deterministic regional order stream |
| Platform | [Database](database/ARCHITECTURE.md) | Schemas, roles, migrations, replication and retention |
| | [Infrastructure](infra/ARCHITECTURE.md) | Lab lifecycle, deployment, pod topology and RabbitMQ delivery |
| | [AWS foundation](infra/terraform/README.md) | Terraform modules, EKS runbook, durability, permissions and costs |
| Verification | [Testing](shardshop-testing/ARCHITECTURE.md) | Acceptance scenarios 1–11 and capacity measurement |
