# ShardShop workload control

[ShardShop](../../README.md) › [Core](../README.md) › **Workload control** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md) · [Control contract](src/main/resources/contracts/openapi.yaml)

The order producer, the product reader and the interactive product seeder each
serve a control page and a status API from this library.

## Start

The pages start with their workloads when `up.sh` deploys the lab. Open them with
`webpages.sh`, as the [lab start](../../README.md#3-open-the-webpages) shows:

| Page | Local URL | REST root |
|---|---|---|
| Workload Order Producer | <http://127.0.0.1:18082/workload-order-producer/> | `/api/v1/order-producer` |
| Workload Product Reader | <http://127.0.0.1:18083/workload-product-reader/> | `/api/v1/product-reader` |
| Workload Product Seeder | <http://127.0.0.1:18084/workload-product-seeder/> | `/api/v1/product-seeder` |

The pages also change the rate and pause or resume the workload; the
[runbook](RUNBOOK.md#controls) describes the controls and the control API.

## Monitoring

`GET <root>/control` returns the status below as JSON; it sends no backend request.

The status updates every 2 seconds. It shows the state, the effective target,
the achieved rate of the last 10 seconds, the operations in flight, and the backend
HTTP requests and errors since process start. A table shows the requests, retries
and errors of each purpose. If the work in flight cannot reach the target, a
notice shows the reachable rate. The page, the script, the stylesheet and the API
responses use `Cache-Control: no-store`.

The producer additionally shows its non-terminal backlog, backpressure, recovered
orders and write/saga latency. `inFlight` counts allocation + PUT submissions only.
The nullable `producer` status object contains `submissionConcurrency`,
`observerConcurrency`, `maxNonTerminalOrders`, `nonTerminalBacklog`, `backpressured`,
`recoveredOrders`, `confirmedOrders`, `cancelledOrders`, `writeLatency` and `sagaLatency`.
Writes reserve capacity before allocation, so submissions plus accepted pending
orders stay within the backlog bound. An unused reservation while waiting on the
pacer is excluded from backpressure. Slow status requests occupy only their bounded
observer slots; other ready observations and submissions can continue.

Both latency objects contain `samples` (lifetime count), `meanMillis` and
`p95Millis` (nearest-rank p95 over the most recent 1,024 samples). Write latency
measures allocation start through PUT acceptance, including retries. Saga latency
measures allocation start through the first observed terminal status, including
polling. Both exclude pacing/backpressure waits and recovered orders. An idempotent
retry after a lost first PUT response is also a replay, because its original
acceptance time cannot be established from the reply.

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
