#!/usr/bin/env bash
# Create or reuse the local ShardShop kind cluster, install CNPG, render the shared chart.
# Tools come from PATH. Safe to rerun: it never deletes a cluster, namespace or volume.
# When: run first to create the lab, and again after changing the shared chart or its
# values. Changing infra/shards.yaml later needs the README's lab reset first.
# Next: scripts/migrate.sh.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: up.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
context=kind-shardshop

for tool in docker kind kubectl helm openssl python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done

# Snapshot the inventory so one run cannot deploy two different versions.
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cp "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" "$work/inventory.yaml"
export SHARDSHOP_INVENTORY="$work/inventory.yaml"
load_inventory
render infrastructure >"$work/infrastructure.yaml"
render namespace >"$work/namespace.yaml"

# The lab needs a 12 GB Docker VM (PLAN.md §5), which reports about 11.7 GiB.
mem=$(docker info --format '{{.MemTotal}}')
(( mem >= 11 * 1024**3 + 512 * 1024**2 )) \
    || { echo "Docker VM has $(( mem / 1024**2 )) MiB; set Docker Desktop to 12 GB" >&2; exit 1; }

kind get clusters | grep -qx shardshop \
    || kind create cluster --config "$root/infra/kind.yaml" --wait 180s
kubectl --context "$context" wait --for=condition=Ready nodes --all --timeout=180s
# The operator comes from its verified, vendored chart; --wait covers its rollout.
helm --kube-context "$context" upgrade --install cnpg \
    "$root/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz" \
    --namespace cnpg-system --create-namespace \
    --values "$root/infra/helm/cnpg/values.yaml" --server-side=true --wait --timeout 3m \
    --hide-notes
# CNPG's CRDs carry no common label; waiting for all CRDs survives operator upgrades.
kubectl --context "$context" wait --for=condition=Established crd --all --timeout=180s
# Generate each login role's password once, before the DatabaseRoles that use it; every
# shard shares it. Existing Secrets are never overwritten, even on a startup rerun.
kubectl --context "$context" apply -f "$work/namespace.yaml"
assert_published_topology
credentials="$work"
for role in catalog_migrator product_app; do
    secret="${role//_/-}"
    existing=$(kubectl --context "$context" -n shardshop get secret "$secret" --ignore-not-found -o name)
    if [[ -z "$existing" ]]; then
        openssl rand -hex 32 | tr -d '\n' >"$credentials/password"
        kubectl --context "$context" -n shardshop create secret generic "$secret" \
            --type=kubernetes.io/basic-auth --from-literal="username=$role" \
            --from-file="password=$credentials/password"
    fi
done
kubectl --context "$context" apply -f "$work/infrastructure.yaml"
kubectl --context "$context" -n shardshop wait --for=condition=Ready \
    clusters.postgresql.cnpg.io --all --timeout=300s
# Roles first: a Database resource creates each schema with its owner role.
kubectl --context "$context" -n shardshop wait --for=jsonpath='{.status.applied}'=true \
    databaseroles.postgresql.cnpg.io --all --timeout=180s
kubectl --context "$context" -n shardshop wait --for=jsonpath='{.status.applied}'=true \
    databases.postgresql.cnpg.io --all --timeout=180s
# Routing is activated by migrate.sh only after every shard has passed migration.
echo "Provisioned ${#shards[@]} shards. Run scripts/migrate.sh to publish routing configuration."
