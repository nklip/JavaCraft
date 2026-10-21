# ShardShop Product

[Back to ShardShop](../README.md) ·
[Architecture](../ARCHITECTURE.md#4-product-generation-and-read-load) ·
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) ·
[Order service](../shardshop-order/README.md) ·
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

2. Open access to both webpages:

   ```bash
   bash microservices/shardshop/scripts/webpages.sh
   ```

   Wait for `webpages.sh` to print both URLs. It starts and supervises both
   localhost tunnels:

   | Page | Local port | Service port |
   |---|---|---|
   | [Products](http://127.0.0.1:18080/products/) | 18080 | product 8080 |
   | [Orders](http://127.0.0.1:18081/orders/) | 18081 | order 8080 |

3. Open <http://127.0.0.1:18080/products/>.
4. Keep the terminal open while you use either webpage.

`webpages.sh` takes no arguments and requires only `kubectl` and `lsof`.
It stops existing listeners on ports 18080 and 18081, sends TERM first, waits
up to five seconds, then sends KILL to remaining listeners. Both ports must be
released before it opens the tunnels. Run the same command whenever you need
to reopen access; it does not rebuild or restart applications.
A forwarding failure stops the tunnels started by this invocation and leaves
the deployed lab running.

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

**Tunnels:**

- **Scope:** Both tunnels bind only to `127.0.0.1`.
- **Stop the tunnels:** Press Ctrl+C. Both forwards started by this invocation
  stop; the cluster and workloads continue to run.
- **Stop the lab:** Run `bash microservices/shardshop/scripts/down.sh`.
- **Second terminal:** `up.sh` releases its deployment lock before both
  port-forwards start. Thus, `down.sh` or a new deployment can run in a second
  terminal.
- **Forwarding failure:** If either tunnel exits, `up.sh` reports its exit and
  stops the other tunnel. Exiting `up.sh` cleans up both owned processes.

## 2. Seller and product provider

<sub>[Back to contents](#contents)</sub>

Product creates and reads sellers and products. The
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) defines the full
request and response schemas.

New sellers require an immutable `countryCode`: `US` in US; the existing 27 EU
countries in EU; and `CN`, `VN`, `KR`, or `JP` in ASIA. New product prices and unit
costs allow USD for US, EUR/GBP for EU, CNY for CN, VND for VN, KRW for KR, and JPY
for JP. Invalid combinations return HTTP 400 `INVALID_REQUEST` before business
writes. JPY/KRW/VND amounts must be integral; wire money remains exact fixed two
decimal text (`19.00` is valid). Buyers may purchase across regions.

Retained catalog rows keep their IDs, money and profits. Migration V6 backfills
country only when the exact catalog-v1 creation key, saved company and region
match the original TSV provenance. Other sellers retain a null country. Read
responses expose `countryCode`, `newOrderEligible` and `legacyReason` for products;
ineligible legacy products remain visible on the page and cannot enter new orders. The
read-only `catalog.currency_policy_legacy` view reports cutover status. As the
migration owner, inspect unresolved sellers (including sellers with no products)
and noncompliant products with:

```sql
SELECT * FROM catalog.currency_policy_legacy
WHERE legacy_reason IS NOT NULL ORDER BY seller_id, product_id;
```

Runtime roles cannot change seller countries or read this operator report. Identical
old product retries and seller retries without country still recover saved rows;
new sellers require country. Existing profit balances remain intact, while new
sellers initialize only their allowed currencies.
