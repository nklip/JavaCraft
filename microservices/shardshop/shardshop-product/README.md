# ShardShop Product

[Back to ShardShop](../README.md) ·
[Architecture](../ARCHITECTURE.md#4-product-generation-and-read-load) ·
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) ·
[Implementation plan](../PLAN.md) · [Changelog](../CHANGELOG.md)

`shardshop-product` is the seller and product catalog service of ShardShop.
It writes to and reads from the regional catalog shards.

| Task | Component |
|---|---|
| Create sellers and products, and generate their IDs | **Product** |
| Keep creation keys in the database for retries | **Product** |
| Read the current stock and the confirmed profit | **Product** |
| Serve the read-only regional product page | **Product** |
| Reserve stock and post profit | Order |
| Apply database migrations before product starts | Shared deployment pipeline |

**Working directory:** Run all shell commands from the repository root.

## Contents

1. [Regional product page](#1-regional-product-webpage)
2. [Seller and product provider](#2-seller-and-product-provider)

## 1. Regional product webpage

<sub>[Back to contents](#contents)</sub>

Product serves a read-only webpage at `/products/` from its application image.
The webpage shows the products of one region with their stock, price and earnings.

### Open the webpage

**Prerequisite:** Create the lab with `cluster-create.sh`. See the
[cluster lifecycle](../README.md#local-kubernetes-cluster-operator-and-databases-steps-1121).

1. Deploy the lab:

   ```bash
   bash microservices/shardshop/scripts/up.sh
   ```

2. Wait for the kubectl message `Forwarding from`. After a successful
   deployment, `up.sh` starts this port-forward in the foreground:

   ```bash
   kubectl --context kind-shardshop -n shardshop port-forward service/shardshop-product 18080:8080
   ```

3. Open <http://127.0.0.1:18080/products/>.
4. Keep the terminal open while you use the webpage.

The controls below the products are **First · Previous · Page N · Next · Last**.
First returns to page 1 of the selected region. Last follows the remaining pages
to the end of that region's catalog. First is disabled whenever Previous is;
Last is disabled whenever Next is, including while loading or after an error.
The final region and page remain in the URL for copying, reloading and browser
Back/Forward. Last shares the existing eight-second loading deadline; if it
fails, Refresh retries the jump.

The page, script and stylesheet use `Cache-Control: no-store` so redeployments
load matching assets. After upgrading from the earlier cached version, reload
the page once with **Cmd+Shift+R** on macOS or **Ctrl+Shift+R** on Windows/Linux.
This discards the old HTML that browsers were previously allowed to retain for
24 hours; the updated page also uses new asset URLs to bypass old CSS/JavaScript.

**Tunnel:**

- **Scope:** The tunnel binds only to localhost.
- **Stop the tunnel:** Press Ctrl+C. The cluster and the workloads continue to
  run.
- **Stop the lab:** Run `scripts/down.sh`.
- **Second terminal:** `up.sh` releases its deployment lock before the
  port-forward starts. Thus, `down.sh` or a new deployment can run in a second
  terminal.

## 2. Seller and product provider

<sub>[Back to contents](#contents)</sub>

Product creates and reads sellers and products. The
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) defines the full
request and response schemas.
