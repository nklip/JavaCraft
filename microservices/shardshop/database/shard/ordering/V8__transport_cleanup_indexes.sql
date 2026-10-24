-- Keep retention scans bounded without scanning the current transport window.
CREATE INDEX order_inbox_retention_idx ON ordering.order_inbox (received_at, message_id);
CREATE INDEX order_outbox_retention_idx ON ordering.order_outbox (published_at, message_id)
    WHERE published_at IS NOT NULL;
