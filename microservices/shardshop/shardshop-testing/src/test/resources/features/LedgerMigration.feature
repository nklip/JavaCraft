@ledger-migration
Feature: Ledger V2 keeps the original command of every binding
  Flyway 13.12.0 applies database/ledger/ledger as ledger_migrator, which acts as ledger_schema_owner, with the
  settings of the stream's flyway.toml. V2 adds the permanent command_envelope to ledger_result_ids and backfills it
  from the retained inbox. It stops when a legacy binding has lost its original command.

  Scenario: V2 backfills the original command of a legacy binding from its retained inbox
    Then a V1 binding with a retained inbox command keeps that command after V2

  Scenario: V2 stops when a legacy binding has lost its original command
    Then V2 fails for a V1 binding without an inbox command and leaves the V1 schema
