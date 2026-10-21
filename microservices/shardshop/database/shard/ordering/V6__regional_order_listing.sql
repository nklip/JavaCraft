-- Traverse either creation-date direction and its numeric ID tie-breaker without offset scans.
CREATE INDEX orders_created_at_order_id_idx ON ordering.orders (created_at, order_id);

-- Product IDs are globally unique; seller home regions do not constrain buyer-region listings.
CREATE INDEX order_items_product_id_order_id_idx ON ordering.order_items (product_id, order_id);
