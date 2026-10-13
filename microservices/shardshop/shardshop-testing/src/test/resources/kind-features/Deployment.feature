@kind-deployment @kind-workload
Feature: Deployed kind product workload
  The lab must already be migrated and deployed. These observations preserve deployed resources.

  Scenario: Product and its workloads share the verified catalog
    Then the deployed product workload has the seeded catalog and HTTP-only reader

  Scenario: The existing reader reaches both product replicas with clean primary reads
    Then both product pods receive reads and the reader reports a fresh clean interval
