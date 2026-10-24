@order-results @saga-reconciliation
Feature: A packaged order saga reconciles lost ledger results with a durable retry budget
  Background:
    Given order results use migrated regional primaries and a broker

  Scenario: An unpublished command does not consume a reconciliation attempt
    Then an overdue unpublished command waits while another eligible saga is replayed

  Scenario: Replay identities, snapshots, and the five-attempt budget survive cleanup and restart
    Then competing packaged coordinators persist five distinct replays without a sixth attempt

  Scenario Outline: A lost <outcome> result is regenerated from the ledger's permanent decision
    Then an undelivered <outcome> result recovers through a checkpointed replay

    Examples:
      | outcome  |
      | RECORDED |
      | REJECTED |
