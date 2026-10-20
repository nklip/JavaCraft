@order-results
Feature: Order applies ledger results and credits confirmed seller profit
  A committed result changes the buyer's order once. Confirmed item margins are
  credited on each seller's catalog shard after the order transaction commits.

  Background:
    Given order results use migrated regional primaries and a broker

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

