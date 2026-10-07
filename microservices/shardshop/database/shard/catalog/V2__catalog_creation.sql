-- Keep V1's stored name and legacy INSERTs valid while exposing the company model.
-- The generated column has one source of truth and cannot be assigned separately.
ALTER TABLE catalog.sellers
    ADD COLUMN company_name VARCHAR(200) GENERATED ALWAYS AS (name) STORED,
    ADD COLUMN creation_key VARCHAR(128),
    ADD CONSTRAINT sellers_creation_key_check CHECK (
        creation_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    ADD CONSTRAINT sellers_region_creation_key_unique UNIQUE (region, creation_key);
COMMENT ON COLUMN catalog.sellers.creation_key IS
    'Durable region-scoped creation identity; NULL is reserved for pre-API legacy rows.';

ALTER TABLE catalog.products
    ADD COLUMN description VARCHAR(2000),
    ADD COLUMN unit_cost NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN creation_key VARCHAR(128);
UPDATE catalog.products SET description = name;
-- Legacy SQL probes omit description; the HTTP API always supplies it explicitly.
ALTER TABLE catalog.products
    ALTER COLUMN description SET DEFAULT 'Legacy product',
    ALTER COLUMN description SET NOT NULL,
    ADD CONSTRAINT products_description_check CHECK (btrim(description) <> ''),
    ADD CONSTRAINT products_unit_cost_check CHECK (
        unit_cost >= 0 AND unit_cost <> 'NaN'::numeric
    ),
    ADD CONSTRAINT products_creation_key_check CHECK (
        creation_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    ),
    ADD CONSTRAINT products_seller_creation_key_unique UNIQUE (seller_id, creation_key),
    ADD CONSTRAINT products_profit_identity_unique UNIQUE (product_id, seller_id, currency);
COMMENT ON COLUMN catalog.products.unit_cost IS
    'Immutable creation cost; future orders snapshot it before confirmed-margin crediting.';
COMMENT ON COLUMN catalog.products.creation_key IS
    'Durable seller-scoped creation identity; NULL is reserved for pre-API legacy rows.';

CREATE TABLE catalog.seller_profits (
    seller_id BIGINT NOT NULL REFERENCES catalog.sellers (seller_id),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount NUMERIC(19, 2) NOT NULL DEFAULT 0.00 CHECK (amount <> 'NaN'::numeric),
    PRIMARY KEY (seller_id, currency)
);
INSERT INTO catalog.seller_profits (seller_id, currency)
SELECT seller_id, currency
FROM catalog.sellers CROSS JOIN (VALUES ('USD'), ('EUR')) AS initial_currencies(currency);
COMMENT ON TABLE catalog.seller_profits IS
    'Service-owned confirmed margin per currency; signed balances allow losses and never combine currencies.';

-- Order IDs may belong to another shard; the product/seller/currency identity is local.
-- Step 4.6 will atomically insert this identity and apply its margin to the balance.
CREATE TABLE catalog.profit_credits (
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    product_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount <> 'NaN'::numeric),
    credited_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id, product_id),
    FOREIGN KEY (product_id, seller_id, currency)
        REFERENCES catalog.products (product_id, seller_id, currency)
);
CREATE INDEX profit_credits_seller_currency_idx ON catalog.profit_credits (seller_id, currency);
COMMENT ON TABLE catalog.profit_credits IS
    'Immutable per-order/product credit identity for future confirmation retries; no profit engine runs in step 3.6.';
