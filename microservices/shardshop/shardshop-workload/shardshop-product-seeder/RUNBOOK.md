# Product seeder runbook

[ShardShop](../../README.md) › [Workloads](../README.md) › [Product seeder](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Behavior

`shardshop-product-seeder` is the finite application for the seeder Job. It sends
the `catalog-v2` definitions to the product API in dataset order: each seller, and
then the products in that seller's policy currencies. Each POST has the definition key in
`Idempotency-Key`. Product requests use the seller ID from the seller response.
After all creations, the seeder reads each seller and product by its returned ID
and by its creation key. Each read must return the acknowledged ID and the dataset
payload. In the default product read profile, these GETs read the primaries.
Step 6.1 plans to make the seeder repeat a verification read that gets `404` for a
limited time, because replica-profile reads can lag.

The seeder keeps no local state. A new run sends the same keys and payloads again.
Product returns `200` with the stored ID for an entity that exists and creates only
the missing entities. Thus a run after a partial failure keeps the IDs, home regions
and payloads that the failed run received.

| Product result | Seeder action |
|---|---|
| `201` or `200` with a valid ID and the dataset payload | Keep the returned entity |
| `503`, a response timeout, or a refused or closed connection | Send the same request again after a backoff, up to `max-attempts` attempts |
| A different status, an invalid response, or a read with a different ID or payload | Stop the run at once |

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `shardshop.seeder.product-url` | None. Startup fails without it | Base URL of the product Service |
| `shardshop.seeder.catalog-version` | `catalog-v2` | Dataset version. Other versions fail before HTTP |
| `shardshop.seeder.connect-timeout` | `2s` | HTTP/1.1 connection deadline |
| `shardshop.seeder.request-timeout` | `5s` | Complete deadline of one attempt, including the response body |
| `shardshop.seeder.max-attempts` | `5` | Attempts for each request, 1–10 |
| `shardshop.seeder.retry-backoff` | `500ms` | First delay. Each failed attempt doubles it |
| `shardshop.seeder.max-retry-backoff` | `5s` | Maximum delay |
| `shardshop.seeder.request-rate` | `100` | Outgoing HTTP requests per second, including repeated attempts, 0–1000; positive for the Job |
| `shardshop.seeder.interactive` | `false` | `true` serves the [control page](../../shardshop-core/workload-control/README.md) and seeds only on operator request |

With the defaults, one request stops after a maximum of approximately 33 seconds.
At 100 requests/s, the 1,056 requests of a complete run take approximately 11 seconds.
The Job deadline of 300 seconds limits the complete run. Environment
variables such as `SHARDSHOP_SEEDER_PRODUCT_URL` also set these properties.
`--check-startup` validates the configuration and exits without HTTP requests.

## Tests

`ProductSeeder.feature` of the [integration tests](../../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)
runs the packaged seeder against the packaged product and four shard databases
(two US). It does not connect to the kind lab. A database session holds the
advisory lock of `catalog-v2.EU.seller.10`, so the first seeder run fails with
`503` after 59 sellers and 68 products. After a product restart with a new
generator ID, a complete run must keep those rows unchanged. Two complete runs
must store identical rows and log the same digest. The scenario calculates the
expected payloads from the dataset TSVs and the digest from the stored IDs. It
also checks seller ID routes, creation-key homes, product colocation and zero
policy-appropriate currency profits. A last run against product with unavailable replica reads must
fail and leave the rows unchanged:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```
