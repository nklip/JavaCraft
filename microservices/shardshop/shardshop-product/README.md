# ShardShop Product

[ShardShop](../README.md) › **Product** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md) · [OpenAPI contract](src/main/resources/contracts/openapi.yaml)

`shardshop-product` is the seller and product catalog service. It writes to and
reads from the regional catalog shards.

## Start

In the lab, `up.sh` deploys product as two identical pods behind the Service
`shardshop-product` on port 8080; see the [lab start](../README.md#1-start-the-lab).
Each product JVM reserves a new generator ID at every start.

Outside Kubernetes, build product and start `target/quarkus-app/quarkus-run.jar`
with the routing snapshot, reachable shard databases and a fixed test generator
ID, as
[build and run](../RUNBOOK.md#2-build-and-run) describes. `--check-startup`
validates the configuration and exits.

## Monitoring

### Product page

Open <http://127.0.0.1:18080/products/> after `webpages.sh` starts its tunnels.
Product serves a read-only webpage at `/products/` from its application image.
The webpage shows the products of one region with their stock, price and earnings.

The controls below the products are **First · Previous · Catalog page · Next · Last**.
First reads the beginning of the selected region. Last directly reads its final
window of up to 25 products; this window can overlap a forward page. Each action,
including Refresh and browser Back/Forward, makes one listing request within an
eight-second deadline. Errors show no partial results; Refresh retries the same
position. Empty cursor views remain visible with available recovery controls.

The URL stores the region and an opaque `after` or `before` cursor, or `last=true`.
Copied links and reloads request that position directly. Refresh on `last=true`
reads the latest final window. Region changes start at the beginning. Page numbers
are not shown because live catalog changes and direct cursors cannot establish
them. Legacy `page=1` links open the beginning; other numbered links show an
explicit migration message with First and Last controls instead of traversing the
catalog. Invalid or mismatched cursors show a recoverable link error.

The [product API](ARCHITECTURE.md#1-product-api) defines the listing parameters.

### Metrics

Product exposes per-pod HTTP request counters through Micrometer's Prometheus
endpoint at `/q/metrics`. With the tunnels open, this command reads the pod that
the Service selects:

```bash
curl -s http://127.0.0.1:18080/q/metrics
```

### Logs

```bash
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-product
```

Each JVM start logs `Reserved generator ID <n> for this JVM start.` at INFO.

### Currency-policy report

The [database guide](../database/README.md#catalog-currency-policy-report) shows the
read-only report of retained catalog rows that the currency policy does not permit
for new orders.
