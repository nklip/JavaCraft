@kind-drill @kind-replica-read-drill
Feature: Strict replica reads on the retained lab
  Scenario: Replica reads stay bounded without primary fallback and all temporary settings recover
    Then strict replica reads survive lag, standby loss and hung queries and restore primary reads
