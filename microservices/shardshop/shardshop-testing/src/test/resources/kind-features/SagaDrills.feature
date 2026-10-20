@kind-drill @kind-saga-drill
Feature: Explicit kind saga replay and CDC relocation drills
  The shared drill lock serializes mutations. Recovery restores producer replicas,
  ledger policy and order placement; durable scoped orders remain as evidence.
  One order replica with one stock worker reserves stock in sequence; step 4.2 tests competing coordinators.

  Scenario: Duplicate envelopes preserve decisions and two orders for the same stock never oversell
    Then scoped saga retries, rejection replay and two orders for the same stock preserve permanent effects

  Scenario: An order replacement on another worker resumes stored CDC offsets
    Then an order JVM on a different worker resumes persisted CDC and confirms a new order
