# ShardShop Workload Control

[Back to ShardShop](../../README.md) ·
[Architecture](../../ARCHITECTURE.md#workload-traffic-control-step-413) ·
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) ·
[Product service](../../shardshop-product/README.md) ·
[Order service](../../shardshop-order/README.md) ·
[Implementation plan](../../PLAN.md) · [Changelog](../../CHANGELOG.md)

`shardshop-workload-control` gives the three workload applications a live traffic
control. The order producer, the product reader and the interactive product seeder
each serve a dedicated webpage and a control API. A change applies only to the
workload that receives it, and it applies without a rebuild, a redeployment or a
restart.

| Task | Component |
|---|---|
| Pace the starts of one workload at a live target rate; pause and resume | **Workload control** |
| Count backend HTTP requests, retries and errors by purpose | **Workload control** |
| Serve the control API and the shared page script and stylesheet | **Workload control** |
| Serve the dedicated page and define one start | Each workload |
| Read products, produce orders and seed the catalog | Reader, producer, seeder |
| Deploy the pages and their Services | Application release (`up.sh`) |
| Open local access to all webpages | `webpages.sh` |

Only the workload applications use this library. A Maven Enforcer rule stops other
modules from a dependency on it.

**Working directory:** Run all shell commands from the repository root.

## Contents

1. [Workload control webpages](#1-workload-control-webpages)
2. [Control API](#2-control-api)
3. [Pacing and lifecycle](#3-pacing-and-lifecycle)
4. [Configuration](#4-configuration)
5. [Tests](#5-tests)

## 1. Workload control webpages

<sub>[Back to contents](#contents)</sub>

### Open the webpages

**Prerequisite:** Create the lab with `cluster-create.sh`. See the
[cluster lifecycle](../../README.md#local-kubernetes-cluster-operator-and-databases-steps-1121).

1. Deploy the lab:

   ```bash
   bash microservices/shardshop/scripts/up.sh
   ```

2. Open access to the webpages:

   ```bash
   bash microservices/shardshop/scripts/webpages.sh
   ```

   Wait for `webpages.sh` to print all five URLs. It starts and supervises one
   localhost tunnel for each page:

   | Page | Local port | Service port |
   |---|---|---|
   | [Products](http://127.0.0.1:18080/products/) | 18080 | product 8080 |
   | [Orders](http://127.0.0.1:18081/orders/) | 18081 | order 8080 |
   | [Workload Order Producer](http://127.0.0.1:18082/workload-order-producer/) | 18082 | order-producer 8080 |
   | [Workload Product Reader](http://127.0.0.1:18083/workload-product-reader/) | 18083 | product-reader 8080 |
   | [Workload Product Seeder](http://127.0.0.1:18084/workload-product-seeder/) | 18084 | product-seeder-control 8080 |

3. Keep the terminal open while you use the webpages. Ctrl+C stops all tunnels
   of this invocation; the workloads continue at their current settings.

`webpages.sh` takes no arguments. It stops existing listeners on ports
18080–18084 before it opens the tunnels, and it does not rebuild or restart
applications. If one tunnel stops, the script stops the other tunnels and keeps
the lab running.

### Controls

- **Target rate:** Type a number, or use **−** and **+** to change the field by
  one step. **Apply** sends the value. The page checks the value first; the
  workload checks it again and rejects an invalid value without a change.
- **Pause** stops new work after the operations in flight. **Resume** continues
  at the target rate. Pause keeps the target rate.
- **Start seed run** (seeder page only) starts one finite seed run. A second
  start while a run is in progress gets the message that a run is active.

The status updates every 2 seconds. It shows the state, the effective target,
the achieved rate of the last 10 seconds, the operations in flight, and the backend
HTTP requests and errors since process start. A table shows the requests, retries
and errors of each purpose. If the work in flight cannot reach the target, a
notice shows the reachable rate. The page, the script, the stylesheet and the API
responses use `Cache-Control: no-store`.

| Workload | Page | Unit | Default | Maximum | Step | In flight |
|---|---|---|---:|---:|---:|---|
| Workload Order Producer | `/workload-order-producer/` | new order starts/s | 0.5 | 100 | 0.1 | 1 order |
| Workload Product Reader | `/workload-product-reader/` | product read starts/s, all workers | 200 | 10,000 | 10 | 1 read per worker (16) |
| Workload Product Seeder | `/workload-product-seeder/` | outgoing HTTP requests/s | 100 | 1,000 | 10 | 1 request |

Rates have at most three decimal places. Zero stops new work like Pause.

| State | Meaning |
|---|---|
| `WAITING` | The reader or the producer waits for the complete catalog, or the producer creates buyers |
| `IDLE` | The interactive seeder has no run yet |
| `RUNNING` | Paced work runs at the effective target |
| `PAUSING` | The effective target is zero; operations in flight finish |
| `PAUSED` | The effective target is zero; no operation is in flight |
| `COMPLETED` | The last seed run succeeded |
| `FAILED` | The workload or the last seed run failed |

The producer rate counts new orders only. The traffic table shows its startup
requests (catalog lookups and buyer creation), allocations, submissions and status
polling as different rows. The reader counts one request for each read and one
retry for each repeated attempt after a lost connection. The seeder counts every
outgoing request, including creation, verification and repeated attempts.

## 2. Control API

<sub>[Back to contents](#contents)</sub>

Each workload serves the [control contract](src/main/resources/contracts/openapi.yaml)
under its own root: `/api/v1/order-producer`, `/api/v1/product-reader` or
`/api/v1/product-seeder`.

| Request | Result |
|---|---|
| `GET <root>/control` | `200` with the status; it sends no backend request |
| `PUT <root>/control/rate` with `{"rate": 2.5}` | `200` with the new status; `400 INVALID_RATE` or `400 INVALID_REQUEST` keeps the previous setting |
| `POST <root>/control/pause` | `200`; the state is `PAUSING` until the operations in flight end |
| `POST <root>/control/resume` | `200`; work continues at the target rate |
| `POST /api/v1/product-seeder/control/runs` | `202` with the run; `409 RUN_ACTIVE` while a run is in progress |

The body of a rate update is one JSON object with one number. Strings, unknown or
repeated members, more than three decimal places and values outside the bounds
are rejected. Bodies are limited to 1 KiB. Each accepted update increments
`revision`; concurrent updates apply one at a time.

With the tunnels open, for example:

```bash
curl -s -X PUT -H 'Content-Type: application/json' -d '{"rate": 400}' \
  http://127.0.0.1:18083/api/v1/product-reader/control/rate
```

The API has no authentication. It is reachable only inside the cluster and
through the local tunnels. Step 7.5 adds authentication for the AWS deployment.

## 3. Pacing and lifecycle

<sub>[Back to contents](#contents)</sub>

One schedule spaces all starts of a workload at the current rate. After a stall,
the workload does not send the missed starts later. A rate change, Pause and
Resume apply at once, also to callers that already wait: the next start comes one
new interval after the last start, or at once if that time is past. Thus a change
or a Resume causes no burst.

Pause holds new work only. A read, a seeder request or a producer order in flight
finishes within its HTTP deadline and retry budget. The producer observes its
current order until the order is terminal; then it allocates no new ordinal. The
producer replays earlier ordinals after a restart without pacing, but Pause also
holds the replay.

A rate change does not change the dataset versions, the run name, the ordinal
progress, the service-issued IDs, the retry keys or the currency selection. The
catalog start gates stay in the Java workloads, and the workloads keep no database
credentials and no Kubernetes API token.

The setting lives in the process. A browser refresh or closure keeps it. A process
restart, for example each `up.sh` run, restores the configured default rate,
unpaused.

**Seeder modes:** The bootstrap seeder Job of each release is unchanged: it seeds
once, serves no HTTP and gates the deployment. The
`shardshop-product-seeder-control` Deployment runs the same image in the
interactive mode. It serves its page while it is idle or completed, and it starts
a finite run only on operator request. A run sends the same creation keys and
payloads as the Job, thus a rerun recovers the same IDs and changes no stock or
earnings. Each HTTP attempt keeps its 5-second timeout and its retry budget. The
run itself has no deadline: a paused run waits until Resume. A pod restart stops
a run in progress; start it again after the restart.

## 4. Configuration

<sub>[Back to contents](#contents)</sub>

| Setting | Default | Meaning |
|---|---|---|
| `shardshop.reader.request-rate` | `200` | Initial reader rate, 0–10000; the Helm schema allows whole numbers 1–10000 for the kind workload gate |
| `shardshop.producer.order-rate` | `0.5` | Initial new order starts per second, 0–100 |
| `shardshop.seeder.request-rate` | `100` | Initial seeder requests per second, 0–1000; positive for the bootstrap Job |
| `shardshop.seeder.interactive` | `false` | `true` serves the control page and starts runs on request |

Set the initial rates in the Helm values with `SHARDSHOP_READER_REQUEST_RATE`,
`SHARDSHOP_PRODUCER_ORDER_RATE` or `SHARDSHOP_SEEDER_REQUEST_RATE` under
`productReader.configuration`, `orderProducer.configuration` or
`productSeeder.configuration`. The chart sets `SHARDSHOP_SEEDER_INTERACTIVE=true`
for the interactive seeder only.

## 5. Tests

<sub>[Back to contents](#contents)</sub>

Unit tests of the pacer, the control state, the request parsing and the contract:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/workload-control -am test
```

The page script runs against the shipped markup of the three pages with Node
(`WorkloadControlScriptTest`, skipped when Node is not installed). The packaged
`WorkloadControl.feature` starts the reader, the producer and the interactive
seeder against product, order and PostgreSQL. It measures the backend traffic
after live increases and decreases, zero, Pause and Resume, invalid and concurrent
updates, restart defaults and repeated seed runs:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@workload-control'
```
