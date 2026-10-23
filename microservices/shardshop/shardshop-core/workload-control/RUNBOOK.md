# Workload control runbook

[ShardShop](../../README.md) › [Core](../README.md) › [Workload control](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Controls

- **Target rate:** Type a number, or use **−** and **+** to change the field by
  one step. **Apply** sends the value. The page checks the value first; the
  workload checks it again and rejects an invalid value without a change.
- **Pause** stops new work after the operations in flight. **Resume** continues
  at the target rate. Pause keeps the target rate.
- **Start seed run** (seeder page only) starts one finite seed run. A second
  start while a run is in progress gets the message that a run is active.

| Workload | Page | Unit | Default | Maximum | Step | In flight |
|---|---|---|---:|---:|---:|---|
| Workload Order Producer | `/workload-order-producer/` | new order starts/s | 0.5 | 30 | 0.1 | 4 submissions (configurable) |
| Workload Product Reader | `/workload-product-reader/` | product read starts/s, all workers | 200 | 10,000 | 10 | 1 read per worker (16) |
| Workload Product Seeder | `/workload-product-seeder/` | outgoing HTTP requests/s | 100 | 1,000 | 10 | 1 request |

Rates have at most three decimal places. Zero stops new work like Pause.

## Control API

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
through the local tunnels. Step 7.5 plans authentication for the AWS deployment.

## Pacing and lifecycle

One schedule spaces all starts of a workload at the current rate. After a stall,
the workload does not send the missed starts later. A rate change, Pause and
Resume apply at once, also to callers that already wait: the next start comes one
new interval after the last start, or at once if that time is past. Thus a change
or a Resume causes no burst.

Pause holds new work only. A read, a seeder request or a producer order in flight
finishes within its HTTP deadline and retry budget. The producer drains allocation + PUT submissions, while a separate bounded
observer continues reading accepted non-terminal orders. `PAUSED` means no write
is in flight; its backlog can remain nonzero. Restart replays ordinal zero through
the same paced, bounded submission window. Pause and a zero rate also hold replay.
The workload counts idempotent PUT replays separately, including pending recoveries.
The order HTTP API emits `Idempotent-Replay: true|false`; absence during an HTTP
rolling upgrade retries the same PUT under its ordinary attempt/deadline budget.
A malformed header fails validation. No extra status probe is sent on the write path.

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

## Configuration

| Setting | Default | Meaning |
|---|---|---|
| `shardshop.reader.request-rate` | `200` | Initial reader rate, 0–10000; the Helm schema allows whole numbers 1–10000 for the kind workload gate |
| `shardshop.producer.order-rate` | `0.5` | Initial new order starts per second, 0–30 |
| `shardshop.producer.submission-concurrency` | `4` | Concurrent allocation + PUT submissions, 1–12 |
| `shardshop.producer.observer-concurrency` | `2` | Concurrent status reads, 1–12, independent of writes |
| `shardshop.producer.max-non-terminal-orders` | `100` | Maximum writes plus non-terminal orders, submission concurrency–10000 |
| `shardshop.seeder.request-rate` | `100` | Initial seeder requests per second, 0–1000; positive for the bootstrap Job |
| `shardshop.seeder.interactive` | `false` | `true` serves the control page and starts runs on request |

Set the initial rates in the Helm values with `SHARDSHOP_READER_REQUEST_RATE`,
`SHARDSHOP_PRODUCER_ORDER_RATE` or `SHARDSHOP_SEEDER_REQUEST_RATE` under
`productReader.configuration`, `orderProducer.configuration` or
`productSeeder.configuration`. The chart sets `SHARDSHOP_SEEDER_INTERACTIVE=true`
for the interactive seeder only.

## Tests

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
