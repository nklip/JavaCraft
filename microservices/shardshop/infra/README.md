# ShardShop infrastructure

[ShardShop](../README.md) › **Infrastructure** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

The infrastructure is the local kind cluster: four nodes, CloudNativePG, the
PostgreSQL clusters, RabbitMQ and the generator registry.

## Start

The [lab start](../README.md#start-the-lab) creates the infrastructure once and then
deploys the applications on it:

| Command | Starts |
|---|---|
| `cluster-create.sh` | The kind cluster (one control plane, three workers), namespace `shardshop`, credential Secrets, CloudNativePG 1.30.1, the shard clusters `shard-a`, `shard-b` and `shard-c`, the `ledger-db` cluster, RabbitMQ and the generator registry |
| `up.sh` | Resumes stopped nodes, then builds, migrates and deploys the applications |

Both commands verify their result before they exit: DNS on every node, database
readiness, the two streaming standbys of each shard and the effective broker
topology. The [runbook](RUNBOOK.md) describes each phase, the stop and the
recovery.

## Monitoring

### Cluster status

```bash
kubectl --context kind-shardshop get nodes
kubectl --context kind-shardshop get storageclass
kubectl --context kind-shardshop get namespace shardshop --show-labels
kubectl --context kind-shardshop get crds
helm --kube-context kind-shardshop -n cnpg-system list
kubectl --context kind-shardshop -n cnpg-system get deployment cnpg-cloudnative-pg
kubectl --context kind-shardshop -n shardshop get clusters,pods,pvc -L shardshop.javacraft/region
```

### Pod inventory

For an already-running lab, record the date and all namespaces so the operator,
Kubernetes system pods and transient Jobs remain separate from the 20 steady
application/database/broker pods:

```bash
kubectl --context kind-shardshop get pods --all-namespaces -o wide
kubectl --context kind-shardshop get pods --all-namespaces \
  -o custom-columns='NAMESPACE:.metadata.namespace,NAME:.metadata.name,OWNER_KIND:.metadata.ownerReferences[0].kind,OWNER:.metadata.ownerReferences[0].name,PHASE:.status.phase'
```

Classify Deployment/StatefulSet replicas, CNPG instance pods, system pods and
Job-owned pods separately. Count running pods and completed/failed/terminating
pods separately; a completed bootstrap seeder is not an interactive-seeder replica.
Read-only observation must not trigger cluster creation or resumption.

### RabbitMQ management

`shardshop-rabbitmq.shardshop.svc:5672` serves AMQP, and port 15672 serves the
management UI inside the cluster. Open the UI through a local port forward:

```bash
kubectl --context kind-shardshop -n shardshop port-forward service/shardshop-rabbitmq 15672:15672
```

Then open <http://127.0.0.1:15672/> and log in as `shardshop-admin`. The password is
the `admin-password` key of Secret `shardshop-rabbitmq`; do not print it into shared
logs. The service users `shardshop-order` and `shardshop-ledger` cannot use the
management UI. Watch the depth of `ledger.record-order`, `order.ledger-results` and
their `.dlq` parking queues.

### Saga and database pressure

Order HTTP, order saga and ledger expose Prometheus text at `/q/metrics` on port
8080. The saga and ledger have internal Services for these endpoints. Product
already exposes its request metrics there. No collector, dashboard or additional
pod is installed. For example, forward one endpoint and scrape it locally:

```bash
kubectl --context kind-shardshop -n shardshop port-forward service/shardshop-order-saga 18083:8080
# In another terminal:
curl --fail http://127.0.0.1:18083/q/metrics
```

Use `service/shardshop-ledger` for ledger or a named HTTP pod for that pod's pool
metrics. Scrape each pod separately when comparing pool or request pressure;
a Service scrape may select different replicas. The deployment gate verifies all
replicas through the Kubernetes pod proxy.

| Signal | Interpretation |
|---|---|
| Order `metrics_snapshot_success` / `metrics_snapshot_age_seconds`; ledger `snapshot_success` / `snapshot_age_seconds` | Require success=1 and a recent sample (the gate allows 30 seconds). Failed sampling keeps the previous values. Before the first success, values are unavailable, not zero. |
| Order `outbox_pending` / `outbox_oldest_pending_seconds`; ledger `pending_results` / `oldest_pending_age_seconds` | Growing age with pending work identifies a stalled publisher. Check broker capacity and database availability together. |
| Order `sagas{status=...}` / `sagas_reconciliation_exhausted` | Pending work and the five-attempt reconciliation limit. An exhausted saga needs investigation and, when appropriate, the documented manual reset. |
| Order `quarantine_orphan_rows`, `quarantine_orphan_unresolved`, `quarantine_conflicting_result_rows`; ledger `conflicting_commands` | Retained incidents are historical row counts; only the orphan-unresolved gauge is explicitly an unresolved count. |
| Order `cdc_recovery_required` versus `cdc_retrying` | Durable state loss requires an operator; an ordinary connection failure retries automatically. A zero recovery flag alone does not prove a healthy reader. |
| Order `cdc_slot_exists`, `cdc_slot_active`, `cdc_checkpoint_exists`, `cdc_retained_wal_bytes`, `cdc_checkpoint_lag_bytes` | Reader ownership, durable progress and WAL pressure. Byte distance includes unrelated database writes and is not a count of delayed orders. |
| Order `db_pool_*`; ledger `pool_*` | Active, available, awaiting and configured maximum connections for the custom application pools. These values do not query PostgreSQL. |

Order names have the prefix `shardshop_order_`, ledger names `shardshop_ledger_`.
Order labels are fixed process, shard and (where applicable) status values, never
order/customer IDs. See the [order metric contract](../shardshop-order/README.md)
and [ledger metric contract](../shardshop-ledger/RUNBOOK.md#metrics) for details.

RabbitMQ's existing [management API](#rabbitmq-management), `GET /api/queues/shardshop`,
provides numeric `messages_ready`, `messages_unacknowledged`, `message_bytes_ready`
and `message_bytes_unacknowledged`, together with `effective_policy_definition`.
The gate reads these counters without consuming messages. The pinned broker's
`rabbitmqctl list_queues` returns blank byte fields, which mean unavailable data;
do not interpret them as zero. The CLI still exposes counts, admission limits and
alarms:

```bash
kubectl --context kind-shardshop -n shardshop exec shardshop-rabbitmq-0 -c rabbitmq -- \
  rabbitmqctl --quiet --timeout 10 --formatter=json list_queues -p shardshop \
  name messages_ready messages_unacknowledged effective_policy_definition
kubectl --context kind-shardshop -n shardshop exec shardshop-rabbitmq-0 -c rabbitmq -- \
  rabbitmq-diagnostics -q -t 10 check_local_alarms
```

Compare ready counts/bytes with `max-length` / `max-length-bytes`; unacknowledged
deliveries are separate. Quorum queues can also retain internal dead-letter work
while a DLQ is unavailable. Ordinary ready/unacknowledged counters alone do not
prove this backlog is empty: use the [capacity drill observations](../shardshop-testing/RUNBOOK.md#retry-and-dlq-capacity-drills)
and broker alarms. Limits allow bounded in-flight overshoot and exclude overhead.

CloudNativePG exposes native metrics on every database pod at port 9187, path
`/metrics`. Use the pod proxy or a local pod port forward. On a primary,
`cnpg_pg_replication_slots_pg_wal_lsn_diff{slot_type="physical"}` reports retained
WAL bytes per physical slot; `cnpg_pg_replication_slots_active` reports its
activity. On a standby, `cnpg_pg_replication_lag` reports lag in seconds. The
streaming-replica total also includes the logical CDC connection, so it cannot
alone prove that both physical standbys are present. The observability gate
also reads `pg_stat_replication` on each primary: both physical standbys must be
streaming, and current WAL minus each `replay_lsn` shows their byte backlog.
Watch this alongside CDC retained WAL and available disk. Logical-slot health
does not prove failover readiness. The failover drill checks that.
