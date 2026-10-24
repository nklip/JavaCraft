@transport-cleanup-migration
Feature: Cleanup indexes upgrade retained transport without changing replay state
  The database owner adds retention indexes through fix-forward Flyway migrations.

  Scenario: Ordering V8 preserves retained transport and an exhausted reconciliation budget
    Then ordering cleanup indexes preserve the retained V7 rows and rerun without changes

  Scenario: Ledger V3 preserves retained transport and permanent replay identities
    Then ledger cleanup indexes preserve the retained V2 rows and rerun without changes
