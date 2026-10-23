# ShardShop database layer

[ShardShop](../README.md) › **Database** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

The database layer owns the three regional shard clusters, the separate ledger
database, their roles and schemas, and the Flyway migration streams.

## Start

The database layer has no process of its own. `cluster-create.sh` creates the
clusters, roles, databases and schemas; `up.sh` runs the Flyway migrations as Helm
hook Jobs before it deploys the applications. Applications never migrate at
startup. See the [lab start](../README.md#start-the-lab).

## Monitoring

### Shard clusters

```bash
kubectl --context kind-shardshop -n shardshop get clusters,pods,pvc -L shardshop.javacraft/region
kubectl --context kind-shardshop -n shardshop get publications
```

### Ledger database

Inspect the database and its Service without exposing credentials:

```bash
kubectl --context kind-shardshop -n shardshop get cluster ledger-db
kubectl --context kind-shardshop -n shardshop get pods,pvc -l cnpg.io/cluster=ledger-db
kubectl --context kind-shardshop -n shardshop get service ledger-db-rw
kubectl --context kind-shardshop -n shardshop get endpointslices -l kubernetes.io/service-name=ledger-db-rw
```

### Migrations

```bash
kubectl --context kind-shardshop -n shardshop get jobs,pods -l app.kubernetes.io/name=schema-migration
kubectl --context kind-shardshop -n shardshop logs \
    -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=catalog,shardshop.javacraft/migration-target=shard-a \
    --all-containers=true --tail=100
kubectl --context kind-shardshop -n shardshop get databaseroles,databases
kubectl --context kind-shardshop -n shardshop logs \
    -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=ordering,shardshop.javacraft/migration-target=shard-a \
    --all-containers=true --tail=100
kubectl --context kind-shardshop -n shardshop get jobs -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=ledger
kubectl --context kind-shardshop -n shardshop logs \
    -l app.kubernetes.io/name=schema-migration,app.kubernetes.io/component=ledger,shardshop.javacraft/migration-target=ledger-db \
    --all-containers=true --tail=100
helm --kube-context kind-shardshop -n shardshop status shardshop-migrations
helm --kube-context kind-shardshop -n shardshop history shardshop-migrations
```

Successful migration Jobs are deleted after Helm observes completion; failed Jobs
keep their logs for 24 hours.

### Catalog currency-policy report

The read-only `catalog.currency_policy_legacy` view reports the currency cutover of
retained catalog rows. As the migration owner, inspect unresolved sellers (including
sellers with no products) and noncompliant products with:

```sql
SELECT * FROM catalog.currency_policy_legacy
WHERE legacy_reason IS NOT NULL ORDER BY seller_id, product_id;
```
