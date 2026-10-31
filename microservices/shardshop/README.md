# ShardShop

[Architecture](ARCHITECTURE.md) · [Runbook](RUNBOOK.md) · [Implementation plan](PLAN.md) ·
[Version policy](VERSIONS.md) · [Changelog](CHANGELOG.md)

ShardShop is a local system-design lab: a regional product catalog, sharded orders
and an asynchronous ledger on one kind cluster. This page tells you how to start
the lab and which monitors it has. The [runbook](RUNBOOK.md) and the runbooks of
the modules contain all other procedures: configuration, tests, stop, recovery.

## Start the lab

### Prerequisites

- **Tools on `PATH`:** `docker`, `kind`, `kubectl`, `helm`, `helmfile`,
  `skaffold`, `openssl` and `jq`; `webpages.sh` also needs `lsof`. The project
  never downloads or pins tool binaries. It was tested with Docker Desktop 4.97.0,
  kind 0.33.0, kubectl 1.37 (one minor version newer than the Kubernetes 1.36
  cluster, as the skew policy permits), Helm 4.3.1, Helmfile 1.8.2, Skaffold
  2.25.0 and jq 1.7.1; [VERSIONS.md](VERSIONS.md) records their qualification. The lifecycle
  commands need no Python runtime.
- **Docker memory:** give Docker Desktop's VM at least 12 GB of memory
  (Settings → Resources). Both startup scripts stop early if it has less.
- **Image tools:** Docker's Buildx plugin and Docker Scout, with Docker Hub signed
  in through Docker Desktop or `docker login`. `up.sh` builds and scans the images
  before it changes the cluster, thus it needs network access.
- **Java and Maven:** `JAVA_HOME` set to a maintained JDK 25 or newer patch, and Maven 3.9.16 or
  newer on `PATH` ([details](RUNBOOK.md#java-and-maven)).
- **Browser checks:** Node.js on `PATH` is required by the Maven verification
  module, including builds run by the lifecycle scripts. Missing Node fails the
  build. The acceptance run uses Node **24.22.2 LTS**. For a separate Homebrew keg, prepend
  `/opt/homebrew/opt/node@24/bin` to `PATH` before running Maven or the lifecycle
  scripts. See the
  [tool qualification and update requirement](VERSIONS.md#nodejs-browser-test-tool).

Run all commands from the repository root.

### 1. Create the cluster

Do this once:

```bash
bash microservices/shardshop/scripts/cluster-create.sh
```

`cluster-create.sh` creates the four kind nodes and prepares the namespace,
credentials, CloudNativePG, the databases, RabbitMQ and the generator registry. It
does not build or deploy applications. On an existing cluster it resumes stopped
nodes and reconciles supported changes; it never resets retained data. The
[infrastructure guide](infra/README.md) lists what it creates.

### 2. Deploy the applications

```bash
bash microservices/shardshop/scripts/up.sh
```

`up.sh` builds and tests the current sources, scans and loads the images, migrates
the databases, deploys the six applications and runs the acceptance gates. Then it
exits. Each run replaces the application pods, also when nothing changed, and each
new product or order JVM uses a new generator ID. `up.sh` also resumes a lab that
`down.sh` stopped.

### 3. Open the webpages

```bash
bash microservices/shardshop/scripts/webpages.sh
```

Wait until `webpages.sh` prints all five URLs, then keep its terminal open:

| Page | Local URL | What it shows |
|---|---|---|
| Products | <http://127.0.0.1:18080/products/> | Products of one seller region, with stock, price and confirmed earnings |
| Orders | <http://127.0.0.1:18081/orders/> | Orders of one buyer region, with status, total and items |
| Workload Order Producer | <http://127.0.0.1:18082/workload-order-producer/> | Producer state, rate, backlog, backpressure and latency |
| Workload Product Reader | <http://127.0.0.1:18083/workload-product-reader/> | Reader state, rate and backend requests |
| Workload Product Seeder | <http://127.0.0.1:18084/workload-product-seeder/> | Interactive seeder state, rate and seed runs |

- `webpages.sh` takes no arguments. It stops existing listeners on ports
  18080–18084 (TERM, then KILL after five seconds) before it opens one tunnel for
  each page. All tunnels bind only to `127.0.0.1`.
- It holds no deployment lock, thus `down.sh` or a new deployment can run in a
  second terminal.
- Ctrl+C closes the tunnels. The cluster and the workloads continue to run.
- If one tunnel exits, `webpages.sh` reports it and closes the other tunnels. Run
  the same command again to reopen access; it does not rebuild or restart
  applications.

To stop the lab and keep its data, run `down.sh`; the
[infrastructure runbook](infra/RUNBOOK.md#stop-and-resume-the-cluster) describes
stop, destruction and recovery.

### Start one application outside Kubernetes

Each module guide tells how to start that application by itself:
[product](shardshop-product/README.md), [order](shardshop-order/README.md),
[ledger](shardshop-ledger/README.md), [seeder](shardshop-workload/shardshop-product-seeder/README.md),
[reader](shardshop-workload/shardshop-product-reader/README.md) and
[producer](shardshop-workload/shardshop-order-producer/README.md).
[Build and run](RUNBOOK.md#build-and-run) shows the build and the packaged JARs.

## Monitoring

| Monitor | How to open it | Details |
|---|---|---|
| Product and order pages | `webpages.sh`, ports 18080 and 18081 | [Product](shardshop-product/README.md#monitoring), [order](shardshop-order/README.md#monitoring) |
| Workload control pages | `webpages.sh`, ports 18082–18084 | [Workload control](shardshop-core/workload-control/README.md#monitoring) |
| Workload status API | `GET /api/v1/<workload>/control` through the same tunnels | [Workload control](shardshop-core/workload-control/README.md#monitoring) |
| Product HTTP counters | Prometheus endpoint `/q/metrics` of each product pod | [Product](shardshop-product/README.md#metrics) |
| Order write latency | `GET /internal/order/write-metrics` of each order HTTP pod | [Order](shardshop-order/README.md#write-metrics) |
| Saga recovery and database pressure | `/q/metrics` on order and ledger; native broker/database observations | [Recovery metrics](infra/README.md#saga-and-database-pressure) |
| Application logs | `kubectl logs` of each Deployment or Job | Each module guide lists its log lines |
| RabbitMQ management | Port 15672 of Service `shardshop-rabbitmq` | [Infrastructure](infra/README.md#rabbitmq-management) |
| Pods, databases and migrations | `kubectl` and `helm` | [Infrastructure](infra/README.md#monitoring), [database](database/README.md#monitoring) |
| Test results | Maven report folders | [Verification](shardshop-testing/README.md#results) |

All commands use the `kind-shardshop` context. Each module guide describes its own
monitors in detail.

## Layout

Each module has an `ARCHITECTURE.md` for its design, a `RUNBOOK.md` for its
procedures and a README for its start and monitoring entry points.

- `shardshop-core/`: shared libraries (`common`, `idgen`, `sharding`, `workload-control`).
- `shardshop-workload/`: datasets, product seeder, product reader and order producer.
- `shardshop-product/`, `shardshop-order/`, `shardshop-ledger/`: the services. Order is one
  image with separate HTTP and saga Deployments.
- `shardshop-testing/`: Cucumber scenarios, with a Testcontainers profile and a live kind profile.
- `database/`: Flyway streams `shard/catalog`, `shard/ordering` and `ledger/ledger`.
- `infra/`: `kind.yaml`, `shards.yaml`, `helmfile.yaml.gotmpl`, the Helm charts and the
  image recipes. The optional AWS paths (`eks-values.yaml`, `terraform/`) belong to milestone 7.
- `scripts/`: `cluster-create.sh`, `up.sh`, `down.sh`, `cluster-destroy.sh` and `webpages.sh`.

Build only ShardShop or the affected module. Never run the root Maven build of the
repository for this project. The disruptive kind drills are opt-in. `up.sh` never runs them.
