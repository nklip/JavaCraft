@kind-drill @kind-workload-drill
Feature: Kind product workload recovery drill
  This opt-in drill requires a deployed, running reader and the immutable invocation chart snapshot.
  Temporary seeder Jobs are removed, reader replicas are preserved, and generator allocations are never rewound.

  Scenario: Repeated HTTP seeding and a fresh product JVM preserve service-issued IDs
    Then two temporary HTTP seeders preserve IDs and one replacement product JVM recovers workload traffic
