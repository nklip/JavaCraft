# ShardShop Product

[ShardShop](../README.md) › **Product** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md) · [OpenAPI contract](src/main/resources/contracts/openapi.yaml)

`shardshop-product` is the seller and product catalog service. It writes to and
reads from the regional catalog shards.

## Start

In the lab, `up.sh` deploys product as two identical pods behind the Service
`shardshop-product` on port 8080; see the [lab start](../README.md#start-the-lab).
Each product JVM reserves a new generator ID at every start.

Outside Kubernetes, build product and start `target/quarkus-app/quarkus-run.jar`
with the routing snapshot, reachable shard databases and a fixed test generator
ID, as
[build and run](../RUNBOOK.md#build-and-run) describes. `--check-startup`
validates the configuration and exits.

## Monitoring

### Product page

Open <http://127.0.0.1:18080/products/> after `webpages.sh` starts its tunnels.
Product serves a read-only webpage at `/products/` from its application image.
The webpage shows the products of one region with their stock, price and earnings.

The controls below the products are **First · Previous · Page N · Next · Last**.
First returns to page 1 of the selected region. Last follows the remaining pages
to the end of that region's catalog. First is disabled whenever Previous is;
Last is disabled whenever Next is, including while loading or after an error.
The final region and page remain in the URL for copying, reloading and browser
Back/Forward. Last shares the existing eight-second loading deadline; if it
fails, Refresh retries the jump.

The page reads `GET /api/v1/products?region=US&limit=25&after=<productId>`; the
[product API](ARCHITECTURE.md#product-api) defines its parameters.

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
