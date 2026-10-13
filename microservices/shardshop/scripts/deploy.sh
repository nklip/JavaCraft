#!/usr/bin/env bash
# Deploy product and the product reader, and create the seeder Job of a new run. The workloads
# own their lifecycle (ARCHITECTURE.md workload lifecycle): the reader sends load only after it
# resolves the complete dataset through product. Thus this script does not wait for the Job.
# When: after up.sh and migrate.sh. Every call needs a new run
# name, because each run has its own seeder Job.
# Next: the reader runs until the next deploy.sh call or until you scale it to zero.
set -euo pipefail

[[ $# == 1 ]] || { echo 'Usage: deploy.sh <run-name>' >&2; exit 1; }
run=$1
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
for tool in docker kind kubectl helm jq python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cp "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" "$work/inventory.yaml"
export SHARDSHOP_INVENTORY="$work/inventory.yaml"
load_inventory
assert_published_topology

# Use the deployed release's qualified images, without relying on mutable local
# tags or choosing among unrelated repository digests retained by containerd.
helm status shardshop-application --kube-context kind-shardshop --namespace shardshop --output json \
    >"$work/release.json" 2>/dev/null \
    || { echo 'Run scripts/up.sh first to deploy the application release' >&2; exit 1; }
jq -e '.name == "shardshop-application" and .namespace == "shardshop" and .info.status == "deployed"' \
    "$work/release.json" >/dev/null \
    || { echo 'The application Helm release must be deployed before starting a legacy run' >&2; exit 1; }
revision=$(jq -er '.version | select(type == "number" and . > 0 and . == floor)' "$work/release.json")
helm get values shardshop-application --kube-context kind-shardshop --namespace shardshop \
    --revision "$revision" --output json >"$work/release-values.json" 2>/dev/null \
    || { echo 'Cannot read the deployed application release images' >&2; exit 1; }
images=()
nodes=$(kind get nodes --name shardshop)
[[ -n "$nodes" ]] || { echo 'Run scripts/up.sh first to create the kind nodes' >&2; exit 1; }
for application in product product-seeder product-reader; do
    reference=$(jq -er --arg application "$application" '.images[$application] | select(type == "string")' \
        "$work/release-values.json")
    [[ "$reference" =~ ^docker\.io/library/shardshop-$application@sha256:[0-9a-f]{64}$ ]] \
        || { echo "The deployed release lacks a qualified $application image" >&2; exit 1; }
    for node in $nodes; do
        docker exec "$node" crictl inspecti "$reference" 2>/dev/null |
            jq -e --arg reference "$reference" '.status.repoDigests | index($reference) != null' >/dev/null \
            || { echo "Run scripts/up.sh first to load the deployed $application image on every node" >&2; exit 1; }
    done
    images+=("$application=$reference")
done
render applications "$run" "${images[@]}" >"$work/applications.yaml"
render seeding "$run" "${images[@]}" >"$work/seeding.yaml"
job="shardshop-product-seeder-$run"
[[ -z $(k get job "$job" --ignore-not-found -o name) ]] \
    || { echo "Run $run already exists; use a new run name" >&2; exit 1; }

k apply -f "$work/applications.yaml"
k create -f "$work/seeding.yaml"
echo "Run $run started: Job $job seeds catalog-v1, and the product reader starts its load after it resolves the complete dataset."
