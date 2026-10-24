-- Complete runtime SQL object grants; CNPG declares role attributes and memberships.
-- Only groups receive grants; no runtime SQL access to Flyway migration history.
REVOKE ALL ON SCHEMA ordering FROM PUBLIC, ordering_writer, ordering_cdc_reader;
REVOKE ALL ON ALL TABLES IN SCHEMA ordering FROM PUBLIC, ordering_writer, ordering_cdc_reader;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ordering FROM PUBLIC, ordering_writer, ordering_cdc_reader;
-- Table revokes do not clear column privileges left by a previous repeatable run.
REVOKE ALL (ledger_result) ON ordering.order_sagas FROM PUBLIC, ordering_writer, ordering_cdc_reader;
REVOKE ALL (received_envelope) ON ordering.order_result_ids FROM PUBLIC, ordering_writer, ordering_cdc_reader;
REVOKE ALL (order_id, item_position, seller_id, product_id, currency, amount, created_at,
    applied_at, failures, next_attempt_at, blocked_reason) ON ordering.profit_intents
    FROM PUBLIC, ordering_writer, ordering_cdc_reader;

GRANT USAGE ON SCHEMA ordering TO ordering_writer, ordering_cdc_reader;
GRANT SELECT ON ordering.buyers, ordering.order_allocations, ordering.orders,
    ordering.order_items, ordering.order_sagas, ordering.order_result_ids,
    ordering.order_outbox, ordering.order_inbox, ordering.orphan_results,
    ordering.conflicting_results, ordering.profit_intents TO ordering_writer;
GRANT INSERT ON ordering.buyers, ordering.order_allocations, ordering.orders,
    ordering.order_items, ordering.order_sagas, ordering.order_result_ids,
    ordering.order_inbox TO ordering_writer;
-- New outbox rows start pending with a database-assigned creation time.
GRANT INSERT (message_id, result_message_id, saga_id, envelope) ON ordering.order_outbox TO ordering_writer;
-- Quarantine starts unresolved, including orphan results that block order ID reuse.
GRANT INSERT (message_id, payload_fingerprint, command_id, saga_id, order_id, buyer_id,
    request_fingerprint, outcome, envelope, reason, first_seen_at, last_seen_at,
    delivery_count) ON ordering.orphan_results TO ordering_writer;
GRANT INSERT (message_id, payload_fingerprint, command_id, saga_id, order_id, buyer_id,
    request_fingerprint, outcome, envelope, expected_identity, received_identity, reason,
    first_seen_at, last_seen_at, delivery_count) ON ordering.conflicting_results TO ordering_writer;
GRANT INSERT (order_id, item_position, seller_id, product_id, currency, amount)
    ON ordering.profit_intents TO ordering_writer;
GRANT UPDATE (status, updated_at) ON ordering.orders TO ordering_writer;
GRANT UPDATE (status, cancellation_reason, stock_released_at, reconciliation_attempts,
    next_reconciliation_at, updated_at, stock_lease_version, stock_lease_until,
    stock_failures, next_stock_attempt_at, ledger_result) ON ordering.order_sagas TO ordering_writer;
GRANT UPDATE (received_envelope) ON ordering.order_result_ids TO ordering_writer;
GRANT UPDATE (applied_at, failures, next_attempt_at, blocked_reason)
    ON ordering.profit_intents TO ordering_writer;
GRANT UPDATE (published_at) ON ordering.order_outbox TO ordering_writer;
-- Only disposable transport rows can be deleted. Java enforces the checkpoint/window gate.
GRANT DELETE ON ordering.order_outbox, ordering.order_inbox TO ordering_writer;
GRANT UPDATE (last_seen_at, delivery_count) ON ordering.orphan_results, ordering.conflicting_results TO ordering_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordering.debezium_offset_storage TO ordering_writer;
GRANT SELECT ON ordering.cdc_reader_state TO ordering_writer;
GRANT INSERT (reader_name) ON ordering.cdc_reader_state TO ordering_writer;
GRANT SELECT ON ordering.order_outbox TO ordering_cdc_reader;

-- Human-operated reconciliation reset. No application is a member of this group.
REVOKE ALL ON SCHEMA ordering FROM ordering_reconciler;
REVOKE ALL ON ALL TABLES IN SCHEMA ordering FROM ordering_reconciler;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ordering FROM ordering_reconciler;
REVOKE ALL (order_id, buyer_id, request_fingerprint, currency, total_amount, status,
    created_at, updated_at, currency_policy_version) ON ordering.orders FROM ordering_reconciler;
REVOKE ALL (saga_id, order_id, command_id, status, cancellation_reason, stock_released_at,
    reconciliation_attempts, created_at, updated_at, next_reconciliation_at,
    stock_lease_version, stock_lease_until, stock_failures, next_stock_attempt_at,
    ledger_result) ON ordering.order_sagas FROM ordering_reconciler;
GRANT USAGE ON SCHEMA ordering TO ordering_reconciler;
GRANT SELECT (order_id, status) ON ordering.orders TO ordering_reconciler;
GRANT SELECT (saga_id, order_id, status, reconciliation_attempts, next_reconciliation_at)
    ON ordering.order_sagas TO ordering_reconciler;
GRANT UPDATE (reconciliation_attempts, next_reconciliation_at)
    ON ordering.order_sagas TO ordering_reconciler;
