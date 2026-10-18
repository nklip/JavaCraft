-- Preserve the entire command transport identity after disposable inbox cleanup.
-- A retained legacy binding without its original command needs reconciliation;
-- SET NOT NULL deliberately stops this migration rather than inventing that payload.
ALTER TABLE ledger.ledger_result_ids ADD COLUMN command_envelope JSONB;

UPDATE ledger.ledger_result_ids AS binding
SET command_envelope = inbox.envelope
FROM ledger.ledger_inbox AS inbox
WHERE inbox.message_id = binding.message_id
  AND inbox.result_message_id = binding.result_message_id;

ALTER TABLE ledger.ledger_result_ids
    ALTER COLUMN command_envelope SET NOT NULL,
    ADD CONSTRAINT ledger_result_ids_command_object CHECK (jsonb_typeof(command_envelope) = 'object');

COMMENT ON COLUMN ledger.ledger_result_ids.command_envelope IS
    'Permanent original command envelope; same transport IDs must retain every field, including occurredAt, through inbox/outbox cleanup.';
