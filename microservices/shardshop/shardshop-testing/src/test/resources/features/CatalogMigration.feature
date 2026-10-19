@catalog-migration
Feature: Catalog migrations with the Flyway Java API
  The database layer owns the catalog schema. Flyway 13.8.1 applies database/shard/catalog as catalog_migrator,
  which acts as catalog_owner, with the settings of the stream's flyway.toml. The roles, the memberships, the
  database hardening and the schema owner are the same as on the lab. The test database is the only shard of the
  region US, thus every seller ID agrees with its placement check.

  Scenario: Flyway upgrades the V1 rows to the V2 company, product and profit model
    Given a new shard database with only V1 of the catalog stream
    And catalog_owner adds these rows:
      """
      INSERT INTO catalog.sellers(seller_id, name) VALUES (1, 'Legacy company');
      INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock)
          VALUES (11, 1, 'Legacy item', 19.95, 'USD', 10, 8)
      """
    When Flyway migrates the catalog stream
    Then Flyway applies the migrations "2 catalog creation, 3 stock coordination, 4 profit posting, catalog grants"
    And catalog_reader reads these rows:
      | query                                                                                                     | rows                    |
      | SELECT company_name, creation_key IS NULL FROM catalog.sellers WHERE seller_id = 1                         | Legacy company,t        |
      | SELECT description, unit_cost, initial_stock, stock, creation_key IS NULL FROM catalog.products WHERE product_id = 11 | Legacy item,0.00,10,8,t |
      | SELECT currency, amount FROM catalog.seller_profits WHERE seller_id = 1 ORDER BY currency                  | EUR,0.00;USD,0.00       |
    And a second Flyway migration validates the history and applies no migration
    And catalog_owner owns the catalog schema and all its tables and indexes
    And the catalog schema has no functions and no triggers

  Scenario: The constraints keep creation keys, product details and profit identities valid
    Given a new shard database that Flyway migrated
    And catalog_writer adds these rows:
      """
      INSERT INTO catalog.sellers(seller_id, name, creation_key)
          VALUES (2, 'Current company', 'catalog-v1.US.seller.1'), (3, 'Other company', 'other');
      INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'USD'), (2, 'EUR'), (3, 'USD'), (3, 'EUR');
      INSERT INTO catalog.products
          (product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock, creation_key)
          VALUES (12, 2, 'Current item', 'A complete description', 19.95, 12.50, 'USD', 3, 3, 'item.1'),
                 (13, 3, 'Other item', 'Another complete description', 19.95, 12.50, 'EUR', 3, 3, 'item.1')
      """
    Then catalog_writer reads these rows:
      | query                                                                                                    | rows              |
      | SELECT seller_id, company_name FROM catalog.sellers WHERE region = 'US' AND creation_key = 'catalog-v1.US.seller.1' | 2,Current company |
      | SELECT product_id FROM catalog.products WHERE seller_id = 2 AND creation_key = 'item.1'                    | 12                |
      | SELECT product_id FROM catalog.products WHERE seller_id = 3 AND creation_key = 'item.1'                    | 13                |
    And these statements fail with their SQLSTATE:
      | role           | statement                                                                                                                   | sqlstate |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (4, 'Duplicate company', 'catalog-v1.US.seller.1')        | 23505    |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (14, 2, 'Duplicate item', 1.00, 'USD', 1, 1, 'item.1') | 23505 |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Rejected company', '')                              | 23514    |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Rejected company', 'bad key')                       | 23514    |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Rejected company', '-bad')                          | 23514    |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Rejected company', E'bad\\n')                       | 23514    |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Rejected company', 'é')                             | 23514    |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Rejected item', 1.00, 'USD', 1, 1, '') | 23514 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Rejected item', 1.00, 'USD', 1, 1, 'bad key') | 23514 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Rejected item', 1.00, 'USD', 1, 1, '-bad') | 23514 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Rejected item', 1.00, 'USD', 1, 1, E'bad\\n') | 23514 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Rejected item', 1.00, 'USD', 1, 1, 'é') | 23514 |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, company_name) VALUES (50, 'Rejected company', 'Separate company')              | 428C9    |
      | catalog_writer | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Boundary company', '{repeat:K:129}')                | 22001    |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Boundary item', 1.00, 'USD', 1, 1, '{repeat:K:129}') | 22001 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock) VALUES (51, 2, 'Rejected item', '', 1.00, 0.00, 'USD', 1, 1) | 23514 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock) VALUES (51, 2, 'Rejected item', NULL, 1.00, 0.00, 'USD', 1, 1) | 23502 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock) VALUES (51, 2, 'Rejected item', 'Valid', 1.00, -0.01, 'USD', 1, 1) | 23514 |
      | catalog_writer | INSERT INTO catalog.products(product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock) VALUES (51, 2, 'Rejected item', 'Valid', 1.00, 'NaN', 'USD', 1, 1) | 23514 |
    And catalog_writer reads these rows after these statements:
      | statements                                                                                                                       | rows           |
      | INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (50, 'Boundary company', '{repeat:K:128}'); SELECT length(creation_key) FROM catalog.sellers WHERE seller_id = 50 | 128 |
      | INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock, creation_key) VALUES (50, 2, 'Boundary item', 1.00, 'USD', 1, 1, '{repeat:K:128}'); SELECT length(creation_key) FROM catalog.products WHERE product_id = 50 | 128 |
      | INSERT INTO catalog.sellers(seller_id, name) VALUES (4, 'Legacy second company'); INSERT INTO catalog.products(product_id, seller_id, name, price, currency, initial_stock, stock) VALUES (14, 4, 'Legacy second item', 1.00, 'USD', 1, 1); SELECT description, unit_cost FROM catalog.products WHERE product_id = 14 | Legacy product,0.00 |

  Scenario: Profit credits keep one identity per order item and seller balances allow signed amounts
    Given a new shard database that Flyway migrated
    And catalog_writer adds these rows:
      """
      INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (2, 'Current company', 'current'), (3, 'Other company', 'other');
      INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'USD'), (2, 'EUR'), (3, 'USD'), (3, 'EUR');
      INSERT INTO catalog.products
          (product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock, creation_key)
          VALUES (12, 2, 'Current item', 'A complete description', 19.95, 12.50, 'USD', 3, 3, 'item.1')
      """
    And catalog_owner adds these rows:
      """
      INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (101, 12, 2, 'USD', -2.50);
      UPDATE catalog.seller_profits SET amount = amount - 2.50 WHERE seller_id = 2 AND currency = 'USD';
      INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'JPY')
      """
    Then catalog_writer reads these rows:
      | query                                                                                     | rows                         |
      | SELECT currency, amount FROM catalog.seller_profits WHERE seller_id = 2 ORDER BY currency | EUR,0.00;JPY,0.00;USD,-2.50  |
    And order_app reads these rows:
      | query                                                                                     | rows                         |
      | SELECT currency, amount FROM catalog.seller_profits WHERE seller_id = 2 ORDER BY currency | EUR,0.00;JPY,0.00;USD,-2.50  |
    And these statements fail with their SQLSTATE:
      | role          | statement                                                                                                        | sqlstate |
      | catalog_owner | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (101, 12, 2, 'USD', -2.50) | 23505 |
      | catalog_owner | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (102, 12, 3, 'USD', 1.00)  | 23503 |
      | catalog_owner | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (102, 12, 2, 'EUR', 1.00)  | 23503 |
      | catalog_owner | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (102, 999, 2, 'USD', 1.00) | 23503 |
      | catalog_owner | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (0, 12, 2, 'USD', 1.00)    | 23514 |
      | catalog_owner | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (102, 12, 2, 'USD', 'NaN') | 23514 |
      | catalog_owner | UPDATE catalog.seller_profits SET amount = 'NaN' WHERE seller_id = 2 AND currency = 'USD'                        | 23514    |

  Scenario: Each role has only the privileges of the role matrix
    Given a new shard database that Flyway migrated
    And catalog_writer adds these rows:
      """
      INSERT INTO catalog.sellers(seller_id, name, creation_key) VALUES (2, 'Current company', 'current');
      INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'USD'), (2, 'EUR');
      INSERT INTO catalog.products
          (product_id, seller_id, name, description, price, unit_cost, currency, initial_stock, stock, creation_key)
          VALUES (12, 2, 'Current item', 'A complete description', 19.95, 12.50, 'USD', 3, 3, 'item.1')
      """
    And catalog_owner adds these rows:
      """
      INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (101, 12, 2, 'USD', -2.50)
      """
    Then these statements fail with their SQLSTATE:
      | role             | statement                                                                                       | sqlstate |
      | catalog_writer   | INSERT INTO catalog.seller_profits(seller_id, currency, amount) VALUES (2, 'GBP', 100)          | 42501    |
      | catalog_writer   | UPDATE catalog.seller_profits SET amount = 0 WHERE seller_id = 2                                | 42501    |
      | catalog_writer   | DELETE FROM catalog.seller_profits WHERE seller_id = 2                                          | 42501    |
      | catalog_writer   | TRUNCATE catalog.seller_profits                                                                 | 42501    |
      | catalog_writer   | UPDATE catalog.seller_profits SET currency = 'GBP' WHERE seller_id = 2                          | 42501    |
      | catalog_writer   | UPDATE catalog.products SET stock = 3 WHERE product_id = 12                                     | 42501    |
      | catalog_writer   | DELETE FROM catalog.sellers WHERE seller_id = 2                                                 | 42501    |
      | catalog_writer   | UPDATE catalog.sellers SET creation_key = 'changed' WHERE seller_id = 2                         | 42501    |
      | catalog_writer   | SELECT * FROM catalog.profit_credits                                                            | 42501    |
      | catalog_writer   | SELECT * FROM catalog.stock_reservations                                                        | 42501    |
      | catalog_reserver | INSERT INTO catalog.seller_profits(seller_id, currency, amount) VALUES (2, 'GBP', 1.00)         | 42501    |
      | catalog_reserver | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount, credited_at) VALUES (102, 12, 2, 'USD', 1.00, CURRENT_TIMESTAMP) | 42501 |
      | catalog_reserver | UPDATE catalog.profit_credits SET amount = 100 WHERE order_id = 101                             | 42501    |
      | catalog_reserver | DELETE FROM catalog.profit_credits WHERE order_id = 101                                         | 42501    |
      | catalog_reserver | TRUNCATE catalog.profit_credits                                                                 | 42501    |
      | catalog_reserver | TRUNCATE catalog.stock_reservations                                                             | 42501    |
      | order_app        | INSERT INTO catalog.seller_profits(seller_id, currency, amount) VALUES (2, 'GBP', 100)          | 42501    |
      | order_app        | DELETE FROM catalog.seller_profits WHERE seller_id = 2                                          | 42501    |
      | order_app        | UPDATE catalog.seller_profits SET currency = 'GBP' WHERE seller_id = 2                          | 42501    |
      | order_app        | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount, credited_at) VALUES (102, 12, 2, 'USD', 1.00, CURRENT_TIMESTAMP) | 42501 |
      | order_app        | UPDATE catalog.profit_credits SET amount = 100 WHERE order_id = 101                             | 42501    |
      | order_app        | DELETE FROM catalog.profit_credits WHERE order_id = 101                                         | 42501    |
      | order_app        | TRUNCATE catalog.profit_credits                                                                 | 42501    |
      | order_app        | UPDATE catalog.products SET price = 1.00 WHERE product_id = 12                                  | 42501    |
      | order_app        | INSERT INTO catalog.sellers(seller_id, name) VALUES (5, 'Order company')                        | 42501    |
      | order_app        | TRUNCATE catalog.stock_reservations                                                             | 42501    |
      | catalog_reader   | INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (2, 'GBP')                       | 42501    |
      | catalog_reader   | SELECT * FROM catalog.profit_credits                                                            | 42501    |
      | product_app      | SELECT * FROM catalog.flyway_schema_history                                                     | 42501    |
      | order_app        | SELECT * FROM catalog.flyway_schema_history                                                     | 42501    |
      | product_app      | CREATE TABLE catalog.product_app_table (id BIGINT)                                              | 42501    |
    And order_app reads these rows after these statements:
      | statements                                                                                                                       | rows  |
      | UPDATE catalog.products SET stock = stock - 1 WHERE product_id = 12; INSERT INTO catalog.stock_reservations(order_id, product_id, quantity) VALUES (201, 12, 1); SELECT stock FROM catalog.products WHERE product_id = 12 | 2 |
      | INSERT INTO catalog.stock_reservations(order_id, product_id, quantity) VALUES (201, 12, 1); UPDATE catalog.stock_reservations SET released_at = CURRENT_TIMESTAMP WHERE order_id = 201 AND product_id = 12 AND released_at IS NULL; SELECT released_at IS NOT NULL FROM catalog.stock_reservations WHERE order_id = 201 | t |
      | INSERT INTO catalog.stock_reservations(order_id, product_id, quantity) VALUES (201, 12, 1); DELETE FROM catalog.stock_reservations WHERE order_id = 201; SELECT count(*) FROM catalog.stock_reservations | 0 |
      | INSERT INTO catalog.profit_credits(order_id, product_id, seller_id, currency, amount) VALUES (102, 12, 2, 'USD', 1.00); UPDATE catalog.seller_profits SET amount = amount + 1.00 WHERE seller_id = 2 AND currency = 'USD'; SELECT amount FROM catalog.seller_profits WHERE seller_id = 2 AND currency = 'USD' | 1.00 |
    And the login roles have these catalog privileges:
      | role        | table                      | column   | privilege | granted |
      | product_app | catalog.sellers            |          | SELECT    | yes     |
      | product_app | catalog.sellers            |          | INSERT    | yes     |
      | product_app | catalog.products           |          | INSERT    | yes     |
      | product_app | catalog.seller_profits     |          | SELECT    | yes     |
      | product_app | catalog.seller_profits     | currency | INSERT    | yes     |
      | product_app | catalog.seller_profits     | amount   | INSERT    | no      |
      | product_app | catalog.seller_profits     | any      | UPDATE    | no      |
      | product_app | catalog.sellers            | any      | UPDATE    | no      |
      | product_app | catalog.products           | any      | UPDATE    | no      |
      | product_app | catalog.stock_reservations | any      | SELECT    | no      |
      | product_app | catalog.profit_credits     | any      | SELECT    | no      |
      | order_app   | catalog.sellers            |          | SELECT    | yes     |
      | order_app   | catalog.products           |          | SELECT    | yes     |
      | order_app   | catalog.seller_profits     |          | SELECT    | yes     |
      | order_app   | catalog.seller_profits     | any      | INSERT    | yes     |
      | order_app   | catalog.seller_profits     | amount   | INSERT    | no      |
      | order_app   | catalog.seller_profits     | amount   | UPDATE    | yes     |
      | order_app   | catalog.seller_profits     | currency | UPDATE    | no      |
      | order_app   | catalog.seller_profits     |          | DELETE    | no      |
      | order_app   | catalog.seller_profits     |          | TRUNCATE  | no      |
      | order_app   | catalog.profit_credits     | any      | SELECT    | yes     |
      | order_app   | catalog.profit_credits     | any      | INSERT    | yes     |
      | order_app   | catalog.profit_credits     | credited_at | INSERT | no      |
      | order_app   | catalog.profit_credits     | any      | UPDATE    | no      |
      | order_app   | catalog.profit_credits     |          | DELETE    | no      |
      | order_app   | catalog.profit_credits     |          | TRUNCATE  | no      |
      | order_app   | catalog.products           | stock    | UPDATE    | yes     |
      | order_app   | catalog.products           | price    | UPDATE    | no      |
      | order_app   | catalog.sellers            | any      | UPDATE    | no      |
      | order_app   | catalog.sellers            | any      | INSERT    | no      |
      | order_app   | catalog.stock_reservations |          | SELECT    | yes     |
      | order_app   | catalog.stock_reservations |          | INSERT    | yes     |
      | order_app   | catalog.stock_reservations |          | UPDATE    | yes     |
      | order_app   | catalog.stock_reservations |          | DELETE    | yes     |
      | order_app   | catalog.stock_reservations |          | TRUNCATE  | no      |
      | order_app   | catalog.stock_reservation_guards |          | SELECT   | yes     |
      | order_app   | catalog.stock_reservation_guards |          | INSERT   | yes     |
      | order_app   | catalog.stock_reservation_guards | closed   | UPDATE   | yes     |
      | order_app   | catalog.stock_reservation_guards | order_id | UPDATE   | no      |
      | order_app   | catalog.stock_reservation_guards |          | DELETE   | no      |
      | order_app   | catalog.stock_reservation_guards |          | TRUNCATE | no      |
      | product_app | catalog.stock_reservation_guards | any      | SELECT   | no      |
      | product_app | catalog.stock_reservation_guards | any      | INSERT   | no      |
      | product_app | catalog.stock_reservation_guards | any      | UPDATE   | no      |
      | product_app | catalog.stock_reservation_guards |          | DELETE   | no      |
      | product_app | catalog.stock_reservation_guards |          | TRUNCATE | no      |

  Scenario: Flyway reapplies a changed grants file, and the file removes stale privileges
    Given a new shard database that Flyway migrated
    And catalog_owner gives these privileges of an earlier grants file:
      """
      GRANT INSERT (amount), UPDATE (currency) ON catalog.seller_profits TO catalog_writer;
      GRANT SELECT ON catalog.seller_profits, catalog.profit_credits TO catalog_reserver;
      GRANT INSERT, UPDATE ON catalog.seller_profits TO catalog_reserver;
      GRANT INSERT (seller_id, currency), UPDATE (amount) ON catalog.seller_profits TO catalog_reserver;
      GRANT INSERT ON catalog.profit_credits TO catalog_reserver;
      GRANT INSERT (order_id, product_id, seller_id, currency, amount) ON catalog.profit_credits TO catalog_reserver;
      GRANT UPDATE (amount), INSERT (credited_at) ON catalog.profit_credits TO catalog_reserver
      """
    And the login roles have these catalog privileges:
      | role        | table                  | column | privilege | granted |
      | product_app | catalog.seller_profits | amount | INSERT    | yes     |
      | order_app   | catalog.seller_profits | amount | UPDATE    | yes     |
      | order_app   | catalog.profit_credits | amount | INSERT    | yes     |
    When Flyway migrates the catalog stream with a changed grants file
    Then Flyway applies the migrations "catalog grants"
    And the login roles have these catalog privileges:
      | role        | table                  | column | privilege | granted |
      | product_app | catalog.seller_profits | amount | INSERT    | no      |
      | product_app | catalog.seller_profits | any    | UPDATE    | no      |
      | order_app   | catalog.seller_profits | any    | INSERT    | yes     |
      | order_app   | catalog.seller_profits | amount | INSERT    | no      |
      | order_app   | catalog.seller_profits | amount | UPDATE    | yes     |
      | order_app   | catalog.seller_profits | currency | UPDATE  | no      |
      | order_app   | catalog.profit_credits | any    | SELECT    | yes     |
      | order_app   | catalog.profit_credits | any    | INSERT    | yes     |
      | order_app   | catalog.profit_credits | credited_at | INSERT | no      |
      | order_app   | catalog.profit_credits | any    | UPDATE    | no      |
