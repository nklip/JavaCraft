-- Permanent business decisions: transport IDs do not participate in this identity.
CREATE TABLE ledger.ledger_operations (
    order_id BIGINT PRIMARY KEY CHECK (order_id > 0),
    buyer_id BIGINT NOT NULL CHECK (buyer_id > 0),
    saga_id BIGINT NOT NULL UNIQUE CHECK (saga_id > 0),
    command_id BIGINT NOT NULL UNIQUE CHECK (command_id > 0),
    request_fingerprint TEXT NOT NULL CHECK (btrim(request_fingerprint) <> ''),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount >= 0 AND amount <> 'NaN'::numeric),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    outcome VARCHAR(8) NOT NULL CHECK (outcome IN ('RECORDED', 'REJECTED')),
    rejection_reason TEXT CHECK (btrim(rejection_reason) <> ''),
    result_schema_version INTEGER NOT NULL CHECK (result_schema_version > 0),
    result_data JSONB NOT NULL CHECK (jsonb_typeof(result_data) = 'object'),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((outcome = 'REJECTED') = (rejection_reason IS NOT NULL)),
    UNIQUE (order_id, outcome, amount, currency, decided_at)
);
COMMENT ON TABLE ledger.ledger_operations IS
    'Immutable, permanent decisions including rejections, logical correlation, fingerprint, snapshot and saved result data. Replay never reevaluates policy; transport identities are stored separately.';

CREATE TABLE ledger.ledger_entries (
    order_id BIGINT PRIMARY KEY,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    outcome VARCHAR(8) NOT NULL DEFAULT 'RECORDED' CHECK (outcome = 'RECORDED'),
    recorded_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (order_id, outcome, amount, currency, recorded_at)
        REFERENCES ledger.ledger_operations (order_id, outcome, amount, currency, decided_at)
);
COMMENT ON TABLE ledger.ledger_entries IS
    'One permanent entry per recorded order, with amount, currency and timestamp matching its decision; rejected decisions cannot have entries.';

-- Order supplies both transport IDs. Retain this binding and exact result envelope
-- after transport cleanup, including the counter used to fence stale confirms.
CREATE TABLE ledger.ledger_result_ids (
    result_message_id BIGINT PRIMARY KEY CHECK (result_message_id > 0),
    message_id BIGINT NOT NULL UNIQUE CHECK (message_id > 0),
    order_id BIGINT NOT NULL REFERENCES ledger.ledger_operations (order_id),
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publication_attempt BIGINT NOT NULL DEFAULT 1 CHECK (publication_attempt > 0),
    CHECK (message_id <> result_message_id),
    UNIQUE (message_id, result_message_id),
    UNIQUE (result_message_id, publication_attempt)
);
CREATE INDEX ledger_result_ids_order_id_idx ON ledger.ledger_result_ids (order_id);
COMMENT ON TABLE ledger.ledger_result_ids IS
    'Permanent command/result transport binding and immutable result envelope with original timestamps. Java atomically increments publication_attempt and rearms/recreates the outbox on duplicates, without generating IDs.';

CREATE TABLE ledger.ledger_inbox (
    message_id BIGINT PRIMARY KEY,
    result_message_id BIGINT NOT NULL UNIQUE,
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (message_id, result_message_id)
        REFERENCES ledger.ledger_result_ids (message_id, result_message_id)
);
COMMENT ON TABLE ledger.ledger_inbox IS
    'Disposable command delivery deduplication; an inbox hit must still rearm the permanent decision result.';

CREATE TABLE ledger.ledger_outbox (
    message_id BIGINT PRIMARY KEY,
    publication_attempt BIGINT NOT NULL CHECK (publication_attempt > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ CHECK (published_at >= created_at),
    FOREIGN KEY (message_id, publication_attempt)
        REFERENCES ledger.ledger_result_ids (result_message_id, publication_attempt)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX ledger_outbox_pending_idx ON ledger.ledger_outbox (created_at, message_id)
    WHERE published_at IS NULL;
COMMENT ON TABLE ledger.ledger_outbox IS
    'Pending result publication; join message_id to ledger_result_ids.result_message_id for the saved envelope. The deferred key requires the current durable attempt at commit. Mark published only WHERE message_id and publication_attempt match the sent attempt; cleanup only confirmed rows after retention.';

-- No foreign keys: conflicting transport/logical identities must remain recordable.
CREATE TABLE ledger.conflicting_commands (
    message_id BIGINT NOT NULL CHECK (message_id > 0),
    payload_fingerprint VARCHAR(64) NOT NULL CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    result_message_id BIGINT NOT NULL CHECK (result_message_id > 0),
    command_id BIGINT NOT NULL CHECK (command_id > 0),
    saga_id BIGINT NOT NULL CHECK (saga_id > 0),
    order_id BIGINT NOT NULL CHECK (order_id > 0),
    buyer_id BIGINT NOT NULL CHECK (buyer_id > 0),
    request_fingerprint TEXT NOT NULL CHECK (btrim(request_fingerprint) <> ''),
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
CREATE INDEX conflicting_commands_unresolved_idx ON ledger.conflicting_commands (order_id) WHERE resolved_at IS NULL;
COMMENT ON TABLE ledger.conflicting_commands IS
    'Commit conflicting commands before acknowledgement without changing the permanent decision or emitting a rejection. Hash the complete canonical received payload to preserve different conflicts sharing a message ID. Retain unresolved records through cleanup and backups; resolution is an operator action.';
