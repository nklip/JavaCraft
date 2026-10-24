@kind-drill @kind-saga-drill
Feature: Explicit kind saga replay and CDC relocation drills
  The shared drill lock serializes mutations. Recovery restores producer replicas,
  ledger policy and saga placement; durable scoped orders remain as evidence.
  One saga replica with one stock worker reserves stock in sequence. A failed same-shard item group leaves
  no reservations or stock changes; step 4.2 tests competing coordinators and cross-shard compensation.

  Scenario: Duplicate envelopes preserve decisions and two orders for the same stock never oversell
    Then scoped saga retries, rejection replay and two orders for the same stock preserve permanent effects

  Scenario: A saga replacement on another worker resumes stored CDC offsets while writes continue
    Then the saga JVM on a different worker resumes CDC while HTTP commits new orders

  @kind-http-rollout
  Scenario: Continuous HTTP writes survive a rolling update while its saga relay keeps ownership
    Then an HTTP rollout preserves the saga JVM and its active CDC slots
