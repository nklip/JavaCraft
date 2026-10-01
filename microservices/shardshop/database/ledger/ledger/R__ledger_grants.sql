-- Complete runtime SQL object grants; CNPG declares role attributes and memberships.
-- The database bootstrap owner is distinct from this schema's non-login owner.
REVOKE ALL ON SCHEMA ledger FROM PUBLIC, ledger_writer;
REVOKE ALL ON ALL TABLES IN SCHEMA ledger FROM PUBLIC, ledger_writer;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC, ledger_writer;

GRANT USAGE ON SCHEMA ledger TO ledger_writer;
GRANT SELECT ON ledger.ledger_operations, ledger.ledger_entries,
    ledger.ledger_result_ids, ledger.ledger_inbox, ledger.ledger_outbox,
    ledger.conflicting_commands TO ledger_writer;
GRANT INSERT ON ledger.ledger_operations, ledger.ledger_entries,
    ledger.ledger_result_ids, ledger.ledger_inbox TO ledger_writer;
-- New/recreated outbox rows start pending with a database-assigned creation time.
GRANT INSERT (message_id, publication_attempt) ON ledger.ledger_outbox TO ledger_writer;
-- Runtime can report incidents, but cannot insert them already resolved.
GRANT INSERT (message_id, payload_fingerprint, result_message_id, command_id, saga_id,
    order_id, buyer_id, request_fingerprint, envelope, expected_identity, received_identity,
    reason, first_seen_at, last_seen_at, delivery_count) ON ledger.conflicting_commands TO ledger_writer;
GRANT UPDATE (publication_attempt) ON ledger.ledger_result_ids TO ledger_writer;
GRANT UPDATE (publication_attempt, published_at) ON ledger.ledger_outbox TO ledger_writer;
-- Java enforces confirmed publication and retention gates before transport cleanup.
GRANT DELETE ON ledger.ledger_inbox, ledger.ledger_outbox TO ledger_writer;
GRANT UPDATE (last_seen_at, delivery_count) ON ledger.conflicting_commands TO ledger_writer;
