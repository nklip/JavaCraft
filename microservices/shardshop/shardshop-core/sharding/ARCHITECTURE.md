# Shard routing architecture

[ShardShop](../../ARCHITECTURE.md) › [Core](../ARCHITECTURE.md) › **Shard routing** · [Runbook](RUNBOOK.md)

`shardshop-sharding` maps a seller or buyer ID to its home shard. Product and order
route through it directly; there is no router service, and no other module
computes routes.

- **Owns:** the version-2 routing rule, the shard topology and the routing
  golden-vector tests. Its `RoutingConfiguration` mapping,
  `ShardRoutingConfiguration` CDI producer and injection tests live in
  `dev.nklip.javacraft.shardshop.sharding.config`.
- **Depends on:** `shardshop-common` for digest calculation.
- **Used by:** product and order only.

## Version-2 routing and topology

### Routing rule

Sellers and buyers use the same routing rule with their own Snowflake IDs,
implemented once in `shardshop-sharding`. Sellers may create products only on
their home-region shard; a product's ID does not determine its placement. A
seller's products and stock reservations stay on the seller's shard, so the
catalog routes by `seller_id`. A buyer's orders, with their items, sagas and
transport records, live on the buyer's shard, so ordering routes by `buyer_id`:

```text
digestInteger(id) = unsignedBigEndian(SHA-256(UTF-8(canonical decimal Snowflake ID)))
shard(id) = deployedShards[digestInteger(id) % deployedShards.size]
initial deployedShards = [shard-a, shard-b, shard-c]
initial regions = [US, EU, ASIA]
homeRegion(id) = shard(id).region
```

- Use the validated canonical decimal ID text, with no newline or leading zeros,
  before UTF-8 encoding.
- Interpret all 32 SHA-256 digest bytes as one unsigned 256-bit **big-endian**
  integer: byte 0 is most significant, byte 31 least significant. Do not use a
  signed integer, truncate the digest, or use Java `hashCode()`.
- Both routing and currency selection use `shardshop-common`'s
  `Sha256.unsignedDigest` after validating the ID. The order producer uses the
  order ID digest modulo 100 only in the explicit USD/EUR test mode; normal traffic
  uses the buyer definition's region and its weighted schedule. Neither mode
  depends on `shardshop-sharding` or computes routing from an ID.

This is identifier/routing contract **version 2**, replacing the previous
unreleased draft and its vectors. The hash contract stays immutable; the initial
ordered inventory preserves all version-2 routing vectors. Generator IDs are
independent of database shard IDs.

### Inventory and routing snapshot

`infra/shards.yaml` defines ordered entries with `name` and `region`. Multiple
shards may serve the same region in a custom inventory; IDs still select the
exact shard through the unchanged hash rule. Shared Helm templates generate all
resources and a routing snapshot. Its `version` remains SHA-256 of the comma-joined
UTF-8 names; `regionVersion` is SHA-256 of the comma-joined UTF-8 `name=REGION`
pairs in the same order. The chart schema validates names and the
`US`/`EU`/`ASIA` enum.

Only after every deployed shard and the separate ledger pass their migrations and
current-generation outbox publications does `up.sh` publish that snapshot as the
`shardshop-routing` ConfigMap. Product/order import aligned
`shardshop.routing.shards` and `shardshop.routing.regions` lists at startup and
construct an immutable `ShardTopology` with `fromNamesAndRegions`. Missing, empty
or duplicate names, invalid regions, or different list lengths fail startup.
All replicas must start with the same snapshot.

### Topology changes

Topology membership is not a health check: outages and primary promotion never
remove shards from the list. Processes do not refresh topology live. Changing
membership or order reassigns existing IDs; deployment/migration refuse changes
to a published list until the lab is explicitly reset and reseeded. Changing an
existing shard's region also fails the published `regionVersion` guard.

Before starting migrations, `up.sh` reserves both version hashes in the immutable
`shardshop-migration-topology` ConfigMap. `up.sh` compares the inventory with this
saved topology as well as the published routing snapshot, including when no
versioned migration is pending. Both existing ConfigMaps must contain matching
`version` and `regionVersion` values; absent snapshots are allowed for initial
bootstrap. The reservation survives a partial migration or failed first
publication and matching reruns reuse it. Keep this ConfigMap while you keep the
dataset, because versioned SQL checksums do not detect changed Flyway placeholder
values.

Retaining populated data across topology changes needs a separate
migration/cutover protocol; never probe all shards as a fallback. Freeze the hash
rule and the published inventory: changing the divisor requires a data migration
strategy.

## Data placement

| Data | Routing key | Writer / owner |
|---|---|---|
| `catalog.sellers` | `seller_id` | Product service |
| `catalog.products` | the product's `seller_id` | Product service creates them; the order service reads them and updates only `stock` in its reservation transactions |
| `catalog.stock_reservations` | the product's `seller_id` | Order service, writing reservations and adjusting product stock in the same shard transaction |
| `catalog.seller_profits` | `seller_id` | Product service inserts zero balances for the seller policy currencies; the order service applies confirmed credits |
| `catalog.profit_credits` | the product's `seller_id` | Order service inserts one immutable credit for each order and product |
| `ordering.buyers` | `buyer_id` | Order service |
| `ordering.order_allocations` | `buyer_id` | Order service; permanent allocation retry mappings |
| `ordering.orders`, `ordering.order_items` | the order's `buyer_id` | Order service |
| `ordering.order_sagas`, `ordering.order_outbox`, `ordering.order_inbox` | the order's `buyer_id` | Order service; colocated with the order |
| `ordering.order_result_ids` | the saga's order's `buyer_id` | Order service; permanent command/result message bindings survive transport cleanup |
| `ordering.profit_intents` | the order's `buyer_id` | Order service; confirmed item margins await idempotent application on seller shards |
| `ordering.orphan_results` | the order's `buyer_id` | Order service; quarantine for a result whose order is missing |
| `ordering.conflicting_results` | the order's `buyer_id` | Order service; quarantine for a result that conflicts with saved identity or outcome |
| `ledger.ledger_entries`, `ledger.ledger_operations`, `ledger.ledger_result_ids`, `ledger.ledger_inbox`, `ledger.ledger_outbox` | No sharding initially | Ledger service, in its separate database |
| `ledger.conflicting_commands` | No sharding initially | Ledger service; quarantine for a command that conflicts with a permanent decision |

## Cross-shard rules

- Order items remain on their order's shard even when referenced products live on
  other shards: a buyer in US, EU, or ASIA may buy from sellers in any region, and
  one order may mix sellers whose product currency matches.
- Seller region/country determines permitted product prices; buyer location
  imposes no currency restriction. The same-currency and stock rules still apply.
- Each item names its seller, product and the quantity the buyer wants; resolve
  them against the seller's primary before creating the order and persist
  server-derived product/price snapshots in its items. Product details are
  immutable after creation; only `stock` changes, through the
  [reservation step](../../shardshop-order/ARCHITECTURE.md#stock-reservation).
- There is no payment or cross-shard transaction. Foreign keys stay inside one
  shard (products to sellers, reservations to products, orders to buyers, items to
  orders); cross-shard references from order items to products have none.
- Product and order IDs are unique within a shard by constraint and across shards
  by Snowflake generation, and the API always addresses a product together with
  its seller and an order together with its buyer.
- Select the shard before opening a transaction, and keep it fixed until commit.
  Order creation and initial saga state commit together on one primary. After stock
  reservation, the transition to `PENDING_LEDGER` and its outgoing command commit
  together on that primary.
- The order CDC relay captures committed outbox inserts from every shard's WAL,
  rather than polling for unpublished rows.
- Public APIs expose decimal-string Snowflake business IDs, never shard selectors:
  catalog calls carry the seller ID and order calls the buyer ID, and the services
  derive the shard from them.

## Related contracts

Product owns its [creation-key placement and read profiles](../../shardshop-product/ARCHITECTURE.md);
order owns allocation and acceptance. The [datasets](../../shardshop-workload/shardshop-datasets/ARCHITECTURE.md)
contain definitions, never routing implementations.
