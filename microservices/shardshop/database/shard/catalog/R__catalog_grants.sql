-- The catalog's complete privilege matrix; Flyway reapplies this file whenever it changes.
-- Grants go to group roles only; login roles receive them through CNPG role membership.
REVOKE ALL ON SCHEMA catalog FROM PUBLIC, catalog_reader, catalog_writer, catalog_reserver;
REVOKE ALL ON ALL TABLES IN SCHEMA catalog FROM PUBLIC, catalog_reader, catalog_writer, catalog_reserver;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA catalog FROM PUBLIC, catalog_reader, catalog_writer, catalog_reserver;
-- Table-level REVOKE does not remove column grants from earlier repeatable runs.
-- Remove the earlier reserver profit grants too; only product's zero initialization
-- is enabled until the confirmation protocol in PLAN step 4.6 is implemented.
REVOKE ALL (seller_id, currency, amount) ON catalog.seller_profits
    FROM PUBLIC, catalog_reader, catalog_writer, catalog_reserver;
REVOKE ALL (order_id, product_id, seller_id, currency, amount, credited_at) ON catalog.profit_credits
    FROM PUBLIC, catalog_reader, catalog_writer, catalog_reserver;

GRANT USAGE ON SCHEMA catalog TO catalog_reader, catalog_writer, catalog_reserver;
GRANT SELECT ON catalog.sellers, catalog.products, catalog.seller_profits TO catalog_reader;
GRANT SELECT, INSERT ON catalog.sellers, catalog.products TO catalog_writer;
GRANT SELECT ON catalog.seller_profits TO catalog_writer;
-- Creation initializes currencies at the database default; product cannot set/reset profits.
GRANT INSERT (seller_id, currency) ON catalog.seller_profits TO catalog_writer;
-- Reservation history must remain available for idempotent reserve and release.
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.stock_reservations TO catalog_reserver;
-- Java updates stock and reservations in the same transaction (PLAN step 4.9).
GRANT UPDATE (stock) ON catalog.products TO catalog_reserver;
