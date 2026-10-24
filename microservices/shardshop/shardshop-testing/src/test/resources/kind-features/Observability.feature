@kind-deployment @kind-observability
Feature: Read-only observability of the deployed saga
  Metrics are sampled from the existing application pods, broker and database primaries.

  Scenario: Every process exposes fresh bounded metrics and native backlog counters
    Then the deployed applications, broker and databases expose fresh bounded observability
