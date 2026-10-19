@order-producer
Feature: Packaged order producer against the service APIs and PostgreSQL shards
  The producer waits for the seeded catalog, creates all buyers through order's API, and keeps one
  allocated order in flight until its status is terminal. This scenario controls only the terminal
  transition: the ledger and result relay are covered by their own packaged scenarios. An HTTP proxy
  hides the first committed buyer, allocation and PUT responses to check safe retries.

  Scenario: Catalog gate, complete buyer creation, deterministic replay, currencies, and terminal polling
    Given the order producer has migrated regional primaries and packaged providers
    When the USD order producer starts before catalog seeding
    Then it waits for the complete catalog without creating buyers or orders
    When the seeder runs
    Then the seeder exits with code 0 and logs the summary "US 50 sellers, 100 products {USD=50, EUR=50}; EU 50 sellers, 100 products {USD=50, EUR=50}; ASIA 50 sellers, 100 products {USD=50, EUR=50}"
    And the producer creates all 3000 buyers and submits a USD order with one item from each region
    When the producer stops and restarts with the same run name and percentage
    Then its pending first order and allocation are unchanged and no second ordinal was allocated
    When the first USD order becomes confirmed
    Then the producer advances to ordinal 1 only after observing the terminal order
    When the producer stops and a separate EUR run starts
    Then it submits a EUR order with one item from each region and reuses the 3000 buyers
    When the first EUR order becomes cancelled
    Then the EUR run advances to ordinal 1 only after observing the terminal order


  Scenario: An invalid setting stops the producer at startup with one error line
    When the order producer checks its startup with a blank run name
    Then the startup check exits with code 1 and logs one error "Invalid run name"
