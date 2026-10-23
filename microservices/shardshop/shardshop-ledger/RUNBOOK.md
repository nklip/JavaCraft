# Ledger runbook

[ShardShop](../README.md) › [Ledger](README.md) › **Runbook** · [Architecture](ARCHITECTURE.md)

## Command consumer

Ledger consumes `ledger.record-order` with manual acknowledgement and one
prefetched delivery per process. It validates the complete `RecordOrder` schema,
canonical string IDs, timestamp, immutable snapshot arithmetic and normalized
request fingerprint before opening a bounded transaction on its own database.
The parser enforces the broker's 1 MiB payload limit before JSON materialization.
It never reads the order or catalog databases and generates no IDs.

The transaction locks the order, saga, command and transport identities in a stable
order with PostgreSQL advisory locks. A new supported-currency command inserts
one permanent decision and ledger entry, its reserved result binding, inbox and
pending result outbox. The default allowlist is `CNY,EUR,GBP,JPY,KRW,USD,VND`.
A valid currency excluded by an explicitly scoped test policy saves a permanent
`REJECTED` decision with `UNSUPPORTED_CURRENCY` and no entry. The
consumer acknowledges only after commit. Malformed commands use
`basic.reject(requeue=false)`; transient transaction failures use
`basic.reject(requeue=true)` and the broker's bounded delayed-retry/DLQ policy.

An identical command always rearms its saved result, including an inbox hit or a
delivery after transport cleanup. Its durable publication attempt increments in
the same transaction as the pending outbox. A new reconciliation transport pair
uses the saved business outcome and `decidedAt`, even after allowlist changes.
Conflicting logical IDs, snapshots or transport bindings commit to
`ledger.conflicting_commands` before acknowledgement, without replacing the
decision or generating a rejection. Repeated identical conflicts increment their
delivery count; different canonical payloads remain separate incidents.

Apply ledger V2 before starting the consumer. It permanently retains each original
command envelope in `ledger_result_ids`, so a changed same-ID payload or timestamp
is still detected after inbox cleanup. V2 backfills existing bindings from their
inboxes and deliberately stops if that original payload is unavailable; reconcile
such retained data before retrying the migration. V1 remains unchanged.

## Result publisher

The ledger polls committed pending rows in `ledger_outbox`, joining
`ledger_result_ids` for the saved result envelope. It publishes that exact JSON
with the order-reserved result ID to `order.saga-results`, routing key
`order.ledger-results`. Messages are persistent and mandatory; a positive broker
confirm without a return is required before marking a row published.

The mark compares both `message_id` and the sent `publication_attempt`. A replay
that increments the permanent attempt and rearms or recreates the outbox therefore
stays pending when an older confirm arrives. Neither publishing nor retrying
changes the envelope, its timestamps or the attempt counter. A crash or uncertain
database commit after publication can deliver the same result again; delivery is
at least once.

One publication is in flight per process. Reads and conditional marks use separate
bounded database transactions, with no database connection held during broker IO.
The relay reads at most 100 rows per batch by default, immediately continues after
a full batch, and otherwise polls every 500 ms. Errors retain pending rows and
replace the publisher connection, backing off from 500 ms to 30 seconds. When the
broker or the network closed the connection while it was idle, the next publication
opens a new one without a failed attempt. Each new connection logs one INFO line,
`RabbitMQ result publisher connected to order.saga-results`. A restart resumes
pending results. The retry log includes a bounded cause, without payloads. A
timeout cause names its phase: the connection, the publication or its confirm.

## Configuration

Configure the dedicated ledger database and broker credentials:

```properties
shardshop.ledger.enabled=true
shardshop.ledger.database.url=jdbc:postgresql://ledger-db-rw:5432/ledger
shardshop.ledger.database.username=ledger_app
shardshop.ledger.database.password=${LEDGER_DATABASE_PASSWORD}
shardshop.ledger.database.root-certificate=/etc/shardshop/ledger/ca.crt
shardshop.ledger.max-pool-size=4
shardshop.ledger.allowed-currencies=CNY,EUR,GBP,JPY,KRW,USD,VND
shardshop.ledger.broker.host=shardshop-rabbitmq
shardshop.ledger.broker.port=5672
shardshop.ledger.broker.virtual-host=shardshop
shardshop.ledger.broker.username=shardshop-ledger
shardshop.ledger.broker.password=${RABBITMQ_LEDGER_PASSWORD}
shardshop.ledger.broker.timeout=5s
```

An enabled consumer requires both passwords. `shardshop.ledger.enabled=false`
disables both workers. Database phases have a three-second deadline; broker
connections use the configured timeout and reconnect with capped exponential
backoff and 25% jitter. The ledger and order consumers share this
loop (`QueueConsumer` in `shardshop-common`).
The default database URL names `ledger-db-rw`, the read-write Service of the
`ledger-db` cluster.

The publisher uses the same ledger database and broker credentials as the consumer:

```properties
shardshop.ledger.publisher.enabled=true
shardshop.ledger.publisher.batch-size=100
shardshop.ledger.publisher.poll-interval=500ms
```

Set `shardshop.ledger.publisher.enabled=false` to run only the command consumer. Batch size is
limited to 1–1000 and poll interval to 1 ms–60 seconds. The broker timeout bounds
the send and confirm phase, including a blocked socket write; connection cleanup
can take one additional timeout. Failed streams are discarded before retry.
Multiple ledger processes may publish duplicates, while the conditional mark
preserves newer replay attempts.

The order CDC relay and this relay share one publisher class,
`dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher` in
`shardshop-common`. Common declares the RabbitMQ client as an optional dependency,
thus only order and ledger, which declare the client themselves, contain it.

## Tests

**Command consumer.** Build the ledger and run its disposable PostgreSQL/RabbitMQ
acceptance:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-ledger -am -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@ledger-consumer or @ledger-migration'
```

**Result publisher**, with the consumer and the order CDC relay. Run the module
builds and the disposable PostgreSQL/RabbitMQ checks:

```bash
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-ledger,shardshop-order -am -Paudit install
mvn -B -ntp -f microservices/shardshop/pom.xml -pl shardshop-testing -am -Pintegration verify \
  '-Dcucumber.filter.tags=@ledger-publisher or @ledger-consumer or @ledger-migration or @cdc-relay'
```
