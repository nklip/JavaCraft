@profit-throughput
Feature: Opt-in profit-stage throughput comparison on disposable PostgreSQL
  Prepared confirmed intents isolate seller crediting and home-shard completion.
  Synthetic confirmation excludes stock, CDC, result and ledger processing.
  The comparison does not qualify full-order capacity or require a speedup.

  Scenario: The same packaged candidate drains fresh equivalent serial and parallel profit fixtures
    Then serial and parallel profit workers drain identical warmed PostgreSQL fixtures
