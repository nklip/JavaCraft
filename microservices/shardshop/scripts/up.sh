#!/usr/bin/env bash
# Bootstrap the local lab; Skaffold builds images and Helmfile deploys releases.
# Tools come from PATH. Safe to rerun: it never deletes a cluster, namespace or volume.
# When: run first to create the lab, after down.sh to start it again, and after changing
# the shared chart or its values. Changing infra/shards.yaml later needs the README's
# explicit reset/reseed first; incompatible node changes are rejected.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: up.sh' >&2; exit 1; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cluster_name=shardshop
context="kind-$cluster_name"
export KIND_EXPERIMENTAL_PROVIDER=docker
k() { kubectl --context "$context" --request-timeout=10s -n shardshop "$@"; }

render() {
    local mode=$1
    shift
    helm template shardshop "$work/chart" --namespace shardshop \
        --values "$work/environment.yaml" --values "$work/inventory.yaml" \
        --set-string "render=$mode" "$@" |
        if [[ "$mode" == inventory || "$mode" == kind-configuration ]]; then
            sed '/^# Source:/d; /^---$/d'
        else cat; fi
}

verify_kind() {
    local phase=$1
    shift
    echo "Verifying kind phase: $phase"
    # The initial clean install verified and installed these artifacts. Direct Failsafe goals
    # reuse them without rebuilding images or running unrelated suites at every gate.
    mvn -B -ntp -f "$root/pom.xml" -pl shardshop-testing -Pkind \
        failsafe:integration-test failsafe:verify \
        -Dit.test=KindIT \
        -Dshardshop.kind.tags="$phase" -Dshardshop.kind.snapshot="$work" \
        -Dshardshop.kind.cluster="$cluster_name" -Dshardshop.kind.fresh="$created_cluster" "$@"
}

# Share one fail-fast lock with stop and explicit cluster destruction.
lock="${TMPDIR:-/tmp}/shardshop-up.lock"
mkdir "$lock" 2>/dev/null || {
    echo "Another ShardShop lifecycle call owns $lock. If its process has ended, inspect the retained Jobs before removing that lock." >&2
    exit 1
}
work=
cleanup() {
    trap '' INT TERM
    if [[ -n "$work" && -f "$work/dns-probes.yaml" ]]; then
        k delete -f "$work/dns-probes.yaml" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    fi
    [[ -z "$work" ]] || rm -rf -- "$work"
    rmdir "$lock"
}
trap cleanup EXIT
# Bash defers these traps until its foreground command has completed, so a CLI
# mutation cannot outlive this invocation's lock when the shell is interrupted.
trap 'exit 130' INT
trap 'exit 143' TERM
for tool in mvn docker kind kubectl helm helmfile skaffold openssl jq; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
docker buildx version >/dev/null 2>&1 || { echo 'Docker Buildx is not available in Docker' >&2; exit 1; }
docker scout version >/dev/null 2>&1 || { echo 'Docker Scout is not available in Docker' >&2; exit 1; }

# Snapshot the inventory so one run cannot deploy two different versions.
work=$(mktemp -d)
probe_run="up-$(openssl rand -hex 6)"
cp -R "$root/infra/helm/shardshop" "$work/chart"
cp "$root/infra/helmfile.yaml.gotmpl" "$work/helmfile.yaml.gotmpl"
cp "$root/skaffold.yaml" "$work/skaffold.yaml"
printf '{}\n' >"$work/skaffold-global.yaml"
export SHARDSHOP_ROOT="$root" SHARDSHOP_SNAPSHOT="$work"
cp -R "$root/database" "$work/chart/migrations"
cp "$root/infra/kind.yaml" "$work/chart/kind.yaml"
cp "${SHARDSHOP_ENV_VALUES:-$root/infra/helm/shardshop/kind-values.yaml}" "$work/environment.yaml"
cp "$root/infra/helm/cnpg/values.yaml" "$work/operator-values.yaml"
cp "$root/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz" "$work/operator.tgz"
cp "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" "$work/inventory.yaml"
render inventory >"$work/inventory.json"
render kind-configuration >"$work/kind.json"
migration_image=$(jq -er '.data.migrationImage' "$work/inventory.json")
shards=()
while IFS= read -r shard; do shards+=("$shard"); done < <(jq -r '.data.shards' "$work/inventory.json")
render infrastructure >"$work/infrastructure.yaml"
render namespace >"$work/namespace.yaml"

# The lab needs a 12 GB Docker VM (PLAN.md §5), which reports about 11.7 GiB.
mem=$(docker info --format '{{.MemTotal}}')
(( mem >= 11 * 1024**3 + 512 * 1024**2 )) \
    || { echo "Docker VM has $(( mem / 1024**2 )) MiB; set Docker Desktop to 12 GB" >&2; exit 1; }

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
created_cluster=false
verify_kind kind-config
# Skaffold owns Docker recipes, artifact names and content tags. Scan every result,
# including cache hits, before any cluster mutation or image delivery.
(cd "$root" && skaffold build -f "$work/skaffold.yaml" --config "$work/skaffold-global.yaml" \
    --kube-context="$context" --cache-artifacts=false --file-output="$work/builds.json")
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

started_nodes=false
nodes=$(kind get nodes --name "$cluster_name")
if [[ -n "$nodes" ]]; then
    verify_kind kind-nodes
    for node in $nodes; do
        running=$(docker inspect --format '{{.State.Running}}' "$node")
        if [[ "$running" != true ]]; then
            docker start "$node" >/dev/null
            started_nodes=true
        fi
    done
else
    kind create cluster --name "$cluster_name" --config "$work/chart/kind.yaml" --wait 180s
    created_cluster=true
    nodes=$(kind get nodes --name "$cluster_name")
    verify_kind kind-nodes
fi
# Refresh the caller's kubeconfig so migrate.sh/deploy.sh and later commands use
# this endpoint too. kind respects KUBECONFIG and otherwise uses ~/.kube/config.
kind export kubeconfig --name "$cluster_name"
for attempt in {1..60}; do
    k get --raw /readyz >/dev/null 2>&1 && break
    (( attempt < 60 )) || { echo "$context API server did not start" >&2; exit 1; }
    sleep 2
done
k wait --for=condition=Ready nodes --all --timeout=180s
# These guards precede operator, namespace, credential and database reconciliation.
verify_kind kind-retention
# Refresh kindnet's downward-API HOST_IP after any retained node was restarted.
# Old processes can otherwise omit peer routes after Docker assigns new addresses.
if [[ "$started_nodes" == true ]]; then
    k -n kube-system rollout restart daemonset/kindnet
    k -n kube-system rollout status daemonset/kindnet --timeout=180s
fi
kubectl --context "$context" --request-timeout=30s apply -f "$work/namespace.yaml"
k -n kube-system rollout status deployment/coredns --timeout=180s
render dns-probes --set-literal "probe.nodes=${nodes//$'\n'/,}" \
    --set-literal "probe.run=$probe_run" >"$work/dns-probes.yaml"
verify_kind kind-dns
# Generate each login role's password once, before the DatabaseRoles that use it.
# Shard logins share credentials; existing Secrets are never overwritten on a rerun.
credentials="$work"
for role in catalog_migrator ordering_migrator product_app order_app ordering_cdc ledger_migrator ledger_app; do
    secret="${role//_/-}"
    existing=$(kubectl --context "$context" -n shardshop get secret "$secret" --ignore-not-found -o name)
    if [[ -z "$existing" ]]; then
        openssl rand -hex 32 | tr -d '\n' >"$credentials/password"
        kubectl --context "$context" -n shardshop create secret generic "$secret" \
            --type=kubernetes.io/basic-auth --from-literal="username=$role" \
            --from-file="password=$credentials/password"
    fi
done
# Bootstrap has its own Helmfile selection because fresh allocator identity must be
# initialized after admission protection, before any application can issue IDs.
export SHARDSHOP_FRESH="$created_cluster"
helmfile -f "$work/helmfile.yaml.gotmpl" -l stage=infrastructure sync --skip-deps --skip-needs
if [[ "$created_cluster" == true ]]; then
    verify_kind kind-admission-bootstrap
    registry_uid=$(k create configmap shardshop-snowflake-generators --from-literal=highWaterMark=0 \
        -o jsonpath='{.metadata.uid}')
    render generator-identity --set-literal "generatorRegistryUid=$registry_uid" >"$work/generator-identity.yaml"
    k create -f "$work/generator-identity.yaml"
fi
verify_kind kind-allocator
# Skaffold's standalone build does not load kind images. This adapter keeps the
# existing qualification-to-manifest check; Helmfile is the only deployment owner.
printf '{"images":{}}\n' >"$work/images.json"
while IFS=$'\t' read -r image tag; do
    load_image "$image" "$tag"
done <"$work/builds.tsv"
helmfile -f "$work/helmfile.yaml.gotmpl" -l stage=application sync --skip-deps --skip-needs
echo 'ShardShop deployment verified: current images, seeded catalog, routing and reader traffic; reader remains running.'
