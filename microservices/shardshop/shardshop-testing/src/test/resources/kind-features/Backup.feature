Feature: Coordinated backup and isolated restore
  @kind-backup-allocator
  Scenario: Current allocator history is independent of older database backups
    Then the latest allocator identity and high-water history are retained outside kind

  @kind-backup
  Scenario: Quiesced shards and ledger form a complete host-side backup
    Then a coordinated backup is written outside kind and the source is restored

  @kind-restore
  Scenario: A complete compatible backup requires explicit CDC recovery in isolation
    Then the selected backup restores into a new isolated target with explicit CDC recovery
