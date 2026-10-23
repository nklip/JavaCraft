# ShardShop core

[ShardShop](../README.md) › **Core** · [Runbook](RUNBOOK.md) · [Architecture](ARCHITECTURE.md)

The core libraries run inside the applications. They have no process to start and
no monitor of their own; the applications that use them report their state.

| Library | Runs inside | Runbook | Architecture |
|---|---|---|---|
| `shardshop-common` | All six applications | [Common runbook](common/RUNBOOK.md) | [Common](common/ARCHITECTURE.md) |
| `shardshop-idgen` | Product and order | [ID generation runbook](idgen/RUNBOOK.md) | [ID generation](idgen/ARCHITECTURE.md) |
| `shardshop-sharding` | Product and order | [Routing runbook](sharding/RUNBOOK.md) | [Shard routing](sharding/ARCHITECTURE.md) |
| `shardshop-workload-control` | The three workloads | [Workload control guide](workload-control/README.md) | [Workload control](workload-control/ARCHITECTURE.md) |

The wire contracts and their checks are in the [core runbook](RUNBOOK.md).
