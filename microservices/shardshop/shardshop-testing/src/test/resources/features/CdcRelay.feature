@cdc-relay
Feature: Order outbox WAL relay to the durable ledger command queue
  The packaged order JVM captures committed inserts from every shard and checkpoints
  only commands confirmed by RabbitMQ and marked published in their source shard.

  Background:
    Given CDC uses migrated TLS shard primaries and the chart broker

  Scenario: Snapshot and WAL deliver only pending committed inserts from each shard
    Then CDC snapshots pending rows, streams each shard, and ignores rollback updates and deletes

  Scenario: Inserts during the initial snapshot reach the queue exactly once
    Then CDC delivers snapshot rows and concurrent inserts exactly once

  Scenario: A saved offset survives an order process replacement
    Then CDC resumes its persisted per-shard offset after restart

  @crash-recovery
  Scenario: A killed saga before broker publication sends its committed command after restart
    Then a forced saga death before broker publication delivers the committed command once after restart

  @crash-recovery
  Scenario: A killed saga replays an already confirmed command before its publication mark
    Then a forced saga death after broker confirmation replays the same command

  @crash-recovery
  Scenario: A killed saga replays a marked command before its durable offset flush
    Then a forced saga death after publication marking resumes before the durable CDC checkpoint

  @crash-recovery
  Scenario: A killed saga after its durable checkpoint does not replay the committed command
    Then a forced saga death after the durable checkpoint resumes without replay

  Scenario: An idle reader keeps its durable position across a restart
    Then an idle CDC reader checkpoints its empty snapshot and resumes after a restart

  Scenario: A first start that fails before its first checkpoint recovers on retry
    Then a CDC reader that fails before its first checkpoint delivers after its route recovers

  Scenario: An idle outbox confirms WAL while other database writes continue
    Then an idle CDC outbox confirms and releases unrelated WAL

  Scenario: One shard reader outage does not stop a healthy shard
    Then an unavailable CDC shard leaves its command pending while another shard publishes

  Scenario: Mandatory return prevents publication and the reconnect recovers the same command
    Then an unroutable CDC command stays pending until its route and reader recover

  Scenario: A dropped replication connection reconnects without losing a command
    Then a terminated CDC walsender reconnects and delivers the next command

  Scenario: A second order process cannot read a shard that another process owns
    Then a second order process waits for CDC ownership and takes over after the first stops

  Scenario: A missing replication slot stops its shard reader without resnapshotting
    Then a missing CDC slot fails closed

  Scenario: A missing offset stops its shard reader without resnapshotting
    Then a missing CDC offset fails closed

  Scenario: Losing both slot and offset cannot erase permanent reader history
    Then missing CDC slot and offset fail closed with immutable reader history

  Scenario: Lost WAL stops its shard reader without resnapshotting
    Then an invalidated CDC slot fails closed

  Scenario: A slot ahead of its durable offset stops its shard reader
    Then a CDC slot ahead of its durable offset fails closed
