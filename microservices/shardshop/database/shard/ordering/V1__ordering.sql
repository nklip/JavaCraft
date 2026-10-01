-- Orders live with their buyer. No foreign key crosses to a seller's catalog shard.
CREATE TABLE ordering.buyers (
    buyer_id BIGINT PRIMARY KEY CHECK (buyer_id > 0),
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    region VARCHAR(4) NOT NULL DEFAULT '${shardRegion}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT buyers_region_check CHECK (region = '${shardRegion}'),
    CONSTRAINT buyers_shard_check CHECK (
        ('0x' || encode(sha256(convert_to(buyer_id::text, 'UTF8')), 'hex'))::numeric
            % ${shardCount} = ${shardIndex}
    )
);
COMMENT ON COLUMN ordering.buyers.region IS
    'Immutable home region from the deployed inventory and version-2 buyer ID route.';

-- Retained retry coordinates; allocation alone creates no order or saga.
CREATE TABLE ordering.order_allocations (
    order_id BIGINT PRIMARY KEY CHECK (order_id > 0),
    buyer_id BIGINT NOT NULL REFERENCES ordering.buyers (buyer_id),
    run_name VARCHAR(200) NOT NULL CHECK (btrim(run_name) <> ''),
    request_ordinal BIGINT NOT NULL CHECK (request_ordinal >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (buyer_id, run_name, request_ordinal),
    UNIQUE (order_id, buyer_id)
);

CREATE TABLE ordering.orders (
    order_id BIGINT PRIMARY KEY,
    buyer_id BIGINT NOT NULL REFERENCES ordering.buyers (buyer_id),
    request_fingerprint TEXT NOT NULL CHECK (btrim(request_fingerprint) <> ''),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_amount NUMERIC(19, 2) NOT NULL CHECK (total_amount >= 0 AND total_amount <> 'NaN'::numeric),
    status VARCHAR(14) NOT NULL DEFAULT 'PENDING_STOCK'
        CHECK (status IN ('PENDING_STOCK', 'PENDING_LEDGER', 'CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (updated_at >= created_at),
    FOREIGN KEY (order_id, buyer_id) REFERENCES ordering.order_allocations (order_id, buyer_id)
);
CREATE INDEX orders_buyer_id_idx ON ordering.orders (buyer_id);

CREATE TABLE ordering.order_items (
    order_id BIGINT NOT NULL REFERENCES ordering.orders (order_id),
    item_position INTEGER NOT NULL CHECK (item_position >= 0),
    seller_id BIGINT NOT NULL CHECK (seller_id > 0),
    product_id BIGINT NOT NULL CHECK (product_id > 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    product_name VARCHAR(200) NOT NULL CHECK (btrim(product_name) <> ''),
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price >= 0 AND unit_price <> 'NaN'::numeric),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    PRIMARY KEY (order_id, item_position)
);
COMMENT ON TABLE ordering.order_items IS
    'Immutable catalog snapshots; seller/product IDs can belong to any region and have no local catalog foreign keys.';

CREATE TABLE ordering.order_sagas (
    saga_id BIGINT PRIMARY KEY CHECK (saga_id > 0),
    order_id BIGINT NOT NULL UNIQUE REFERENCES ordering.orders (order_id),
    command_id BIGINT NOT NULL UNIQUE CHECK (command_id > 0),
    status VARCHAR(14) NOT NULL DEFAULT 'PENDING_STOCK'
        CHECK (status IN ('PENDING_STOCK', 'PENDING_LEDGER', 'CONFIRMED', 'CANCELLED')),
    cancellation_reason TEXT CHECK (btrim(cancellation_reason) <> ''),
    stock_released_at TIMESTAMPTZ,
    reconciliation_attempts INTEGER NOT NULL DEFAULT 0 CHECK (reconciliation_attempts BETWEEN 0 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (updated_at >= created_at),
    next_reconciliation_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '60 seconds'),
    CHECK (next_reconciliation_at >= created_at),
    CHECK (stock_released_at IS NULL OR (status = 'CANCELLED' AND stock_released_at >= created_at))
);
CREATE INDEX order_sagas_pending_idx ON ordering.order_sagas (next_reconciliation_at, saga_id)
    WHERE status IN ('PENDING_STOCK', 'PENDING_LEDGER');
CREATE INDEX order_sagas_release_idx ON ordering.order_sagas (saga_id)
    WHERE status = 'CANCELLED' AND stock_released_at IS NULL;

-- Permanent result identities outlive transport cleanup and remain bound to their saga.
-- Order inserts a reservation and its outbox envelope in the same Java transaction.
CREATE TABLE ordering.order_result_ids (
    result_message_id BIGINT PRIMARY KEY CHECK (result_message_id > 0),
    message_id BIGINT NOT NULL UNIQUE CHECK (message_id > 0),
    saga_id BIGINT NOT NULL REFERENCES ordering.order_sagas (saga_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (message_id, result_message_id, saga_id),
    UNIQUE (result_message_id, saga_id),
    CHECK (message_id <> result_message_id)
);
CREATE INDEX order_result_ids_saga_id_idx ON ordering.order_result_ids (saga_id);

CREATE TABLE ordering.order_outbox (
    message_id BIGINT PRIMARY KEY,
    result_message_id BIGINT NOT NULL UNIQUE,
    saga_id BIGINT NOT NULL,
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ CHECK (published_at >= created_at),
    FOREIGN KEY (message_id, result_message_id, saga_id)
        REFERENCES ordering.order_result_ids (message_id, result_message_id, saga_id)
);
CREATE INDEX order_outbox_pending_idx ON ordering.order_outbox (created_at, message_id)
    WHERE published_at IS NULL;
COMMENT ON TABLE ordering.order_outbox IS
    'Immutable RecordOrder envelope including string IDs, schema version, fingerprint and full snapshot; only publication metadata changes. Cleanup also requires a durable CDC checkpoint past the insert.';

CREATE TABLE ordering.order_inbox (
    message_id BIGINT PRIMARY KEY,
    saga_id BIGINT NOT NULL,
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id, saga_id) REFERENCES ordering.order_result_ids (result_message_id, saga_id)
);

-- No foreign keys: a restored shard may lack the order, buyer and reserved result ID.
CREATE TABLE ordering.orphan_results (
    message_id BIGINT NOT NULL CHECK (message_id > 0),
    payload_fingerprint VARCHAR(64) NOT NULL CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    command_id BIGINT NOT NULL CHECK (command_id > 0),
    saga_id BIGINT NOT NULL CHECK (saga_id > 0),
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    buyer_id BIGINT NOT NULL CHECK (buyer_id > 0),
    request_fingerprint TEXT NOT NULL CHECK (btrim(request_fingerprint) <> ''),
    outcome VARCHAR(8) NOT NULL CHECK (outcome IN ('RECORDED', 'REJECTED')),
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (last_seen_at >= first_seen_at),
    delivery_count BIGINT NOT NULL DEFAULT 1 CHECK (delivery_count > 0),
    resolved_at TIMESTAMPTZ CHECK (resolved_at >= first_seen_at),
    resolution TEXT CHECK (btrim(resolution) <> ''),
    PRIMARY KEY (message_id, payload_fingerprint),
    CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);
CREATE INDEX orphan_results_unresolved_idx ON ordering.orphan_results (order_id) WHERE resolved_at IS NULL;
COMMENT ON TABLE ordering.orphan_results IS
    'Missing-order quarantine blocks order ID reuse; the SHA-256 of the complete canonical received payload distinguishes conflicting deliveries of one message ID.';

CREATE TABLE ordering.conflicting_results (
    message_id BIGINT NOT NULL CHECK (message_id > 0),
    payload_fingerprint VARCHAR(64) NOT NULL CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    command_id BIGINT NOT NULL CHECK (command_id > 0),
    saga_id BIGINT NOT NULL CHECK (saga_id > 0),
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    buyer_id BIGINT NOT NULL CHECK (buyer_id > 0),
    request_fingerprint TEXT NOT NULL CHECK (btrim(request_fingerprint) <> ''),
    outcome VARCHAR(8) NOT NULL CHECK (outcome IN ('RECORDED', 'REJECTED')),
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    expected_identity JSONB NOT NULL CHECK (jsonb_typeof(expected_identity) = 'object'),
    received_identity JSONB NOT NULL CHECK (jsonb_typeof(received_identity) = 'object'),
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP CHECK (last_seen_at >= first_seen_at),
    delivery_count BIGINT NOT NULL DEFAULT 1 CHECK (delivery_count > 0),
    resolved_at TIMESTAMPTZ CHECK (resolved_at >= first_seen_at),
    resolution TEXT CHECK (btrim(resolution) <> ''),
    PRIMARY KEY (message_id, payload_fingerprint),
    CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);
CREATE INDEX conflicting_results_unresolved_idx ON ordering.conflicting_results (order_id) WHERE resolved_at IS NULL;
COMMENT ON TABLE ordering.conflicting_results IS
    'Retains received envelope and expected/received correlation, fingerprint and outcome even after transport cleanup; resolution is an operator action.';

-- Debezium JDBC offset-store column contract; this is connector metadata, not Snowflake IDs.
-- Configure offset.storage.jdbc.table.name=ordering.debezium_offset_storage; no runtime DDL.
CREATE TABLE ordering.debezium_offset_storage (
    id VARCHAR(36) PRIMARY KEY,
    offset_key VARCHAR(1255),
    offset_val VARCHAR(1255),
    record_insert_ts TIMESTAMP NOT NULL,
    record_insert_seq INTEGER NOT NULL
);
