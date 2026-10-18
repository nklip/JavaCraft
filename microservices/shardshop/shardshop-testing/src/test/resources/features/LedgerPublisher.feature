@ledger-publisher
Feature: Packaged ledger result publisher
  Confirmed result delivery retains the permanent decision and fences older publication attempts.

  Background:
    Given a migrated ledger database and broker for result publication

  Scenario: Recorded and rejected decisions publish their saved envelopes
    Then recorded and rejected results reach the order queue and are marked published

  Scenario: Pending results resume after a process restart
    Then a pending result is published after the ledger restarts

  Scenario: Duplicate and reconciliation commands replay retained decisions
    Then replayed and reconciliation results retain their exact saved decisions

  Scenario: An unroutable result stays pending until the queue binding returns
    Then a missing order result binding preserves the pending result until recovery

  Scenario: A broker connection loss does not strand pending results
    Then the publisher reconnects and confirms a later result

  Scenario: A stale confirmation cannot mark a newer replay attempt
    Then an older publisher confirmation cannot clear a newer pending attempt
