@order-results @saga-reconciliation @manual-reconciliation-reset
Feature: An operator can reopen an exhausted pending ledger reconciliation
  The selected order keeps its permanent ledger decision across packaged transport
  cleanup. Only an explicit operator reset gives its saga another replay budget.

  Background:
    Given order results use migrated regional primaries and a broker

  Scenario Outline: Exhausted <outcome> reconciliation recovers without duplicate effects
    Then an exhausted <outcome> decision resumes after an operator reset and packaged transport cleanup

    Examples:
      | outcome  |
      | RECORDED |
      | REJECTED |
