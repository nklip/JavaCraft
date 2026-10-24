-- Run with psql -X -f, as ordering_operator, on the selected buyer shard's primary.
-- Supply explicit order_id and saga_id variables. No wildcard or all-sagas mode.
\set ON_ERROR_STOP on
\set QUIET on
\set VERBOSITY terse
\if :{?order_id}
\else
SELECT 'order_id is required'::bigint;
\endif
\if :{?saga_id}
\else
SELECT 'saga_id is required'::bigint;
\endif

-- Quoted psql substitutions are SQL literals, never SQL source. Validate before casting or locking.
SELECT :'order_id' ~ '^[1-9][0-9]{0,18}$' AND :'order_id' !~ '[^0-9]'
       AND (length(:'order_id') < 19 OR :'order_id' COLLATE "C" <= '9223372036854775807')
       AND :'saga_id' ~ '^[1-9][0-9]{0,18}$' AND :'saga_id' !~ '[^0-9]'
       AND (length(:'saga_id') < 19 OR :'saga_id' COLLATE "C" <= '9223372036854775807') AS valid_ids
\gset
\if :valid_ids
\else
SELECT 'order_id and saga_id must be canonical positive BIGINT values'::bigint;
\endif

BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL statement_timeout = '3s';
SET LOCAL lock_timeout = '2s';
SET LOCAL idle_in_transaction_session_timeout = '5s';
SET LOCAL transaction_timeout = '10s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL search_path = pg_catalog;

-- Same signed low 64 SHA-256 bits as the Java order lock: UTF-8("order\0\0" + orderId).
-- A separate statement ensures the state check gets a fresh snapshot after any lock wait.
SELECT pg_advisory_xact_lock(('x' || right(encode(sha256(
    convert_to('order', 'UTF8') || decode('0000', 'hex') || convert_to(:'order_id', 'UTF8')
), 'hex'), 16))::bit(64)::bigint) AS order_lock
\gset

WITH candidate AS MATERIALIZED (
    SELECT s.saga_id, s.reconciliation_attempts AS previous_attempts
    FROM ordering.order_sagas s JOIN ordering.orders o ON o.order_id = s.order_id
    WHERE s.saga_id = :'saga_id'::bigint AND s.order_id = :'order_id'::bigint
      AND s.status = 'PENDING_LEDGER' AND o.status = 'PENDING_LEDGER'
    FOR UPDATE OF s
), reset AS (
    UPDATE ordering.order_sagas s
    SET reconciliation_attempts = 0, next_reconciliation_at = clock_timestamp()
    FROM candidate c WHERE s.saga_id = c.saga_id AND s.status = 'PENDING_LEDGER'
    RETURNING c.previous_attempts, s.next_reconciliation_at
)
SELECT COALESCE((SELECT 'RESET|' || :'order_id' || '|' || :'saga_id' || '|'
    || previous_attempts::text || '|' || next_reconciliation_at::text FROM reset),
    'NOOP|' || :'order_id' || '|' || :'saga_id') AS reset_result
\gset
COMMIT;

-- Do not report success before the transaction commits.
\echo :reset_result
