# ShardShop order producer

[ShardShop](../../README.md) › [Workloads](../README.md) › **Order producer** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

`shardshop-order-producer` creates its buyers, then allocates, submits and observes
a deterministic stream of orders through the order API.

## Start

In the lab, `up.sh` deploys the producer as the Deployment
`shardshop-order-producer` with one pod and the run name `kind-saga-v1`.

The producer is a continuous HTTP/1.1 workload with bounded concurrent order
submissions. It first resolves every catalog seller and product by creation key
and verifies the returned definitions. Missing entities, transport failures and HTTP 503 repeat the complete
lookup with one-to-fifteen-second exponential backoff and jitter, for at most five
minutes. Invalid JSON or mismatching definitions fail immediately. Only after the
catalog is complete does it create or recover all 3,000 buyers through order's
idempotent API. Buyer creation has its own complete startup deadline. The workload
has no database credentials, Kubernetes token, shard router or ID generator.

With product and order reachable at the configured URLs, launch the packaged
producer from the repository root:

```bash
java -Dshardshop.producer.product-url=http://localhost:8080 \
  -Dshardshop.producer.order-url=http://localhost:8081 \
  -Dshardshop.producer.run-name=local-run-1 \
  -jar microservices/shardshop/shardshop-workload/shardshop-order-producer/target/quarkus-app/quarkus-run.jar
```

`--check-startup` validates the configuration without HTTP requests.

## Monitoring

The [producer control page](../../shardshop-core/workload-control/README.md#monitoring)
on port 18082 shows the state, the rate of new orders, the backlog and the latency.
The control status `producer` object exposes `submissionConcurrency`,
`observerConcurrency`, `maxNonTerminalOrders`, `nonTerminalBacklog`, `backpressured`,
`recoveredOrders`, `confirmedOrders` and `cancelledOrders`. `writeLatency` measures allocation through PUT acceptance;
`sagaLatency` measures allocation through terminal observation. Both report lifetime `samples` and rolling `meanMillis` and `p95Millis` over the
last 1,024 new orders; replay is excluded.

The producer logs startup counts and periodic outcome counts without buyer profiles
or HTTP bodies. A failed order phase names the stuck order, for example
`Order producer failed: Order completion deadline exceeded (ordinal 3, buyer <buyerId>, order <orderId>, last status PENDING_LEDGER)`.

```bash
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-order-producer
```

The [order write metrics](../../shardshop-order/README.md#write-metrics) show the
server side of the same writes.
