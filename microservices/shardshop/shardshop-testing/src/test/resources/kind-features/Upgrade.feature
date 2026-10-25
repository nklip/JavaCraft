Feature: An older foundation upgrades without replacing retained identities or business data
  These explicitly selected observations use shardshop.kind.upgrade.evidence on an owned lab.
  Pause workload controls and drain accepted work before capture. Current lifecycle gates
  may add scoped orders, so the retained witness requires every original permanent row.

  @kind-upgrade-before
  Scenario: Retain evidence from a migrated pre-5.3 lab with sample orders
    Then the pre-5.3 lab retains a private upgrade witness

  @kind-upgrade-after
  Scenario: Current foundation reconciliation and application migration preserve old data
    Then the upgraded lab preserves the witness and grants the restricted operator

  @kind-upgrade-replay
  Scenario: An operator reset recovers an exhausted order retained across the foundation upgrade
    Then the retained exhausted order replays once after the operator reset
