-- Complete runtime SQL object grants; CNPG declares role attributes and memberships.
-- Only groups receive grants; no SQL access to history.
REVOKE ALL ON SCHEMA ordering FROM PUBLIC, ordering_writer, ordering_cdc_reader;
REVOKE ALL ON ALL TABLES IN SCHEMA ordering FROM PUBLIC, ordering_writer, ordering_cdc_reader;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ordering FROM PUBLIC, ordering_writer, ordering_cdc_reader;

GRANT USAGE ON SCHEMA ordering TO ordering_writer, ordering_cdc_reader;
GRANT SELECT, INSERT ON ordering.buyers, ordering.order_allocations, ordering.orders,
    ordering.order_items, ordering.order_sagas, ordering.order_result_ids,
    ordering.order_outbox, ordering.order_inbox, ordering.orphan_results,
    ordering.conflicting_results TO ordering_writer;
GRANT UPDATE (status, updated_at) ON ordering.orders TO ordering_writer;
GRANT UPDATE (status, cancellation_reason, stock_released_at, reconciliation_attempts,
    next_reconciliation_at, updated_at) ON ordering.order_sagas TO ordering_writer;
GRANT UPDATE (published_at) ON ordering.order_outbox TO ordering_writer;
-- Only disposable transport rows can be deleted. Java enforces the checkpoint/window gate.
GRANT DELETE ON ordering.order_outbox, ordering.order_inbox TO ordering_writer;
GRANT UPDATE (last_seen_at, delivery_count) ON ordering.orphan_results, ordering.conflicting_results TO ordering_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON ordering.debezium_offset_storage TO ordering_writer;
GRANT SELECT ON ordering.order_outbox TO ordering_cdc_reader;
