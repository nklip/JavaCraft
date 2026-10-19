-- Keep the first accepted decision and each reserved result ID's exact payload after inbox cleanup.
ALTER TABLE ordering.order_sagas
    ADD COLUMN ledger_result JSONB CHECK (ledger_result IS NULL OR jsonb_typeof(ledger_result) = 'object');
ALTER TABLE ordering.order_result_ids
    ADD COLUMN received_envelope JSONB CHECK (
        received_envelope IS NULL OR jsonb_typeof(received_envelope) = 'object'
    );

-- The home shard records these intents in the same transaction as confirmation.
-- A legacy NULL cost has no truthful margin and must be reconciled before insertion.
CREATE TABLE ordering.profit_intents (
    order_id BIGINT NOT NULL REFERENCES ordering.orders (order_id),
    item_position INTEGER NOT NULL,
    seller_id BIGINT NOT NULL CHECK (seller_id > 0),
    product_id BIGINT NOT NULL CHECK (product_id > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount <> 'NaN'::numeric),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMPTZ CHECK (applied_at IS NULL OR applied_at >= created_at),
    failures INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    blocked_reason VARCHAR(32) CHECK (blocked_reason IN (
        'CREDIT_CONFLICT', 'MISSING_CATALOG_REFERENCE', 'BALANCE_OUT_OF_RANGE'
    )),
    PRIMARY KEY (order_id, item_position),
    UNIQUE (order_id, product_id),
    FOREIGN KEY (order_id, item_position)
        REFERENCES ordering.order_items (order_id, item_position)
);
CREATE INDEX profit_intents_pending_idx
    ON ordering.profit_intents (next_attempt_at, order_id, item_position)
    WHERE applied_at IS NULL AND blocked_reason IS NULL;
COMMENT ON TABLE ordering.profit_intents IS
    'Immutable confirmed per-item margin; retry and application metadata may change. Blocked conflicts retain the original work for investigation.';
