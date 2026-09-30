#!/usr/bin/env bash
# Build the pinned OpenJDK migration image and make it available on every kind node.
# When: migrate.sh calls it before creating any migration Job; run it by hand after
# up.sh only to prepare or re-check the image separately.
set -euo pipefail
[[ $# == 0 ]] || { echo 'Usage: build-migration-image.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
load_inventory
tag=${migration_image%@*}

# Fixed creation timestamps and pinned inputs keep rebuilds reproducible.
docker buildx build --platform linux/arm64 --build-arg SOURCE_DATE_EPOCH=0 \
    --provenance=false --sbom=false --load --tag "$tag" "$root/infra/images/flyway"
# Docker's image ID is a config digest, not the manifest digest Kubernetes uses.
# Check the actual CRI repository digest on every node before any Job can start.
nodes=$(kind get nodes --name shardshop)
[[ -n "$nodes" ]] || { echo 'No shardshop kind nodes found' >&2; exit 1; }
for node in $nodes; do
    # Import one node at a time to bound memory use on the laptop.
    kind load docker-image "$tag" --name shardshop --nodes "$node"
    docker exec "$node" crictl inspecti "$tag" | python3 -c '
import json, sys
expected = sys.argv[1].split("@", 1)[1]
digests = json.load(sys.stdin)["status"]["repoDigests"]
if not any(ref.endswith("@" + expected) for ref in digests):
    sys.exit("Migration image differs from the reviewed digest; review the build and update the image lock before migration")
' "$migration_image"
    # Docker archives initially receive an import-date repository name in containerd.
    # Register the reviewed digest under the repository name used by the Job.
    docker exec "$node" ctr --namespace=k8s.io images tag --force \
        "$tag" "${tag%:*}@${migration_image#*@}" >/dev/null
done
