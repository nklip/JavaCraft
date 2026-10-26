@kind-drill @kind-failover-drill
Feature: Synchronous failover on the retained lab
  Scenario: Acknowledged saga data and CDC survive safe promotion and unsafe promotion is refused
    Then synchronous promotion preserves acknowledged sagas and CDC and refuses an unconfirmed survivor
