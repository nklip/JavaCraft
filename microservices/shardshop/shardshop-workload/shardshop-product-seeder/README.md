# ShardShop product seeder

[ShardShop](../../README.md) › [Workloads](../README.md) › **Product seeder** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

`shardshop-product-seeder` creates or recovers every seller and product of the
configured dataset through the product API, then verifies them.

## Start

- **Bootstrap Job:** each `up.sh` release runs the seeder as the Job
  `shardshop-application-seeder-<revision>`, beside the reader that waits for its
  data ([seeding gate](../RUNBOOK.md#seeding-gate)).
- **Interactive mode:** the Deployment `shardshop-product-seeder-control` runs the
  same image with `shardshop.seeder.interactive=true`. It serves its
  [control page](../../shardshop-core/workload-control/README.md) on port 18084 and
  seeds only on operator request.
- **Outside Kubernetes:** with product reachable at the configured URL, start the
  packaged seeder from the repository root. `--check-startup` validates the
  configuration and exits without HTTP requests.

```bash
java -Dshardshop.seeder.product-url=http://localhost:8080 \
  -jar microservices/shardshop/shardshop-workload/shardshop-product-seeder/target/quarkus-app/quarkus-run.jar
```

## Monitoring

The seeder exits with code 0 only after it created and verified all 151 sellers and
201 products. Then it logs one summary line:

```text
Seeded and verified catalog-v2 [US 50 sellers, 50 products {USD=50}; EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, JPY=14, KRW=4, VND=1}], key-to-ID SHA-256 <hex>
```

The digest is the SHA-256 of one `key=sellerId` line per seller and one
`key=sellerId/productId` line per product, each with LF, in dataset order. Equal
digests show that two runs recovered the same IDs. A failure logs one error line
with the operation, the creation key, and the HTTP status and error code or the
failure type. Then the seeder exits with code 1. Log lines contain no response payloads.

Seeder Jobs keep their logs for 24 hours:

```bash
kubectl --context kind-shardshop -n shardshop get jobs -l app.kubernetes.io/name=shardshop-product-seeder
```

The interactive seeder shows its state, rate and seed runs on its
[control page](../../shardshop-core/workload-control/README.md#monitoring).
