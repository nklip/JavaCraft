# Workload runbook

[ShardShop](../README.md) › [Workloads](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

The workload applications have their own runbooks:
[datasets](shardshop-datasets/RUNBOOK.md), [seeder](shardshop-product-seeder/RUNBOOK.md),
[reader](shardshop-product-reader/RUNBOOK.md) and [producer](shardshop-order-producer/RUNBOOK.md).

## Seeding gate

The reader owns its start condition, as the
[workload lifecycle](ARCHITECTURE.md#workload-lifecycle) defines. It sees the
catalog only through product's HTTP API. At startup, it looks up the 151 sellers
and 201 products of `catalog-v2` by creation key, and it sends load only after all
lookups succeed and match the dataset:

- While an entity is missing or product is unavailable, slow or not reachable,
  SmallRye Fault Tolerance repeats the lookups with exponential backoff from 1 to
  15 seconds. Each repeat logs one INFO line that names the failed lookup. The
  reader sets the Apache `HttpRequestRetryExec` logger to WARN, thus a refused
  connection adds no line for each repeated request.
- After 5 minutes, the reader stops with exit code 1, and Kubernetes restarts the
  pod.
- A response that differs from the dataset stops startup at once.

The MicroProfile Fault Tolerance keys
`dev.nklip.javacraft.shardshop.workload.reader.config.CatalogWait/resolve/Retry/maxDuration`,
`.../Retry/delay` and `.../ExponentialBackoff/maxDelay` change these bounds, in
milliseconds.

`shardshop-application` owns routing, the product/order ConfigMaps and Services,
the ledger ConfigMap, and all five application Deployments. Each Helm revision runs a
new `post-install`/`post-upgrade` seeder Job named
`shardshop-application-seeder-<revision>`. The seeder needs no user run name; the
order producer uses the separate durable `orderProducer.runName` setting. The Job has
`restartPolicy: Never`, `backoffLimit: 3` and `activeDeadlineSeconds: 300`; Helmfile
uses a bounded 600-second release timeout and Helm waits for the hook result.

An unchanged invocation still validates migrations and seeds idempotently, while
each startup rolls all application Deployment pods. Product also rolls when its image,
resources or configuration change. Its pod checksum is SHA-256 of the complete
rendered `application.properties`, using the same named template as the mounted
file, including static defaults. Reader image, HTTP settings, resources and
dataset version are direct template inputs. Helm revision numbers stay outside
application pod templates; the startup restart token controls the rollout. The reader
and order-producer Deployments' `shardshop.javacraft/seeder-job` annotations name
the latest seeder Job without replacing their pods. Standalone reader verification
prefers that annotation and retains the old pod run-label fallback for manual
legacy deployments.

Successful and failed seeder Jobs retain their logs for 24 hours before Kubernetes
TTL cleanup. After that evidence expires, run `up.sh` again before standalone
workload verification. Each seeding attempt recovers the same catalog IDs. If the
catalog is already complete, the reader can continue load while seeding repeats.

## Workload pods

The seeder and the reader get only the product URL
`http://shardshop-product.shardshop.svc:8080` in `SHARDSHOP_SEEDER_PRODUCT_URL` and
`SHARDSHOP_READER_PRODUCT_URL`. They have no API token, no Secret, no database
setting and no Service variables. The reader Deployment uses the `Recreate`
strategy, thus a maximum of one reader runs. The reader, the order
producer and the interactive seeder Deployment `shardshop-product-seeder-control`
open port 8080 for their [control pages](../shardshop-core/workload-control/README.md).
A ClusterIP Service with the same name as each Deployment selects its pod, and a TCP
readiness probe reports the open port. The interactive seeder uses the seeder
budget below; the bootstrap Job serves no HTTP.

The resource budgets come from measurements on the lab on 2026-10-09. At 200
reads/s, each product pod used approximately 10% of one core and 310 MiB of
anonymous memory for its two JVMs. The reader used 7% of one core and 148 MiB.
Now each product pod runs one JVM. On 2026-10-13, at approximately 190
reads/s and a few minutes after a pod replacement, each product pod used 17–23% of
one core and 268 MiB of anonymous memory. The budgets stay the same.
On 2026-10-22, with the control HTTP server, the container working sets
were 207 MiB for the reader, 179 MiB for the order producer and 163 MiB for the
interactive seeder, inside their limits; the budgets stay the same.
With a 1-CPU limit, the JVM starts were throttled for many seconds, and the reader's
startup lookups failed. Thus each container has a 2-CPU limit.

| Container | CPU request | CPU limit | Memory request | Memory limit |
|---|---|---|---|---|
| Product | 250m | 2 | 384Mi | 512Mi |
| Seeder | 100m | 2 | 192Mi | 256Mi |
| Reader | 100m | 2 | 192Mi | 384Mi |

After a product rollout, the new product JVMs are cold. The 16 concurrent startup
lookups of the reader can then receive `503`, because product allows one second to
open or lease a database connection. The reader then waits and repeats the lookups,
as the [seeding gate](#seeding-gate) defines. Against warm product pods, the lookups
take less than one second.
