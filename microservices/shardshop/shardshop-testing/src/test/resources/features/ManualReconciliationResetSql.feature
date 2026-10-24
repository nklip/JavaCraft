@manual-reconciliation-reset-sql
Feature: The manual reconciliation SQL changes only the selected pending saga
  A dedicated operator runs the checked-in psql file against a migrated ordering shard.

  Background:
    Given a migrated ordering shard for manual reconciliation SQL

  Scenario: An exact selected pair resets only its retry coordinates and can be run again
    Then the manual reset changes only the selected pending saga retry coordinates

  Scenario: Missing, mismatched, and terminal pairs leave all rows unchanged
    Then the manual reset refuses missing mismatched and terminal pairs

  Scenario: Invalid and injected identifiers fail without writes
    Then the manual reset rejects invalid identifiers without writes

  Scenario: The order advisory lock protects a newly terminal order
    Then the manual reset rechecks status after waiting for the order lock

  Scenario: A saga row lock collision rolls the bounded reset back
    Then the manual reset times out at a locked saga without writes

  Scenario: Operator permissions stay within the two retry columns
    Then the manual operator has only the reconciliation privilege matrix

  Scenario: Repeatable grants remove stale privileges without changing retained rows
    Then ordering grants remove stale operator privileges without changing rows
