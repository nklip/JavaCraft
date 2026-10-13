#!/usr/bin/env bash
# Build the application images and the Flyway migration image, print their OS package inventory and advisories,
# and load them into every kind node. A critical or high advisory stops the script before the load, unless a
# reviewed statement in infra/images/application/vex/ marks it not_affected.
# When: flyway after up.sh and before migrate.sh; an application after its Maven build and before
# scripts/deploy.sh. Step 4.8 builds the order, ledger and order producer images with this script.
set -euo pipefail

usage='Usage: build-application-images.sh flyway|product|order|ledger|product-seeder|product-reader|order-producer ...'
(( $# >= 1 )) || { echo "$usage" >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
for tool in docker kind python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
docker scout version >/dev/null 2>&1 || { echo 'Docker Scout is not available in Docker' >&2; exit 1; }
nodes=$(kind get nodes --name shardshop)
[[ -n "$nodes" ]] || { echo 'No shardshop kind nodes found; run scripts/up.sh first' >&2; exit 1; }

for image in "$@"; do
    case "$image" in
        flyway) ;;
        product|order|ledger) module="shardshop-$image" ;;
        product-seeder|product-reader|order-producer) module="shardshop-workload/shardshop-$image" ;;
        *) echo "$usage" >&2; exit 1 ;;
    esac
    reviewed=''
    if [[ "$image" == flyway ]]; then
        # The migration Jobs use the digest that the chart values pin, thus the build must reproduce it.
        command -v helm >/dev/null || { echo 'helm is not on PATH' >&2; exit 1; }
        load_inventory
        tag=${migration_image%@*}
        reviewed=${migration_image#*@}
        context="$root/infra/images/flyway"
        dockerfile="$context/Dockerfile"
    else
        context="$root/$module/target/quarkus-app"
        [[ -f "$context/quarkus-run.jar" ]] || { echo "Build $module with Maven first" >&2; exit 1; }
        tag="docker.io/library/shardshop-$image:local"
        dockerfile="$root/infra/images/application/Dockerfile"
    fi
    docker buildx build --platform linux/arm64 --build-arg SOURCE_DATE_EPOCH=0 \
        --provenance=false --sbom=false --load --file "$dockerfile" --tag "$tag" "$context"

    echo "OS package inventory of $tag:"
    docker scout sbom --format list --only-package-type deb "local://$tag"
    echo "Advisories of $tag, all package types and severities:"
    docker scout cves --format only-packages --only-vuln-packages "local://$tag"
    # Reviewed OpenVEX statements remove only the findings that they name for each package.
    echo "Critical and high advisories of $tag without a reviewed not_affected statement:"
    status=0
    docker scout cves --exit-code --only-severity critical,high --format only-packages \
        --only-vuln-packages --vex-location "$root/infra/images/application/vex" \
        --vex-author 'ShardShop image review' --ignore-suppressed "local://$tag" || status=$?
    case "$status" in
        0) ;;
        2) echo "$tag has critical or high advisories; it was not loaded into kind" >&2; exit 1 ;;
        *) echo "Docker Scout could not check $tag; it was not loaded into kind" >&2; exit 1 ;;
    esac

    # Docker's image ID is a config digest, not the manifest digest that Kubernetes uses.
    # Import one node at a time to bound memory use, and require the same digest on each.
    digests=()
    for node in $nodes; do
        kind load docker-image "$tag" --name shardshop --nodes "$node"
        digest=$(docker exec "$node" crictl inspecti "$tag" | python3 -c '
import json, sys
digests = {reference.split("@", 1)[1] for reference in json.load(sys.stdin)["status"]["repoDigests"]}
if len(digests) != 1:
    sys.exit(f"Expected one manifest digest, found {sorted(digests)}")
print(digests.pop())
')
        if [[ -n "$reviewed" && "$digest" != "$reviewed" ]]; then
            echo "$tag on $node has $digest, not the reviewed $reviewed; review the build and update the image pin" >&2
            exit 1
        fi
        # Docker archives first receive an import-date repository name in containerd.
        # Register the digest under the repository name that the manifests use.
        docker exec "$node" ctr --namespace=k8s.io images tag --force \
            "$tag" "${tag%:*}@$digest" >/dev/null
        digests+=("$digest")
    done
    [[ $(printf '%s\n' "${digests[@]}" | sort -u | wc -l) -eq 1 ]] \
        || { echo "kind nodes report different digests for $tag" >&2; exit 1; }
    echo "Loaded ${tag%:*}@${digests[0]} on every kind node."
done
