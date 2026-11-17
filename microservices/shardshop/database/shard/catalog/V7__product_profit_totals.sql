-- Flyway keeps this migration in one transaction. ALTER TABLE takes an ACCESS EXCLUSIVE
-- lock: earlier credit writers finish before the snapshot; later legacy writers fail
-- the fence rather than committing a credit without its product total.
ALTER TABLE catalog.profit_credits
    ADD COLUMN product_total_applied BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE catalog.profit_credits
    ALTER COLUMN product_total_applied SET DEFAULT false,
    ADD CONSTRAINT profit_credits_product_total_applied CHECK (product_total_applied);
COMMENT ON COLUMN catalog.profit_credits.product_total_applied IS
    'Writer protocol fence: new credits explicitly opt into atomic product-total accounting; legacy inserts fail and retry.';

CREATE TABLE catalog.product_profits (
    product_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    -- SUM(numeric(19,2)) was unbounded. Preserve that range and exact signed cents.
    amount NUMERIC NOT NULL CHECK (
        amount NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        AND amount = trunc(amount, 2)
    ),
    PRIMARY KEY (product_id, currency),
    FOREIGN KEY (product_id) REFERENCES catalog.products (product_id),
    CHECK (currency ~ '^[A-Z]{3}$')
);
INSERT INTO catalog.product_profits (product_id, currency, amount)
SELECT product_id, currency, sum(amount)
FROM catalog.profit_credits
GROUP BY product_id, currency;
COMMENT ON TABLE catalog.product_profits IS
    'Exact signed immutable-credit totals per product/currency; new credits and seller balances update atomically.';
