#!/usr/bin/env bash
# Render from one ordered inventory. No Kubernetes access or generated files in Git.
# When: up.sh, migrate.sh, verify-topology.sh and build-migration-image.sh call it
# through shards.sh; run it by hand to preview manifests without a cluster.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
usage='Usage: render.sh infrastructure|namespace|inventory|routing|migration [shard]'
(( $# >= 1 && $# <= 2 )) || { echo "$usage" >&2; exit 1; }
mode=$1
case "$mode" in
    infrastructure|namespace|inventory|routing) [[ $# == 1 ]] || { echo "$usage" >&2; exit 1; } ;;
    # The chart rejects a shard that is not in the inventory.
    migration) [[ $# == 2 ]] || { echo "$usage" >&2; exit 1; } ;;
    *) echo "$usage" >&2; exit 1 ;;
esac

args=(--namespace shardshop --values "${SHARDSHOP_ENV_VALUES:-$root/infra/helm/shardshop/kind-values.yaml}"
      --values "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" --set-string "render=$mode")
if [[ "$mode" == migration ]]; then args+=(--set-string "migrationShard=$2"); fi
helm template shardshop "$root/infra/helm/shardshop" "${args[@]}" |
    if [[ "$mode" == inventory ]]; then
        # Inventory is a single JSON ConfigMap; remove Helm's header for stdlib readers.
        sed '/^# Source:/d; /^---$/d'
    else
        cat
    fi
