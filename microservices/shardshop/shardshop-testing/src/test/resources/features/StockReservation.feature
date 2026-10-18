@stock-reservation
Feature: Durable stock reservation and compensation on PostgreSQL primaries
  The packaged coordinator uses order_app to reserve catalog stock and move accepted
  orders to PENDING_LEDGER. Every committed catalog state obeys the stock invariant.

  Background:
    Given stock reservation uses migrated regional primaries and packaged providers

  Scenario: A cross-region order reaches the ledger boundary once across retries and restarts
    Then cross-region stock reservation creates one immutable RecordOrder

  @cdc-relay
  Scenario: The WAL relay publishes the stored RecordOrder of an accepted order unchanged
    Then CDC publishes the stored RecordOrder of the accepted order unchanged

  Scenario: Competing coordinators never oversell scarce stock and compensate rejected orders
    Then concurrent scarce-stock orders never oversell with competing coordinators

  Scenario: A catalog outage after acceptance preserves partial work for restart recovery
    Then a pending stock saga resumes after a catalog outage without reserving twice

  Scenario: Restart respects an active durable lease and takes over after its expiry
    Then a restarted coordinator reclaims an expired stock lease exactly once

  Scenario: A failure between the Java reservation insert and stock decrement rolls back both
    Then a failed Java stock decrement rolls back its reservation insert

  Scenario: Cancellation persists before compensation and a failed restoration rolls back release
    Then a failed Java stock restoration resumes compensation after restart

  Scenario: Runtime catalog privileges prohibit reservation truncation
    Then the order runtime login cannot truncate reservation history
