# shardshop-workload-control

[ShardShop](../../README.md) › [Core](../README.md) › **Workload control** · [Control contract](src/main/resources/contracts/openapi.yaml)

`shardshop-workload-control` lets an operator change the traffic of each workload while it runs. The order
producer, the product reader and the interactive product seeder each serve a control page and a status API from
this library. A change takes effect without a rebuild, a redeploy or a restart, and it affects only the
selected workload.

## Design

- **Contains:** the pacer, the control state, the REST resource, the error mappers and one page script and
  stylesheet.
- **Used by:** the order producer, the product reader and the product seeder. Each workload has its own page markup.
- **Access:** inside the cluster and through `webpages.sh`, without authentication.

### Pages and API

Each workload serves a dedicated webpage and a workload-owned control and status API on port 8080 with Quarkus REST.
The REST root names the workload, so a later gateway can route each API separately.

| Workload | Page | Local URL | REST root |
|---|---|---|---|
| Order producer | Workload Order Producer, `/workload-order-producer/` | <http://127.0.0.1:18082/workload-order-producer/> | `/api/v1/order-producer` |
| Product reader | Workload Product Reader, `/workload-product-reader/` | <http://127.0.0.1:18083/workload-product-reader/> | `/api/v1/product-reader` |
| Product seeder | Workload Product Seeder, `/workload-product-seeder/` | <http://127.0.0.1:18084/workload-product-seeder/> | `/api/v1/product-seeder` |

The [control contract](src/main/resources/contracts/openapi.yaml) defines `GET /control`, `PUT /control/rate`,
`POST /control/pause`, `POST /control/resume` and, for the seeder only, `POST /control/runs`. The pages start
with their workloads when `up.sh` deploys the lab. Open them with `webpages.sh`, as the
[lab start](../../README.md#3-open-the-webpages) shows.

### Rates

| Workload | Unit of the rate | Default | Maximum | Operations in flight |
|---|---|---:|---:|---|
| Order producer | New order starts per second | 0.5 | 30 | Four submissions and two independent status polls by default |
| Product reader | Product read starts per second across all workers | 200 | 10,000 | One read per worker |
| Product seeder | Outgoing HTTP requests per second, including creation, verification and retries | 100 | 1,000 | One request |

- The server validates a rate as a finite JSON number from zero to the maximum with at most three decimal
  places. An invalid update returns `400` and changes nothing.
- Each accepted update is atomic and increments a revision. Zero stops new work like Pause. Pause keeps the
  target for Resume.
- One schedule spaces the starts of a process. A change wakes the waiting callers at once, and the next start
  comes one new interval after the last start, or at once. Missed starts are never sent later, so a rate change
  or a Resume causes no burst.
- The setting lives only in the process. A browser refresh keeps it, and a process restart (for example each
  `up.sh` run) restores the configured defaults.

### Pause and invariants

Pause drains the bounded operations in flight. Reads and seeder requests keep their HTTP deadlines, bounded
attempts and backoff. The producer drains the current submissions and allocates no new ordinal, and its
observer continues to terminal status, also while paused. `PAUSED` means that no write is in flight, and the
backlog can stay nonzero.

The producer replays ordinal zero after a restart through the same paced, bounded submission window. Pause and
a zero rate also hold the replay. The workload counts idempotent PUT replays separately, including pending
recoveries. The order HTTP API emits `Idempotent-Replay: true|false`. During an HTTP rolling upgrade, the
absence of the header retries the same PUT under its ordinary attempt and deadline budget. A malformed header
fails validation. The write path sends no extra status probe.

The control changes no dataset version, run name, ordinal progress, service-issued ID, retry key or currency
selection. The workloads keep their Java-owned catalog gates and lifecycle, and they have no database
credentials or Kubernetes API token.

### Producer limits

The cap of 30 starts per second follows the lab measurements in the [changelog](../../CHANGELOG.md#step-414).
At that rate the producer reached 29.45 new orders per second with bounded order, outbox and profit backlogs and
a final rolling write p95 of 93.73 ms. At target 10/s the write p95 was 91.42 ms. Four submission slots were
sufficient. The maximum of 12 matches the aggregate pool budget of one HTTP JVM (four connections per shard).
A request can wait up to one second for a pool connection before it gets `503`, so HTTP concurrency can exceed
12 during short bursts. Retained finite stock and host contention limit these measurements, so the default
stays 0.5/s.

### Seeder modes

The seeder has two modes of one application and one image:

| Mode | Runs as | Behavior |
|---|---|---|
| Bootstrap | The default finite Job of each release | Seeds once, serves no HTTP and keeps the deployment completion gate |
| Interactive | The `shardshop-product-seeder-control` Deployment | Serves its page while it is idle or completed. Starts one operator-requested finite run at a time (`409 RUN_ACTIVE` otherwise) |

A run sends the same creation keys and payloads as the Job, so it recovers the same IDs without duplicates and
changes no stock or earnings. Each HTTP attempt keeps its 5-second timeout and retry budget. The run has no
deadline of its own. A paused run waits until Resume, and a pod restart stops a run in progress. Start it again
after the restart.

### Security

The control API has no authentication. It is reachable only inside the cluster and through `webpages.sh`.
Milestone 7 plans authenticated routes for the AWS deployment.

The [workload lifecycle](../../shardshop-workload/README.md#workload-lifecycle) describes the catalog gates.

## Operation

### Monitoring

`GET <root>/control` returns the status as JSON and sends no backend request. The status updates every 2 seconds.
It shows the state, the effective target, the achieved rate of the last 10 seconds, the operations in flight,
and the backend HTTP requests and errors since process start. A table shows the requests, retries and errors of
each purpose. If the work in flight cannot reach the target (concurrency divided by the mean operation time of
the last 10 seconds is below the target), a notice shows the reachable rate. The page, the script, the
stylesheet and the API responses use `Cache-Control: no-store`.

The producer also shows its non-terminal backlog, backpressure, recovered orders and write and saga latency.
`inFlight` counts allocation and PUT submissions only. The nullable `producer` status object contains
`submissionConcurrency`, `observerConcurrency`, `maxNonTerminalOrders`, `nonTerminalBacklog`, `backpressured`,
`recoveredOrders`, `confirmedOrders`, `cancelledOrders`, `writeLatency` and `sagaLatency`. Writes reserve
capacity before allocation, so submissions plus accepted pending orders stay within the backlog bound. An unused
reservation that waits on the pacer is not counted as backpressure. Slow status requests occupy only their
bounded observer slots, and other ready observations and submissions continue.

Both latency objects contain `samples` (lifetime count), `meanMillis` and `p95Millis` (nearest-rank p95 over the
most recent 1,024 new orders). Write latency measures the time from allocation start to PUT acceptance,
including retries. Saga latency measures the time from allocation start to the first observed terminal status,
including polling. Both exclude pacing and backpressure waits and recovered orders. An idempotent retry after a
lost first PUT response is also a replay, because its original acceptance time is not known from the reply.

| State | Meaning |
|---|---|
| `WAITING` | The reader or the producer waits for the complete catalog, or the producer creates buyers |
| `IDLE` | The interactive seeder has no run yet |
| `RUNNING` | Paced work runs at the effective target |
| `PAUSING` | The effective target is zero and operations in flight finish |
| `PAUSED` | The effective target is zero and no operation is in flight |
| `COMPLETED` | The last seed run succeeded |
| `FAILED` | The workload or the last seed run failed |

The producer rate counts only new orders that order accepts as new. The traffic table shows its startup
requests (catalog lookups and buyer creation), allocations, submissions and status polling as different rows.
The reader counts one request for each read and one retry for each repeated attempt after a lost connection.
The seeder counts every outgoing request, including creation, verification and repeated attempts.

### Controls

- **Target rate:** Type a number, or use **−** and **+** to change the field by one step. **Apply** sends the
  value. The page checks the value first, and the workload checks it again and rejects an invalid value without
  a change.
- **Pause** stops new work after the operations in flight. **Resume** continues at the target rate. Pause keeps
  the target rate.
- **Start seed run** (seeder page only) starts one finite seed run. A second start while a run is in progress
  gets the message that a run is active.

| Workload | Unit | Default | Maximum | Step | In flight |
|---|---|---:|---:|---:|---|
| Order producer | new order starts/s | 0.5 | 30 | 0.1 | 4 submissions (configurable) |
| Product reader | product read starts/s, all workers | 200 | 10,000 | 10 | 1 read per worker (16) |
| Product seeder | outgoing HTTP requests/s | 100 | 1,000 | 10 | 1 request |

### Control API

| Request | Result |
|---|---|
| `GET <root>/control` | `200` with the status. Sends no backend request |
| `PUT <root>/control/rate` with `{"rate": 2.5}` | `200` with the new status. `400 INVALID_RATE` or `400 INVALID_REQUEST` keeps the previous setting |
| `POST <root>/control/pause` | `200`. The state is `PAUSING` until the operations in flight end |
| `POST <root>/control/resume` | `200`. Work continues at the target rate |
| `POST /api/v1/product-seeder/control/runs` | `202` with the run. `409 RUN_ACTIVE` while a run is in progress |

The body of a rate update is one JSON object with one number. Strings, unknown or repeated members, more than
three decimal places and values outside the bounds are rejected. A body has at most 1 KiB. Concurrent updates
apply one at a time. With the tunnels open, for example:

```bash
curl -s -X PUT -H 'Content-Type: application/json' -d '{"rate": 400}' \
  http://127.0.0.1:18083/api/v1/product-reader/control/rate
```

### Configuration

| Setting | Default | Meaning |
|---|---|---|
| `shardshop.reader.request-rate` | `200` | Initial reader rate, 0-10000. The Helm schema allows whole numbers 1-10000 for the kind workload gate |
| `shardshop.producer.order-rate` | `0.5` | Initial new order starts per second, 0-30 |
| `shardshop.producer.submission-concurrency` | `4` | Concurrent allocation and PUT submissions, 1-12 |
| `shardshop.producer.observer-concurrency` | `2` | Concurrent status reads, 1-12, independent of writes |
| `shardshop.producer.max-non-terminal-orders` | `100` | Maximum writes plus non-terminal orders, submission concurrency to 10000 |
| `shardshop.seeder.request-rate` | `100` | Initial seeder requests per second, 0-1000. Positive for the bootstrap Job |
| `shardshop.seeder.interactive` | `false` | `true` serves the control page and starts runs on request |

Set the initial rates in the Helm values with `SHARDSHOP_READER_REQUEST_RATE`, `SHARDSHOP_PRODUCER_ORDER_RATE`
or `SHARDSHOP_SEEDER_REQUEST_RATE` under `productReader.configuration`, `orderProducer.configuration` or
`productSeeder.configuration`. The chart sets `SHARDSHOP_SEEDER_INTERACTIVE=true` for the interactive seeder only.

### Tests

Unit tests of the pacer, the control state, the request parsing and the contract:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-core/workload-control -am test
```

The page script runs against the shipped markup of the three pages with Node (`WorkloadControlScriptTest`,
skipped when Node is not installed). The packaged `WorkloadControl.feature` starts the reader, the producer and
the interactive seeder against product, order and PostgreSQL. It measures the backend traffic after live
increases and decreases, zero, Pause and Resume, invalid and concurrent updates, restart defaults and repeated
seed runs:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@workload-control'
```
