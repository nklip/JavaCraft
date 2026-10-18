@ledger-consumer
Feature: Packaged ledger command consumer
  The ledger commits one immutable business decision and keeps every order-reserved transport binding.

  Background:
    Given a migrated disposable ledger database and preprovisioned command broker

  Scenario: Startup check leaves pending broker deliveries untouched
    Then startup validation exits without running the command consumer

  Scenario: The long lived consumer resumes after its broker connection closes
    Then the consumer reconnects and processes a new command after connection loss

  Scenario: A valid USD command commits the decision, entry, binding, inbox and pending result together
    Then a valid USD command records exactly one complete result

  Scenario: A well formed but disallowed currency remains rejected after policy expands
    Then an unsupported EUR command saves a permanent rejection without an entry

  Scenario: A duplicate delivery rearms the saved result after publication, transport cleanup and a policy change
    Then an identical command replays its exact result after publication, cleanup and restart

  Scenario: Reconciliation reserves a new transport pair for the same permanent decision
    Then a reconciliation command retains the original decision and timestamp

  Scenario: Conflicting business and transport identities are quarantined without changing a decision
    Then distinct conflicts remain quarantined and repeated deliveries increment their counts

  Scenario: Transport IDs cannot be rebound even among commands for the same order
    Then swapped command and result IDs are quarantined without changing either saved result

  Scenario: Invalid command syntax and semantics go directly to the parking queue
    Then malformed commands have no ledger effects

  Scenario: Competing packaged consumers serialize the same order and protect identities across orders
    Then concurrent consumers commit one entry and quarantine conflicting identities

  Scenario: Each order, saga, command and transport identity serializes deliveries
    Then the consumer waits for a held lock of each command identity

  Scenario: A failed ledger entry write rolls back the whole delivery and can recover
    Then a database write failure parks the command without a business rejection and recovery records it
