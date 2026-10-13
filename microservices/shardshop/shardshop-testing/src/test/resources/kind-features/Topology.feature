Feature: Kind deployment topology and explicitly selected database drills
  All checks use the existing invocation's inventory and kind lab.
  The database drill selects the first inventory shard unless shardshop.kind.drillShard is set.

  @kind-deployment @kind-topology
  Scenario: Every shard has its deployed routing and independent durable replication topology
    Then every inventory shard has independent replication, durable quorum, worker volumes and golden routing

  @kind-drill @kind-database-drill
  Scenario: One shard preserves replicated data and volumes while its quorum is disrupted
    Then the selected inventory shard survives the reversible database quorum drill
