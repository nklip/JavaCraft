-- Pending publications use the existing pending index; cleanup visits only confirmed rows.
CREATE INDEX ledger_inbox_retention_idx ON ledger.ledger_inbox (received_at, message_id);
CREATE INDEX ledger_outbox_retention_idx ON ledger.ledger_outbox (published_at, message_id)
    WHERE published_at IS NOT NULL;
