-- This history survives offset/slot loss and transport cleanup. The JDBC offset
-- store inserts it in the same transaction as its first durable checkpoint.
CREATE TABLE ordering.cdc_reader_state (
    reader_name VARCHAR(63) PRIMARY KEY,
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE ordering.cdc_reader_state IS
    'Permanent CDC initialization history; missing slots or offsets require explicit reconciliation, never an automatic fresh snapshot.';
