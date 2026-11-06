Feature: Coordinated backup and isolated restore
  @cluster-backup-allocator
  Scenario: Current allocator history is independent of older database backups
    Then the latest allocator identity and high-water history are retained outside kind

  @cluster-backup
  Scenario: Quiesced shards and ledger form a complete host-side backup
    Then a coordinated backup is written outside kind and the source is restored

  @cluster-restore
  Scenario: A complete compatible backup requires explicit CDC recovery in isolation
    Then the selected backup restores into a new isolated target with explicit CDC recovery
