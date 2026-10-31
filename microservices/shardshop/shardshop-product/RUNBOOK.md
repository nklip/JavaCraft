# Product runbook

[ShardShop](../README.md) › [Product](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Deployment

The [application chart](../infra/helm/shardshop/templates/applications.yaml)
defines product replicas, routing and credential mounts, generator allocation,
readiness and pod security.

## Opt-in read diagnostics

Set `shardshop.catalog.read-diagnostics.enabled=true` in
`product.configuration` for a bounded measurement. The default is `false`.
This enables read-only phase timers and pool observations on each product pod's
existing `/q/metrics`; it changes no SQL, pool limit, retry or deadline.

`shardshop_product_read_database_phase_seconds_count` and `_sum` have fixed
`target=primary|replica`, `outcome=success|failure` and `phase` labels:

| Phase | Measured interval |
|---|---|
| `dispatch` | Submission to entry into the database worker |
| `acquisition` | Complete connection acquisition, including queueing, creation and validation |
| `connection_setup` | Network timeout, read-only mode and transaction setup |
| `statement_setup` | Timeout-setting SQL and statement preparation |
| `query` | `executeQuery()`, excluding result iteration and close |
| `work` | Entire application database callback, including setup, query and result decoding |
| `pre_commit_setup` | Timeout-setting SQL immediately before commit |
| `commit` | Commit call |

Subtract count/sum snapshots for each pod and label set; interval mean is the
sum delta divided by the count delta. `work` overlaps `statement_setup` and
`query`, so these durations must not all be added. `_max` is a rolling Micrometer
observation, not an interval percentile. A phase outcome is not an HTTP outcome;
late completion after cancellation can enter a later scrape.

`shardshop_product_read_pool_connections{state="active|available|awaiting"}`
and `shardshop_product_read_pool_creations_total` aggregate pools by target.
Awaiting is instantaneous; creation deltas help distinguish connection opening
from steady-state acquisition. Primary pools also serve writes. These aggregate
values do not locate an individual shard bottleneck. Metrics retain no IDs,
SQL text, response payloads or individual request samples.

## Seller and product provider

Product creates and reads sellers and products. The
[OpenAPI contract](src/main/resources/contracts/openapi.yaml) defines the full
request and response schemas.

New sellers require an immutable `countryCode`: `US` in US; the existing 27 EU
countries in EU; and `CN`, `VN`, `KR`, or `JP` in ASIA. New product prices and unit
costs allow USD for US, EUR/GBP for EU, CNY for CN, VND for VN, KRW for KR, and JPY
for JP. Invalid combinations return HTTP 400 `INVALID_REQUEST` before business
writes. JPY/KRW/VND amounts must be integral; wire money remains exact fixed two
decimal text (`19.00` is valid). Buyers may purchase across regions.

Retained catalog rows keep their IDs, money and profits. The catalog migration backfills
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

## Page cache

The page, script and stylesheet use `Cache-Control: no-store`, so redeployments load
matching assets. If a browser shows an old page, reload it with **Cmd+Shift+R** on
macOS or **Ctrl+Shift+R** on Windows/Linux.

## Product page JavaScript checks

The mandatory `ProductBrowserScriptTest` in `shardshop-testing` runs
`src/test/js/products.test.cjs` during Maven `test`. It requires Node.js on `PATH`
and no browser, Docker or running lab. Run the
[verification-module unit tests or focused page checks](../shardshop-testing/RUNBOOK.md#javascript-page-checks)
when changing the product page; a product-only Maven build does not run this harness.
