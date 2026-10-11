#!/usr/bin/env bash
# Deploy product and the product reader, and create the seeder Job of a new run. The workloads
# own their lifecycle (ARCHITECTURE.md workload lifecycle): the reader sends load only after it
# resolves the complete dataset through product. Thus this script does not wait for the Job.
# When: after up.sh, migrate.sh and build-application-images.sh. Every call needs a new run
# name, because each run has its own seeder Job.
# Next: the reader runs until the next deploy.sh call or until you scale it to zero.
set -euo pipefail

[[ $# == 1 ]] || { echo 'Usage: deploy.sh <run-name>' >&2; exit 1; }
run=$1
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
for tool in docker kind kubectl helm python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cp "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" "$work/inventory.yaml"
export SHARDSHOP_INVENTORY="$work/inventory.yaml"
load_inventory
assert_published_topology

# Use the digest that build-application-images.sh registered on every kind node.
images=()
nodes=$(kind get nodes --name shardshop)
for application in product product-seeder product-reader; do
    references=$(for node in $nodes; do
        docker exec "$node" crictl inspecti "docker.io/library/shardshop-$application:local" 2>/dev/null |
            python3 -c '
import json, sys
name = sys.argv[1]
references = [reference for reference in json.load(sys.stdin)["status"]["repoDigests"]
              if reference.startswith(name + "@sha256:")]
print(references[0] if len(references) == 1 else "missing")
' "docker.io/library/shardshop-$application" || echo missing
    done | sort -u)
    [[ "$references" == docker.io/*@sha256:* && "$references" != *$'\n'* ]] \
        || { echo "Run scripts/build-application-images.sh $application first" >&2; exit 1; }
    images+=("$application=$references")
done
render applications "$run" "${images[@]}" >"$work/applications.yaml"
render seeding "$run" "${images[@]}" >"$work/seeding.yaml"
job="shardshop-product-seeder-$run"
[[ -z $(k get job "$job" --ignore-not-found -o name) ]] \
    || { echo "Run $run already exists; use a new run name" >&2; exit 1; }

k apply -f "$work/applications.yaml"
k create -f "$work/seeding.yaml"
echo "Run $run started: Job $job seeds catalog-v1, and the product reader starts its load after it resolves the complete dataset."
