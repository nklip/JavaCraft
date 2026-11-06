Feature: Kind migration and release safety observations
  Helm runs and waits for migration Jobs; these gates protect topology, routing publication and drill recovery.

  @cluster-migration-reservation
  Scenario: SQL uses the retained immutable topology reservation
    Then the migration topology reservation matches the invocation inventory

  @cluster-migrations-idle
  Scenario: Another migration release cannot overlap existing SQL work
    Then no schema migration Job is active pending or terminating

  @cluster-publications
  Scenario: Current CNPG publications permit routing publication
    Then every inventory publication has applied its current generation

  @cluster-drills-idle
  Scenario: An application release cannot overlap a drill or its unrecovered changes
    Then no explicit drill holds or retains the shared drill lock
