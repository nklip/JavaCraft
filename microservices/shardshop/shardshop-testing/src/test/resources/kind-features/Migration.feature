Feature: Kind migration safety observations
  Helm runs and waits for migration Jobs; these gates protect topology and routing publication.

  @kind-migration-reservation
  Scenario: SQL uses the retained immutable topology reservation
    Then the migration topology reservation matches the invocation inventory

  @kind-migrations-idle
  Scenario: Another migration release cannot overlap existing SQL work
    Then no schema migration Job is active pending or terminating

  @kind-publications
  Scenario: Current CNPG publications permit routing publication
    Then every inventory publication has applied its current generation
