@order-results
Feature: Order applies ledger results and credits confirmed seller profit
  A committed result changes the buyer's order once. Confirmed item margins are
  credited on each seller's catalog shard after the order transaction commits.

  Background:
    Given order results use migrated regional primaries and a broker

  @order-processes
  Scenario: Concurrent HTTP writes survive a saga restart with one ledger decision per order
    Then HTTP accepts concurrent retries while the saga is stopped and its restart completes each order once

  @crash-recovery
  Scenario: A killed saga redelivers a committed result before acknowledgement while HTTP commits another order
    Then a killed result consumer after its database commit redelivers without duplicate effects while HTTP writes continue

  @crash-recovery
  Scenario: A killed ledger consumer preserves its committed decision while order HTTP commits another order
    Then a killed ledger consumer preserves its decision while independent order HTTP commits

  @product-browser
  Scenario: A recorded cross-shard order credits each signed item margin once
    Then a packaged ledger recording confirms a cross-shard order and credits its items once

  @product-browser
  Scenario: A definitive ledger rejection cancels and releases stock without profit
    Then a packaged ledger rejection cancels the order and releases reservations without profit

  Scenario: Duplicate, reconciliation, and conflicting results preserve a terminal decision
    Then result replay and reconciliation are idempotent while changed identities and envelopes are quarantined

  Scenario: Missing orders are quarantined, while an unavailable home is retried by the broker
    Then an orphan result blocks creation and an unavailable home does not create an orphan

  Scenario: Orphan quarantine waits for the creation lock of its order ID
    Then an orphan result waits while order creation holds the lock of its order ID

  Scenario: Invalid result IDs go to broker parking without touching order state
    Then a malformed result is parked without a database side effect

  Scenario: Profit intents resume after the catalog shard recovers
    Then confirmed profit intents survive a catalog outage and order restart

  Scenario: A failed intent insert rolls back the complete order transition
    Then a failed profit intent insert leaves the result unacknowledged until retry succeeds

  Scenario: Unknown historical item cost prevents a recorded result from confirming an order
    Then a recorded result with an unknown item cost is quarantined before confirmation

  Scenario: Seller credit survives an interrupted home mark and competing workers
    Then a committed seller credit is reused after an interrupted home mark by two workers


  @currency-policy @product-browser
  Scenario: Every supported currency crosses buyer regions and survives ledger replay and retained cutover
    Then all seven currencies confirm cross-region purchases with exact profits and immutable legacy retries

  @currency-policy @product-browser
  Scenario: A retained fractional JPY order recovers through the legacy contract after country cutover
    Then a retained version-one JPY snapshot survives country cutover, restart and identical retries
