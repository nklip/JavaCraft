@kind-deployment @kind-workload
Feature: Deployed kind product workload
  The lab must already be migrated and deployed. These observations preserve deployed resources.
  The saga scenario adds two scoped orders for its own buyer and does not examine producer orders.

  Scenario: Product and its workloads share the verified catalog
    Then the deployed product workload has the seeded catalog and HTTP-only reader

  Scenario: The existing reader reaches both product replicas with clean primary reads
    Then both product pods receive reads and the reader reports a fresh clean interval

  @kind-saga
  Scenario: The deployed saga resumes durable capture and completes its own scoped orders
    Then order, ledger and the HTTP-only producer are deployed with durable CDC offsets
    And the gate's own USD and EUR orders reach terminal ledger, stock and seller profit effects
