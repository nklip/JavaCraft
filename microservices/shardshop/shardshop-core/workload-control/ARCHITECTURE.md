# Workload control architecture

[ShardShop](../../ARCHITECTURE.md) › [Core](../ARCHITECTURE.md) › **Workload control** · [README](README.md) · [Runbook](RUNBOOK.md) · [Control contract](src/main/resources/contracts/openapi.yaml)

`shardshop-workload-control` lets an operator change the traffic of each workload
while it runs. A change takes effect without a rebuild, a redeploy or a restart,
and it affects only the selected workload.

- **Contains:** the pacer, the control state, the REST resource, the error mappers
  and one page script and stylesheet.
- **Used by:** the order producer, the product reader and the product seeder. Each
  workload contains its own page markup.
- **Access:** inside the cluster and through `webpages.sh`, without authentication.

## Pages and API

Each of the three workloads serves a dedicated webpage and a workload-owned
control/status API on port 8080 with Quarkus REST:

| Workload | Page | REST root |
|---|---|---|
| Order producer | Workload Order Producer at `/workload-order-producer/` | `/api/v1/order-producer` |
| Product reader | Workload Product Reader at `/workload-product-reader/` | `/api/v1/product-reader` |
| Product seeder | Workload Product Seeder at `/workload-product-seeder/` | `/api/v1/product-seeder` |

The REST root names the workload, thus a later gateway can route each API
separately. The [control contract](src/main/resources/contracts/openapi.yaml)
defines `GET /control`, `PUT /control/rate`, `POST /control/pause`,
`POST /control/resume` and, for the seeder only, `POST /control/runs`.

## Rates

| Workload | Unit of the rate | Default | Maximum | Operations in flight |
|---|---|---:|---:|---|
| Order producer | New order starts per second | 0.5 | 30 | Four submissions and two independent status polls by default |
| Product reader | Product read starts per second across all workers | 200 | 10,000 | One read per worker |
| Product seeder | Outgoing HTTP requests per second, including creation, verification and retries | 100 | 1,000 | One request |

- The server validates a rate as a finite JSON number from zero to the maximum with
  at most three decimal places; an invalid update returns `400` and changes nothing.
- Each accepted update is atomic and increments a revision. Zero stops new work like
  Pause; Pause keeps the target for Resume.
- One schedule spaces the starts of a process. A change wakes waiting callers at
  once, and the next start comes one new interval after the last start or at once.
  Missed starts are never sent later, thus a rate change or a Resume causes no burst.
- The setting lives only in the process: a browser refresh keeps it, and a process
  restart (for example each `up.sh` run) restores the configured defaults.

## Pause and invariants

Pause drains the bounded operation in flight. Reads and seeder requests keep their
HTTP deadlines, bounded attempts and backoff. The producer drains current
submissions and allocates no new ordinal; its observer continues to terminal
status, including while paused.

The control changes no dataset version, run name, ordinal progress, service-issued
ID, retry key or currency selection. The workloads keep their Java-owned catalog
gates and lifecycle, and they have no database credentials or Kubernetes API
access.

## Status

The status shows the lifecycle state (`WAITING`, `IDLE`, `RUNNING`, `PAUSING`,
`PAUSED`, `COMPLETED` or `FAILED`), the effective target, the achieved rate of the
last 10 seconds and the operations in flight. It counts backend HTTP requests,
retries and errors by purpose.

- The producer separates its startup traffic (catalog lookups and buyer creation)
  from allocation, submission and polling, and its rate counts only orders that
  order accepts as new.
- When concurrency divided by the mean operation time of the last 10 seconds is
  below the effective target, the status names this limit.
- The producer also reports its configured concurrency, backlog, backpressure,
  recovered/new terminal counts and write/saga latency samples, mean and p95 over
  the last 1,024 new orders.

## Producer limits

The 30 starts/s cap follows the [step 4.14 lab measurements](../../CHANGELOG.md#step-414):
29.45 new orders/s with bounded order, outbox and profit backlogs and a final
rolling write p95 of 93.73 ms. At target 10/s, write p95 was 91.42 ms. Four
submission slots were sufficient; the maximum of 12 matches one HTTP JVM's
aggregate pool budget of four connections per shard. Requests may wait up to one
second for a pool connection before `503`, so HTTP concurrency can exceed 12 during
short bursts. Retained finite stock and host contention limit what these
measurements establish; the default stays 0.5/s.

## Seeder modes

The seeder has two modes of one application:

| Mode | Runs as | Behavior |
|---|---|---|
| Bootstrap | The default finite Job of each release | Seeds each release, serves no HTTP and keeps the deployment completion gate |
| Interactive | The `shardshop-product-seeder-control` Deployment | Serves its page while it is idle or completed, and starts one operator-requested finite run at a time (`409 RUN_ACTIVE` otherwise) |

A run repeats the dataset keys and payloads, thus it recovers the same IDs without
duplicates and changes no stock or profit. A run has no deadline of its own; a
paused run waits, and a pod restart stops it.

## Security

The control API has no authentication. It is reachable only inside the cluster and
through `webpages.sh`; step 7.5 adds authenticated routes.

See the [README](README.md) for page access and status, the [runbook](RUNBOOK.md)
for API examples, configuration and tests, and the [workload lifecycle](../../shardshop-workload/ARCHITECTURE.md#workload-lifecycle)
for catalog gates.
