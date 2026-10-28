@stock-throughput
Feature: Opt-in stock-stage throughput comparison on disposable PostgreSQL
  This measures production reservation work before the ledger boundary. It does
  not qualify complete-order capacity or assert a hardware-dependent speedup.

  Scenario: The same packaged candidate drains fresh equivalent serial and parallel fixtures
    Then serial and parallel stock workers drain identical warmed PostgreSQL fixtures
