#!/usr/bin/env bash
# Create or reuse the local ShardShop kind cluster, install CNPG with Helm, apply the overlay.
# Tools come from PATH. Safe to rerun: it never deletes a cluster, namespace or volume.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
context=kind-shardshop

for tool in docker kind kubectl helm; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done

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
kubectl --context "$context" apply -k "$root/infra/k8s/overlays/kind"
kubectl --context "$context" -n shardshop wait --for=condition=Ready \
    clusters.postgresql.cnpg.io --all --timeout=300s
