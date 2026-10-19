-- Credit identity and signed margin were introduced in V2. Profit posting uses
-- that immutable identity and updates the balance in the same seller-shard transaction.
COMMENT ON TABLE catalog.profit_credits IS
    'Immutable per-order/product margin applied exactly once to seller_profits in the same transaction.';
