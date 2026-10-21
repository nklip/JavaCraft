-- Retained orders keep their original monetary semantics and command version.
-- New application accepts only policy-compliant catalog prices and writes version 2 explicitly.
ALTER TABLE ordering.orders
    ADD COLUMN currency_policy_version INTEGER NOT NULL DEFAULT 1
    CHECK (currency_policy_version IN (1, 2));

COMMENT ON COLUMN ordering.orders.currency_policy_version IS
    '1: retained two-decimal money semantics; 2: seller-country and ISO 4217 minor-unit policy. Immutable after acceptance.';
