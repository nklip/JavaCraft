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
