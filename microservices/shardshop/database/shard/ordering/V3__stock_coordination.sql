-- Stock retries and ownership are independent of the later ledger reconciliation budget.
ALTER TABLE ordering.order_sagas
    ADD COLUMN stock_lease_version BIGINT NOT NULL DEFAULT 0 CHECK (stock_lease_version >= 0),
    ADD COLUMN stock_lease_until TIMESTAMPTZ,
    ADD COLUMN stock_failures INTEGER NOT NULL DEFAULT 0 CHECK (stock_failures >= 0),
    ADD COLUMN next_stock_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX order_sagas_stock_work_idx ON ordering.order_sagas (next_stock_attempt_at, saga_id)
    WHERE status = 'PENDING_STOCK' OR (status = 'CANCELLED' AND stock_released_at IS NULL);
COMMENT ON COLUMN ordering.order_sagas.stock_lease_version IS
    'Monotonic ownership token; every stock transition also requires an unexpired lease.';
