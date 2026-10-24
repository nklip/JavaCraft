# ShardShop Ledger

[ShardShop](../README.md) › **Ledger** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

Ledger consumes order commands, records immutable financial decisions in its
separate database, and publishes the saved results with publisher confirms.

## Start

In the lab, `up.sh` deploys ledger as one pod with no HTTP Service; see the
[lab start](../README.md#start-the-lab). Apply ledger migrations through V3 before
starting the application; V3 adds transport retention indexes.

`-Dquarkus.config.locations=file:/path/to/ledger.properties` loads an external
configuration file. Normal starts enable the consumer, result publisher and transport cleanup;
`--check-startup` validates configuration and exits without consuming, publishing
or connecting to external services.

## Monitoring

```bash
kubectl --context kind-shardshop -n shardshop logs deployment/shardshop-ledger
```

The consumer log tells an operator why a delivery did not complete. Log lines can
contain validated IDs and fixed rule texts, but never the command body or a password:

- `Ledger command consumer stopped; retrying in <delay> ms: <cause>` follows a broker
  connection failure, a closed channel or a cancelled consumer.
- `Ledger command transaction failed; requested broker retry: <cause>` names the
  database error, for example a check constraint, without the row values.
- `Malformed ledger command rejected to its dead-letter queue: <rule>` names the
  rule that the command does not obey.
- `Conflicting ledger command <messageId> for order <orderId> committed to quarantine: <reason>`
  names the conflict reason that `ledger.conflicting_commands` stores.

The result publisher logs one INFO line for each new broker connection,
`RabbitMQ result publisher connected to order.saga-results`. Its retry log includes
a bounded cause, without payloads; a timeout cause names its phase: the connection,
the publication or its confirm.

`Ledger transport cleanup failed; retrying: <cause>` reports a failed cleanup
transaction once until `Ledger transport cleanup recovered` follows a successful
pass. See the [cleanup runbook](RUNBOOK.md#transport-cleanup) for retention settings
and inspection.

The [RabbitMQ management UI](../infra/README.md#rabbitmq-management) shows the
depth of the command and result queues. The [database guide](../database/README.md#ledger-database)
shows how to inspect the ledger database.
