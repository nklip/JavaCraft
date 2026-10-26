Feature: Explicit older-restore operator audit and recovery
  These isolated operations never modify the retained source lab or authorize restored traffic.

  @kind-recovery-schema
  Scenario: Native repeated ordering restore recreates its namespace and preserves security and isolation
    Then the isolated ordering schema is restored twice without changing security or any database rows

  @kind-recovery-record
  Scenario: Verified original history remains independent of permanent ledger evidence
    Then the selected original order record is exported from its verified backup

  @kind-recovery-audit
  Scenario: Every permanent ledger decision is compared with restored primary orders
    Then the selected restored shards are audited against the permanent ledger snapshot

  @kind-recovery-repair
  Scenario: One selected original order is recovered from complete verified evidence
    Then the selected verified original order is recovered without discarding unresolved evidence

  @kind-recovery-drill
  Scenario: Previously acknowledged outcomes survive an older restore through explicit audit and recovery
    Then an older isolated restore is audited and recovered with original identities
