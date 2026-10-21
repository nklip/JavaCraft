#!/usr/bin/env bash
# Build, migrate, deploy and restart applications on an existing ShardShop lab.
# Infrastructure provisioning and updates belong to cluster-create.sh.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: up.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/lib/lifecycle.sh"
lifecycle_init
require_tools mvn docker kind kubectl helm helmfile skaffold openssl jq
docker buildx version >/dev/null 2>&1 || { echo 'Docker Buildx is not available in Docker' >&2; exit 1; }
docker scout version >/dev/null 2>&1 || { echo 'Docker Scout is not available in Docker' >&2; exit 1; }
nodes=$(kind get nodes --name "$cluster_name")
[[ -n "$nodes" ]] || {
    echo 'No shardshop cluster exists. Run scripts/cluster-create.sh, then scripts/up.sh.' >&2
    exit 1
}
snapshot_configuration
cp "$root/skaffold.yaml" "$work/skaffold.yaml"
printf '{}\n' >"$work/skaffold-global.yaml"
migration_image=$(jq -er '.data.migrationImage' "$work/inventory.json")
require_memory

load_image() {
    local image=$1 tag=$2 node digest expected actual
    local digests=()
    expected=$(cat "$work/$image.id")
    actual=$(docker image inspect --format '{{.Id}}' "$tag")
    [[ "$actual" == "$expected" ]] \
        || { echo "$tag changed after this build; refusing to load it" >&2; exit 1; }
    # Use the loaded platform manifest, not a CRI archive-index digest containing tag annotations.
    # Import one node at a time to bound memory use, and require the same digest on each.
    for node in $nodes; do
        kind load docker-image "$tag" --name "$cluster_name" --nodes "$node"
        digest=$(docker exec "$node" ctr --namespace=k8s.io images ls "name==$tag" | awk 'NR == 2 {print $3}')
        docker exec "$node" ctr --namespace=k8s.io content get "$digest" |
            jq -e --arg expected "$expected" '
                if .config.digest == $expected then true
                else error("Loaded manifest differs from the qualified build") end' >/dev/null
        if [[ "$image" == flyway && "$digest" != "${migration_image#*@}" ]]; then
            echo "$tag on $node has $digest, not the reviewed ${migration_image#*@}; review the build and update the image pin" >&2
            exit 1
        fi
        docker exec "$node" ctr --namespace=k8s.io images tag --force \
            "$tag" "${tag%:*}@$digest" >/dev/null
        # kind also names each import import-<date>@<digest>, without a registry. When the tag
        # moves to a later build, the CRI uses that name for the earlier image, cannot find it
        # and fails with CreateContainerError. A tag for each digest prevents this.
        docker exec "$node" ctr --namespace=k8s.io images tag --force \
            "$tag" "${tag%:*}:sha256-${digest#sha256:}" >/dev/null
        digests+=("$digest")
    done
    [[ $(printf '%s\n' "${digests[@]}" | sort -u | wc -l) -eq 1 ]] \
        || { echo "kind nodes report different digests for $tag" >&2; exit 1; }
    echo "Loaded ${tag%:*}@${digests[0]} on every kind node."
    if [[ "$image" != flyway ]]; then
        jq --arg name "$image" --arg image "${tag%:*}@${digests[0]}" \
            '.images[$name] = $image' "$work/images.json" >"$work/images-next.json"
        mv "$work/images-next.json" "$work/images.json"
    fi
}

# Fail before touching the cluster if any module, image build or advisory gate fails.
# clean removes stale packages; -f restricts the reactor and works from any directory.
echo 'Building ShardShop from current sources.'
mvn -B -ntp -f "$root/pom.xml" clean install -DinstallAtEnd=true
verify_kind kind-config
# Skaffold owns Docker recipes, artifact names and content tags. Scan every result,
# including cache hits, before any cluster mutation or image delivery.
# Disabling its cache enables asynchronous pruning that can delete rebuilt images.
# A fresh invocation cache forces builds without enabling that pruning.
(cd "$root" && skaffold build -f "$work/skaffold.yaml" --config "$work/skaffold-global.yaml" \
    --kube-context="$context" --cache-artifacts=true --cache-file="$work/skaffold-cache" \
    --file-output="$work/builds.json")
jq -r '.builds[] | [(.imageName | split("/")[-1] | sub("^shardshop-"; "")), .tag] | @tsv' \
    "$work/builds.json" >"$work/builds.tsv"
while IFS=$'\t' read -r image tag; do
    docker image inspect --format '{{.Id}}' "$tag" >"$work/$image.id"
    docker scout sbom --format list --only-package-type deb "local://$tag"
    docker scout cves --format only-packages --only-vuln-packages "local://$tag"
    docker scout cves --exit-code --only-severity critical,high --format only-packages \
        --only-vuln-packages --vex-location "$root/infra/images/application/vex" \
        --vex-author '^ShardShop image review$' --ignore-suppressed "local://$tag"
done <"$work/builds.tsv"

# Resume retained nodes and observe the foundation; never provision or repair it.
# If the foundation is missing or needs an update, run cluster-create.sh first.
resume_cluster
verify_dns
verify_kind kind-allocator
export SHARDSHOP_FRESH=false
# Skaffold's standalone build does not load kind images. This adapter keeps the
# existing qualification-to-manifest check; Helmfile is the only deployment owner.
# Every startup rolls all application Deployments, including an unchanged build. Keep the
# token in the invocation values so Helm and the acceptance gate use the same value.
restart_token="restart-$(openssl rand -hex 12)"
jq -n --arg token "$restart_token" '{images: {}, restartToken: $token}' >"$work/images.json"
while IFS=$'\t' read -r image tag; do
    load_image "$image" "$tag"
done <"$work/builds.tsv"
helmfile -f "$work/helmfile.yaml.gotmpl" -l stage=application sync --skip-deps --skip-needs
echo 'ShardShop deployment verified: current images, seeded catalog, routing, reader traffic and order saga outcomes; workloads remain running.'

# Deployment is complete. Release the snapshot and lock before the foreground
# tunnels so down.sh and later deployments remain available in another terminal.
lifecycle_cleanup
trap - EXIT INT TERM
page_access
