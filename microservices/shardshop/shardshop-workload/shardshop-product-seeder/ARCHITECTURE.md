# Product seeder architecture

[ShardShop](../../ARCHITECTURE.md) › [Workloads](../ARCHITECTURE.md) › **Product seeder** · [README](README.md) · [Runbook](RUNBOOK.md)

The product seeder creates or recovers every seller and product of the configured
dataset through the product API. One application runs in two modes: the finite
bootstrap Job of each release, and an
[interactive Deployment](../../shardshop-core/workload-control/ARCHITECTURE.md#seeder-modes)
that seeds only on operator request.

## Finite seeding and verification

| Job setting | Value |
|---|---|
| `restartPolicy` | `Never` |
| `backoffLimit` | `3` |
| `activeDeadlineSeconds` | `300` |

- Its idempotent POSTs seed sellers first, then their products with initial stock.
  Product generates each entity ID; the seeder uses the returned seller ID for
  product creation.
- It exits successfully only after every seller and product has been acknowledged
  and verified through product reads, by returned ID and by creation key. In the
  default profile, these reads use primaries.
- A retry repeats the same creation keys and payloads and recovers the same IDs.
  Within a run, the seeder sends a request again only after a transport failure or
  a `503` response, with bounded backoff.
- Other statuses and reads that differ from the acknowledged ID or payload stop
  the run.

> **Planned (step 6.1):** repeat a verification read that gets `404` for a limited
> time, because a standby can replay a commit later in the replica profile. The
> current seeder retries only transport failures and `503`.

The [shared lifecycle](../ARCHITECTURE.md#workload-lifecycle) defines deployment
ordering and completed Jobs. The [workload control architecture](../../shardshop-core/workload-control/ARCHITECTURE.md#seeder-modes)
defines the interactive mode, single-run control and pause semantics.
