# Order producer architecture

[ShardShop](../../ARCHITECTURE.md) › [Workloads](../ARCHITECTURE.md) › **Order producer** · [README](README.md) · [Runbook](RUNBOOK.md)

The order producer creates its buyers, then generates a deterministic stream of
regional orders and submits them through the order API. It keeps a bounded number
of submissions in flight and observes each accepted order until it is terminal.

- **Runs as:** one Deployment pod.
- **Uses:** the [datasets](../shardshop-datasets/ARCHITECTURE.md), the
  [order allocation provider](../../shardshop-order/ARCHITECTURE.md#service-issued-order-allocations)
  and [traffic control](../../shardshop-core/workload-control/ARCHITECTURE.md) for
  its live rate.
- **Never:** generates IDs, keeps a progress store, or selects orders from current
  stock or profit.

## Currency mix

The seeder creates country-aware sellers and products in US, EU and ASIA. The
[shared currency policy](../../shardshop-product/ARCHITECTURE.md#regional-currency-policy)
governs product prices; buyer location never limits currency. Normal ledger
configuration accepts all seven supported currencies.

The normal default `rejected-order-percent=-1` uses a buyer-region currency mix.
Each region has a 900-order cycle with these currency counts:

| Buyer region | USD | EUR | GBP | CNY | KRW | JPY | VND |
|---|---:|---:|---:|---:|---:|---:|---:|
| US | 810 | 36 | 9 | 30 | 5 | 5 | 5 |
| EU | 45 | 720 | 90 | 30 | 5 | 5 | 5 |
| ASIA | 45 | 36 | 9 | 540 | 90 | 90 | 90 |

US orders are 90% USD; EU orders are 80% EUR and 10% GBP; ASIA orders are 60% CNY
and 10% each KRW, JPY and VND. Each region's remaining 10% is split equally between
the other two regions. Foreign EU purchases split EUR:GBP at 4:1, and foreign ASIA
purchases split CNY:KRW:JPY:VND at 6:1:1:1. These are workload preferences, without
changing the product-pricing policy or cross-region purchase eligibility.

An explicit `rejected-order-percent` from 0 through 100 keeps the legacy USD/EUR
test distribution from the allocated order ID. A rejection test must also apply its
own temporary ledger allowlist. EUR is accepted normally.

## Deterministic regional order stream

The producer resolves the full catalog, creates or recovers all buyers, then
starts at ordinal zero for its required configured run name. Normal traffic
interleaves US, EU and ASIA buyers.

- **Currency schedule.** Smooth weighted round-robin spreads choices through each
  cycle: add each weight to its current score, choose the highest (ties follow the
  USD/EUR/GBP/CNY/KRW/JPY/VND order), then subtract 900 from the chosen score.
  Scores start at zero. The full dataset's cycle is 2,700 orders; a subset of buyer
  regions interleaves only its available regions.
- **Buyer.** Buyer choice within a region hashes the unchanged run name, decimal
  ordinal and `buyer` role separated by NULs over descriptors sorted by definition
  key.
- **Items.** Products within each currency are sorted by unsigned SHA-256 of the
  run name, NUL, `first-item`, NUL and definition key, with key as the tie-breaker.
  The number of preceding orders in that currency selects the next first product,
  across all buyer regions and cycle boundaries. The following products in that
  order complete up to three distinct items, with deterministic quantities from one
  to three. This visits every product within a currency before repeating;
  currencies with fewer products can exhaust their stock sooner. Current
  stock/profits are never selection inputs.
- **Stream identity.** Normal allocations use `catalog-v2:regional-v1`, keeping the
  changed buyer/currency/item selections separate from the prior
  `catalog-v2:balanced` stream. Historical orders are untouched, and explicit
  USD/EUR drill namespaces remain unchanged.

## Concurrency and backpressure

| Setting | Default | Maximum |
|---|---:|---:|
| Submission slots | 4 | 12 |
| Observer slots | 2 | 12 |
| Non-terminal orders, including outstanding submissions | 100 | 10,000 |

Each submission owns allocation and PUT, then releases its slot without waiting for
the saga. Fair bounded polling observes accepted pending orders. A full
non-terminal window applies backpressure until the saga catches up. The PUT
`Idempotent-Replay` header classifies recovered orders without a GET probe;
recovered pending orders remain observed and recovered counts stay separate from
new outcomes.

## Restart and replay

A restart reconstructs and replays earlier ordinals through the same allocation and
PUT APIs before advancing. This deliberately trades restart catch-up time for no
workload-owned progress store. Replay is paced from ordinal zero with the same
bounded concurrency and immutable identities; Pause stops new submissions while the
independent observer continues.

## Deadlines and failures

One complete deadline bounds allocation, submission, retries and terminal polling
for each ordinal (60 seconds by default). Transport failures and HTTP 503 use
bounded attempts and jittered backoff; validation errors fail immediately. A
failed phase stops the producer for restart. Configuration and operational
commands are in the [producer runbook](RUNBOOK.md).

See the [shared startup lifecycle](../ARCHITECTURE.md#workload-lifecycle) for the
catalog gate.
