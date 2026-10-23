# Workload dataset architecture

[ShardShop](../../ARCHITECTURE.md) › [Workloads](../ARCHITECTURE.md) › **Datasets** · [Runbook](RUNBOOK.md) · [Source provenance](SOURCES.md)

All dataset definitions live in the pure `shardshop-datasets` library under
`shardshop-workload`. A definition fixes a payload and its creation retry key,
never an entity ID: product and order issue all IDs.

- **Used by:** the three workload applications only.
- **Contains no:** entity IDs, generator, clock, shard router or service wire
  models. It consumes the pure common currency policy.

## Datasets

| Dataset | Contents | Resources |
|---|---|---|
| `catalog-v2` | 151 sellers and 201 products (US 50, EU 100, ASIA 51). It retains 50 ranked companies per region and adds the explicitly synthetic Vietnam Sample Company to exercise VND. Each seller has one product per allowed currency. | `src/main/resources/datasets/catalog-v2/` |
| `buyers-v1` | 1,000 synthetic profiles per region (3,000 buyers) | `datasets/buyers-v1/` |
| `catalog-v1` | Unchanged resources that retain migration provenance | — |

Regional TSVs hold the definitions. Catalog-v2 replaces the v1 creation
payloads/keys while buyers-v1 remains unchanged.

## Synthetic data rules

- EU covers the EU27 single market; ASIA is limited to China, Vietnam, South Korea
  and Japan.
- Published brand value is the proxy for company popularity, with company
  deduplication; it is not a sales ranking. Name/surname coverage and selection
  limitations are explicit in the workload module's [`SOURCES.md`](SOURCES.md).
- Profiles do not identify verified people. Email local parts normalize first name
  and surname to ASCII and use the reserved domains `example.com`, `example.net` or
  `example.org`.
- All regions reuse numbers from the fictional NANPA range
  `+12025550100`–`+12025550199`, so phone formats do not indicate the buyer's
  country. Phone and postal address values are stable across restarts.
- ASIA name pools contain plausible names from MIT-licensed Faker lists for the
  four countries; they do not claim population-frequency rankings.

## Definitions and keys

Each definition contains a stable creation retry key and its immutable payload.
Regional groups are exposed by `sellersByRegion()` and `buyersByRegion()`;
`productsBySeller()` groups products by their local seller key until creation
returns a real seller ID.

Versioned keys include the region and ordinal, for example
`catalog-v2.US.seller.1` and `buyers-v1.US.buyer.1`. Any subsequent payload change
requires a new version, and no retained key can be reused with a different
payload. Unsupported versions fail locally.

## Related contracts

See [catalog resolution](../ARCHITECTURE.md#catalog-resolution) for client
validation and startup, and the provider-owned [product](../../shardshop-product/ARCHITECTURE.md)
and [order](../../shardshop-order/ARCHITECTURE.md) contracts for service-issued IDs
and durable retries. The [routing library](../../shardshop-core/sharding/ARCHITECTURE.md#version-2-routing-and-topology)
owns the routing algorithm and immutable inventory.
