@order-results @transport-cleanup
Feature: Packaged transport cleanup preserves durable order and ledger decisions
  Background:
    Given order results use migrated regional primaries and a broker

  Scenario: An old published order command waits for a durable checkpoint while unrelated old inbox data is cleaned
    Then packaged order cleanup keeps commands without a checkpoint and unpublished commands

  Scenario: Cleanup removes only expired transport and duplicate deliveries retain the exact decision
    Then packaged order and ledger cleanup preserve replay and business effects

  Scenario: A rejected decision stays rejected after cleanup and a policy change
    Then packaged cleanup replays the original rejection after a policy change
