-- Catalog v1: sellers, their products with stock, and per-order stock reservations.
-- A seller's rows all live on the seller's shard, so these foreign keys stay local.
CREATE TABLE catalog.sellers (
    seller_id BIGINT PRIMARY KEY CHECK (seller_id > 0),
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Only stock changes after creation; there is no restock, so it never exceeds initial_stock.
CREATE TABLE catalog.products (
    product_id BIGINT PRIMARY KEY CHECK (product_id > 0),
    seller_id BIGINT NOT NULL REFERENCES catalog.sellers (seller_id),
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    price NUMERIC(19, 2) NOT NULL CHECK (price >= 0 AND price <> 'NaN'::numeric),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    initial_stock INTEGER NOT NULL CHECK (initial_stock >= 0),
    stock INTEGER NOT NULL CHECK (stock >= 0 AND stock <= initial_stock),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX products_seller_id_idx ON catalog.products (seller_id);

-- One row per order and product; the key makes a retried reservation a no-op.
CREATE TABLE catalog.stock_reservations (
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    product_id BIGINT NOT NULL REFERENCES catalog.products (product_id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMPTZ CHECK (released_at >= reserved_at),
    PRIMARY KEY (order_id, product_id)
);
CREATE INDEX stock_reservations_product_id_idx ON catalog.stock_reservations (product_id);
