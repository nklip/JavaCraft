#!/usr/bin/env bash
# Render from one ordered inventory. No Kubernetes access or generated files in Git.
# When: up.sh, migrate.sh, deploy.sh and verify-topology.sh call
# it through shards.sh; run it by hand to preview manifests without a cluster.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
usage='Usage: render.sh infrastructure|namespace|inventory|routing | migration shard [catalog|ordering] | migration ledger-db ledger | applications|seeding run [application=image ...]'
(( $# >= 1 )) || { echo "$usage" >&2; exit 1; }
mode=$1
case "$mode" in
    infrastructure|namespace|inventory|routing) [[ $# == 1 ]] || { echo "$usage" >&2; exit 1; } ;;
    # The chart validates shard inventory membership and the ledger-db/ledger pairing.
    migration) (( $# >= 2 && $# <= 3 )) || { echo "$usage" >&2; exit 1; } ;;
    # The chart validates the run name and the digest reference of each image.
    applications|seeding) (( $# >= 2 )) || { echo "$usage" >&2; exit 1; } ;;
    *) echo "$usage" >&2; exit 1 ;;
esac

args=(--namespace shardshop --values "${SHARDSHOP_ENV_VALUES:-$root/infra/helm/shardshop/kind-values.yaml}"
      --values "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" --set-string "render=$mode")
if [[ "$mode" == migration ]]; then
    args+=(--set-string "migrationShard=$2" --set-string "migrationStream=${3:-catalog}")
elif [[ "$mode" == applications || "$mode" == seeding ]]; then
    # --set-literal gives each value to the chart schema unchanged: --set-string divides a value
    # at each comma and removes backslashes, which could render the Job of a different run.
    args+=(--set-literal "run=$2")
    for image in "${@:3}"; do
        [[ "$image" == ?*=?* ]] || { echo "$usage" >&2; exit 1; }
        args+=(--set-literal "images.${image%%=*}=${image#*=}")
    done
fi
helm template shardshop "$root/infra/helm/shardshop" "${args[@]}" |
    if [[ "$mode" == inventory ]]; then
        # Inventory is a single JSON ConfigMap; remove Helm's header for stdlib readers.
        sed '/^# Source:/d; /^---$/d'
    else
        cat
    fi
