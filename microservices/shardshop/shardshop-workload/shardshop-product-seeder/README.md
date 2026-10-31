# shardshop-product-seeder

[ShardShop](../../README.md) › [Workloads](../README.md) › **Product seeder**

`shardshop-product-seeder` creates or recovers every seller and product of the configured dataset through the
product API, then verifies them. One application runs in two modes: the finite bootstrap Job of each release,
and an [interactive Deployment](../../shardshop-core/workload-control/README.md#seeder-modes) that seeds only
on operator request.

## Design

### Finite seeding and verification

| Job setting | Value |
|---|---|
| `restartPolicy` | `Never` |
| `backoffLimit` | `3` |
| `activeDeadlineSeconds` | `300` |

- The seeder sends the `catalog-v2` definitions to the product API in dataset order: each seller, then the
  products in the policy currencies of that seller, with initial stock. Each POST has the definition key in
  `Idempotency-Key`. Product generates each entity ID, and the seeder uses the returned seller ID for product
  creation.
- It exits successfully only after every seller and product is acknowledged and verified through product reads,
  by returned ID and by creation key. Each read must return the acknowledged ID and the dataset payload. In the
  default profile, these reads use primaries.
- The seeder keeps no local state. A new run sends the same creation keys and payloads and recovers the same
  IDs. Product returns `200` with the stored ID for an entity that exists and creates only the missing
  entities. Thus a run after a partial failure keeps the IDs, home regions and payloads that the failed run received.
- Within a run, the seeder sends a request again after a transport failure or a `503` response, with bounded
  backoff. Post-write verification reads, by ID and by creation key, also retry `404`, because a standby can
  replay an acknowledged commit later in the replica profile. Creation requests never retry `404`. All
  retryable failures share the configured attempt limit, capped backoff and per-attempt response deadline.
- Other statuses and reads that differ from the acknowledged ID or payload stop the run.

| Product result | Seeder action |
|---|---|
| `201` or `200` with a valid ID and the dataset payload | Keep the returned entity |
| `503`, a response timeout, or a refused or closed connection | Send the same request again after a backoff, up to `max-attempts` attempts |
| `404` from a post-write verification read | Repeat the same GET with the same backoff and shared `max-attempts` budget |
| A different status, an invalid response, or a read with a different ID or payload | Stop the run at once |

The [shared lifecycle](../README.md#workload-lifecycle) defines the deployment order and completed Jobs. The
[workload control](../../shardshop-core/workload-control/README.md#seeder-modes) defines the interactive mode,
single-run control and pause semantics.

## Operation

### Start

- **Bootstrap Job:** each `up.sh` release runs the seeder as the Job `shardshop-application-seeder-<revision>`,
  beside the reader that waits for its data ([seeding gate](../README.md#seeding-gate)).
- **Interactive mode:** the Deployment `shardshop-product-seeder-control` runs the same image with
  `shardshop.seeder.interactive=true`. It serves its
  [control page](../../shardshop-core/workload-control/README.md) on port 18084 and seeds only on operator request.
- **Outside Kubernetes:** with product reachable at the configured URL, start the packaged seeder from the
  repository root. `--check-startup` validates the configuration and exits without HTTP requests.

```bash
java -Dshardshop.seeder.product-url=http://localhost:8080 \
  -jar microservices/shardshop/shardshop-workload/shardshop-product-seeder/target/quarkus-app/quarkus-run.jar
```

### Monitoring

The seeder exits with code 0 only after it created and verified all 151 sellers and 201 products. Then it logs
one summary line:

```text
Seeded and verified catalog-v2 [US 50 sellers, 50 products {USD=50}; EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, JPY=14, KRW=4, VND=1}], key-to-ID SHA-256 <hex>
```

The digest is the SHA-256 of one `key=sellerId` line per seller and one `key=sellerId/productId` line per
product, each with LF, in dataset order. Equal digests show that two runs recovered the same IDs. A failure logs
one error line with the operation, the creation key, and the HTTP status and error code or the failure type.
Then the seeder exits with code 1. Log lines contain no response payloads.

Seeder Jobs keep their logs for 24 hours:

```bash
kubectl --context kind-shardshop -n shardshop get jobs -l app.kubernetes.io/name=shardshop-product-seeder
```

The interactive seeder shows its state, rate and seed runs on its
[control page](../../shardshop-core/workload-control/README.md#monitoring).

### Configuration

| Property | Default | Purpose |
|---|---|---|
| `shardshop.seeder.product-url` | None. Startup fails without it | Base URL of the product Service |
| `shardshop.seeder.catalog-version` | `catalog-v2` | Dataset version. Other versions fail before HTTP |
| `shardshop.seeder.connect-timeout` | `2s` | HTTP/1.1 connection deadline |
| `shardshop.seeder.request-timeout` | `5s` | Complete deadline of one attempt, including the response body |
| `shardshop.seeder.max-attempts` | `5` | Attempts for each request, 1-10 |
| `shardshop.seeder.retry-backoff` | `500ms` | First delay. Each failed attempt doubles it |
| `shardshop.seeder.max-retry-backoff` | `5s` | Maximum delay |
| `shardshop.seeder.request-rate` | `100` | Outgoing HTTP requests per second, including repeated attempts, 0-1000. Positive for the Job |
| `shardshop.seeder.interactive` | `false` | `true` serves the [control page](../../shardshop-core/workload-control/README.md) and seeds only on operator request |

With the defaults, one request stops after a maximum of approximately 33 seconds of HTTP attempts and backoff,
including repeated verification `404` responses. Rate pacing and an operator pause can extend the wall-clock
time. The bootstrap Job also has its overall deadline of 300 seconds. At 100 requests/s, the 1,056 requests of
a complete run take approximately 11 seconds. Environment variables such as `SHARDSHOP_SEEDER_PRODUCT_URL`
also set these properties.

### Tests

`ProductSeeder.feature` of the [integration tests](../../shardshop-testing/RUNBOOK.md#integration-tests-with-testcontainers)
runs the packaged seeder against the packaged product and four shard databases (two US). It does not connect to
the kind lab. A database session holds the advisory lock of `catalog-v2.EU.seller.10`, so the first seeder run
fails with `503` after 59 sellers and 68 products. After a product restart with a new generator ID, a complete
run must keep those rows unchanged. Two complete runs must store identical rows and log the same digest. The
scenario calculates the expected payloads from the dataset TSVs and the digest from the stored IDs. It also
checks seller ID routes, creation-key homes, product colocation and zero policy-appropriate currency profits.
A last run against product with unavailable replica reads must fail and leave the rows unchanged:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration clean verify
```
