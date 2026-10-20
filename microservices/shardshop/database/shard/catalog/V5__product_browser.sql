-- The browser aggregates only the bounded page's products; avoid scanning all historical sales per product.
CREATE INDEX profit_credits_product_currency_idx
    ON catalog.profit_credits (product_id, currency) INCLUDE (amount);
