# ShardShop workloads

[ShardShop](../README.md) › **Workloads** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

The three workload applications create, read and order through the public HTTP
APIs only.

## Start

`up.sh` starts all three with each release; see the [lab start](../README.md#start-the-lab).

| Workload | Runs as | Guide |
|---|---|---|
| Product seeder | One finite Job for each release, and the interactive Deployment `shardshop-product-seeder-control` | [Seeder](shardshop-product-seeder/README.md) |
| Product reader | Deployment `shardshop-product-reader`, one pod | [Reader](shardshop-product-reader/README.md) |
| Order producer | Deployment `shardshop-order-producer`, one pod | [Producer](shardshop-order-producer/README.md) |

The reader and the producer send load only after they resolve the complete catalog;
the [seeding gate](RUNBOOK.md#seeding-gate) defines the wait.

## Monitoring

The [workload control pages](../shardshop-core/workload-control/README.md) show the
state, rate and backend traffic of each workload. Inspect the automatic release:

```bash
kubectl --context kind-shardshop -n shardshop get jobs -l app.kubernetes.io/name=shardshop-product-seeder
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-product-reader
```
