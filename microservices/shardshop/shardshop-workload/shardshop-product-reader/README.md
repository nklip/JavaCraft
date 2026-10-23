# ShardShop product reader

[ShardShop](../../README.md) › [Workloads](../README.md) › **Product reader** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

`shardshop-product-reader` sends product reads at a configured rate and reports
their outcomes, latency, regions and connection pool.

## Start

In the lab, `up.sh` deploys the reader as the Deployment `shardshop-product-reader`
with one pod. Outside Kubernetes, start the packaged reader with the product URL;
`--check-startup` validates the configuration and exits without HTTP requests:

```bash
java -Dshardshop.reader.product-url=http://localhost:8080 \
  -jar microservices/shardshop/shardshop-workload/shardshop-product-reader/target/quarkus-app/quarkus-run.jar
```

At startup, the reader looks up each `catalog-v2` seller by its creation key
and region. Then it looks up each product by its creation key under the returned
seller ID. Each response must agree with the dataset payload. The reader keeps the
returned IDs; it never generates or derives an ID. Then it logs one line:

```text
Resolved catalog-v2 [US 50 sellers, 50 products; EU 50 sellers, 100 products; ASIA 51 sellers, 51 products], key-to-ID SHA-256 <hex>
```

The digest uses the same lines as the [seeder digest](../shardshop-product-seeder/README.md#monitoring).
Equal digests show that the reader found the IDs that the seeder verified. While
an entity is missing or product is unavailable, the reader waits and repeats the
lookups, as the [seeding gate](../RUNBOOK.md#seeding-gate) defines. Another failed lookup, or
the end of the wait, logs one error line, and the reader exits with code 1 before
the load starts.

## Monitoring

```bash
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-product-reader
```

The reader logs one metric line for each report interval:

```text
Product reads, interval 30.0 s: 6000 requests, 200.0/s, outcomes {OK=6000}, latency ms {p50=2, p95=4, p99=7, max=15}, regions {ASIA=1996, EU=2009, US=1995}, pool {leased=3, pending=0, available=13, max=16}, catalog-v2 key-to-ID SHA-256 <hex>
```

Each interval and total report repeats the digest of the resolved catalog being
read. Live verification reads at most 100 recent lines and 64 KiB, so it can compare
the reader and seeder digests after the startup summary has rotated away.

The latency percentiles are in whole milliseconds, rounded up. They include the
wait for a connection and all attempts. `regions` counts reads by seller home
region. `pool` is the state of Apache's pool when the line is written: `leased`
connections are in use, `pending` reads wait for a connection, and `available`
connections are open and free. The level is INFO when all reads are `OK`, and
WARN when they are not. At SIGTERM, the reader stops its workers and writes one
`Product reads, total` line as the last report. The deadline of each read limits
this stop. Log lines contain no response payloads. The reader serves its control page and
API on port 8080 with Quarkus REST; product reads use Apache HttpClient 5.

An HTTP status is a result, not a reason to send the request again, because
product already limits and repeats its own database work. Each read has one of
these outcomes:

| Outcome | Meaning |
|---|---|
| `OK` | `200` with the issued IDs and the dataset payload |
| `NOT_FOUND` | `404`, for example a product that a standby has not replayed yet |
| `REPLICA_UNAVAILABLE` | `503 READ_REPLICA_UNAVAILABLE` |
| `UNAVAILABLE` | Another `503`, for example `CATALOG_UNAVAILABLE` |
| `HTTP_ERROR` | A different status |
| `INVALID_RESPONSE` | A body that is too large or not valid JSON, or that has a different ID or payload |
| `DEADLINE` | No result in 5 s |
| `TRANSPORT` | No HTTP response after the permitted attempts, for example a refused connection |

The reader serves its [control page](../../shardshop-core/workload-control/README.md#monitoring)
on port 18083; it shows the state, the effective target and the achieved rate.
