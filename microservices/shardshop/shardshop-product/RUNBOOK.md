# Product runbook

[ShardShop](../README.md) › [Product](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Deployment

The [application chart](../infra/helm/shardshop/templates/applications.yaml)
defines product replicas, routing and credential mounts, generator allocation,
readiness and pod security.

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

Retained catalog rows keep their IDs, money and profits. Migration V6 backfills
country only when the exact catalog-v1 creation key, saved company and region
match the original TSV provenance. Other sellers retain a null country. Read
responses expose `countryCode`, `newOrderEligible` and `legacyReason` for products;
ineligible legacy products remain visible on the page and cannot enter new orders. The
read-only `catalog.currency_policy_legacy` view reports cutover status. As the
migration owner, inspect unresolved sellers (including sellers with no products)
and noncompliant products with:

Runtime roles cannot change seller countries or read this operator report. Identical
old product retries and seller retries without country still recover saved rows;
new sellers require country. Existing profit balances remain intact, while new
sellers initialize only their allowed currencies.

## Page cache after an upgrade

The page, script and stylesheet use `Cache-Control: no-store` so redeployments
load matching assets. After upgrading from the earlier cached version, reload
the page once with **Cmd+Shift+R** on macOS or **Ctrl+Shift+R** on Windows/Linux.
This discards the old HTML that browsers were previously allowed to retain for
24 hours; the updated page also uses new asset URLs to bypass old CSS/JavaScript.
