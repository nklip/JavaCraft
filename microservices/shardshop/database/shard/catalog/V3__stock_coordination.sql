-- A permanent tombstone also fences a worker paused before its first reservation.
-- Rows intentionally have no foreign key to orders, which live on buyer shards.
CREATE TABLE catalog.stock_reservation_guards (
    order_id BIGINT PRIMARY KEY CHECK (order_id > 0),
    closed BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE catalog.stock_reservation_guards IS
    'Permanent per-order serialization and cancellation fence; a closed guard can never accept another reservation.';
