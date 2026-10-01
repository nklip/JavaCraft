#!/usr/bin/env bash
# Migrate database schemas in the kind-shardshop lab: run each Flyway stream from database/
# on every shard primary, one shard at a time, and stop at the first failure. up.sh
# declares the clusters, roles, databases and schemas that these streams fill.
# When: after up.sh, and again after adding or changing a migration under database/.
# Product and order can start only after it has published the routing ConfigMap.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: migrate.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cp "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" "$work/inventory.yaml"
export SHARDSHOP_INVENTORY="$work/inventory.yaml"
load_inventory
render routing >"$work/routing.yaml"
streams=(catalog ordering)
for stream in "${streams[@]}"; do
    for shard in "${shards[@]}"; do
        render migration "$shard" "$stream" >"$work/$shard-$stream.yaml"
    done
done
assert_published_topology
bash "$root/scripts/build-migration-image.sh"
reserve_migration_topology

for stream in "${streams[@]}"; do
    # The stream's SQL files and flyway.toml; its Jobs mount them at /flyway/sql.
    k create configmap "$stream-migrations" --from-file="$root/database/shard/$stream" \
        --dry-run=client -o yaml | k apply -f -
    for shard in "${shards[@]}"; do
        k wait "cluster/$shard" --for=condition=Ready --timeout=300s
        generation=$(k get "database/$shard-shardshop" -o jsonpath='{.metadata.generation}')
        k wait "database/$shard-shardshop" --for=jsonpath='{.status.observedGeneration}'="$generation" --timeout=180s
        k wait "database/$shard-shardshop" --for=jsonpath='{.status.applied}'=true --timeout=180s
        job="$shard-$stream-migration"
        k delete job "$job" --ignore-not-found --wait
        k apply -f "$work/$shard-$stream.yaml"
        # A Job's first condition is terminal: SuccessCriteriaMet/Complete or FailureTarget/Failed.
        k wait "job/$job" --for=jsonpath='{.status.conditions[0].status}'=True --timeout=200s
        k logs "job/$job"
        [[ $(k get job "$job" -o jsonpath='{.status.succeeded}') == 1 ]] \
            || { echo "$stream migration failed on $shard; later shards were not attempted" >&2; exit 1; }
    done
done
# up.sh declares publications before their tables exist. Let CNPG reconcile them
# after every stream succeeds, and reject a stale successful status from an older spec.
for shard in "${shards[@]}"; do
    publication="$shard-order-outbox"
    for attempt in {1..90}; do
        status=$(k get publication "$publication" \
            -o jsonpath='{.metadata.generation}{" "}{.status.observedGeneration}{" "}{.status.applied}')
        read -r generation observed_generation applied <<< "$status"
        [[ "$generation" == "$observed_generation" && "$applied" == true ]] && break
        (( attempt < 90 )) \
            || { echo "Publication $publication was not applied; routing was not published" >&2; exit 1; }
        sleep 2
    done
done
# Publish atomically only after ALL deployed shards have passed the migration gate.
# Existing processes retain their startup snapshot; health changes never alter membership.
assert_published_topology
k apply -f "$work/routing.yaml"
echo "Published routing version $routing_version for ${#shards[@]} shards."
