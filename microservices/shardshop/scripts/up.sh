#!/usr/bin/env bash
# Build and qualify the current ShardShop images, provision the lab, and load every node.
# Tools come from PATH. Safe to rerun: it never deletes a cluster, namespace or volume.
# When: run first to create the lab, after down.sh to start it again, and after changing
# the shared chart or its values. Changing infra/shards.yaml later needs the README's
# explicit reset/reseed first; incompatible node changes are rejected. Migrations and application deployment still use migrate.sh and deploy.sh.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: up.sh' >&2; exit 1; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cluster_name=shardshop
context="kind-$cluster_name"
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
    echo "Verifying kind phase: $1"
    # The initial clean install verified and installed these artifacts. Direct Failsafe goals
    # reuse them without rebuilding images or running unrelated suites at every gate.
    mvn -B -ntp -f "$root/pom.xml" -pl shardshop-testing -Pkind \
        failsafe:integration-test failsafe:verify \
        -Dit.test=KindIT \
        -Dshardshop.kind.tags="$1" -Dshardshop.kind.snapshot="$work" \
        -Dshardshop.kind.cluster="$cluster_name" -Dshardshop.kind.fresh="$created_cluster"
}

for tool in mvn docker kind kubectl helm openssl jq; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
docker buildx version >/dev/null 2>&1 || { echo 'Docker Buildx is not available in Docker' >&2; exit 1; }
docker scout version >/dev/null 2>&1 || { echo 'Docker Scout is not available in Docker' >&2; exit 1; }

# Snapshot the inventory so one run cannot deploy two different versions.
work=$(mktemp -d)
cleanup() {
    if [[ -f "$work/dns-probes.yaml" ]]; then
        k delete -f "$work/dns-probes.yaml" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    fi
    rm -rf -- "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
probe_run="up-$(openssl rand -hex 6)"
cp -R "$root/infra/helm/shardshop" "$work/chart"
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

build_image() {
    local image=$1 tag=$2 module build_context dockerfile status=0
    if [[ "$image" == flyway ]]; then
        build_context="$root/infra/images/flyway"
        dockerfile="$build_context/Dockerfile"
    else
        case "$image" in
            product|order|ledger) module="shardshop-$image" ;;
            product-seeder|product-reader|order-producer) module="shardshop-workload/shardshop-$image" ;;
            *) echo "Unknown application: $image" >&2; exit 1 ;;
        esac
        build_context="$root/$module/target/quarkus-app"
        [[ -f "$build_context/quarkus-run.jar" ]] \
            || { echo "The current Maven build did not package $module" >&2; exit 1; }
        dockerfile="$root/infra/images/application/Dockerfile"
    fi
    docker buildx build --platform linux/arm64 --build-arg SOURCE_DATE_EPOCH=0 \
        --provenance=false --sbom=false --load --file "$dockerfile" --tag "$tag" \
        --iidfile "$work/$image.id" "$build_context"

    echo "OS package inventory of $tag:"
    docker scout sbom --format list --only-package-type deb "local://$tag"
    echo "Advisories of $tag, all package types and severities:"
    docker scout cves --format only-packages --only-vuln-packages "local://$tag"
    echo "Critical and high advisories of $tag without a reviewed not_affected statement:"
    # --vex-author is a pattern. Anchor it, because it also accepts authors that contain the text.
    docker scout cves --exit-code --only-severity critical,high --format only-packages \
        --only-vuln-packages --vex-location "$root/infra/images/application/vex" \
        --vex-author '^ShardShop image review$' --ignore-suppressed "local://$tag" || status=$?
    case "$status" in
        0) ;;
        2) echo "$tag has critical or high advisories; no images were loaded into kind" >&2; exit 1 ;;
        *) echo "Docker Scout could not check $tag; no images were loaded into kind" >&2; exit 1 ;;
    esac
}

load_image() {
    local image=$1 tag=$2 node digest expected actual
    local digests=()
    expected=$(cat "$work/$image.id")
    actual=$(docker image inspect --format '{{.Id}}' "$tag")
    [[ "$actual" == "$expected" ]] \
        || { echo "$tag changed after this build; refusing to load it" >&2; exit 1; }
    # Docker config IDs identify our builds; Kubernetes requires the CRI manifest digest.
    # Import one node at a time to bound memory use, and require the same digest on each.
    for node in $nodes; do
        kind load docker-image "$tag" --name "$cluster_name" --nodes "$node"
        digest=$(docker exec "$node" crictl inspecti "$tag" | jq -er '
            [.status.repoDigests[] | split("@")[1]] | unique |
            if length == 1 and (.[0] | test("^sha256:[0-9a-f]{64}$")) then .[0]
            else error("Expected one CRI manifest digest") end')
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
}

# Fail before touching the cluster if any module, image build or advisory gate fails.
# clean removes stale packages; -f restricts the reactor and works from any directory.
echo 'Building ShardShop from current sources.'
mvn -B -ntp -f "$root/pom.xml" clean install -DinstallAtEnd=true
created_cluster=false
verify_kind kind-config
# Step 4.8 adds order, ledger and order-producer when their deployments are implemented.
applications=(product product-seeder product-reader)
build_image flyway "${migration_image%@*}"
for application in "${applications[@]}"; do
    build_image "$application" "docker.io/library/shardshop-$application:local"
done

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
# The operator comes from its verified, vendored chart; --wait covers its rollout.
helm --kube-context "$context" upgrade --install cnpg \
    "$work/operator.tgz" \
    --namespace cnpg-system --create-namespace \
    --values "$work/operator-values.yaml" --server-side=true --wait --timeout 3m \
    --hide-notes
# CNPG's CRDs carry no common label; waiting for all CRDs survives operator upgrades.
kubectl --context "$context" wait --for=condition=Established crd --all --timeout=180s
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
kubectl --context "$context" --request-timeout=30s apply -f "$work/infrastructure.yaml"
if [[ "$created_cluster" == true ]]; then
    verify_kind kind-admission-bootstrap
    registry_uid=$(k create configmap shardshop-snowflake-generators --from-literal=highWaterMark=0 \
        -o jsonpath='{.metadata.uid}')
    render generator-identity --set-literal "generatorRegistryUid=$registry_uid" >"$work/generator-identity.yaml"
    k create -f "$work/generator-identity.yaml"
fi
verify_kind kind-allocator
verify_kind kind-infrastructure
load_image flyway "${migration_image%@*}"
for application in "${applications[@]}"; do
    load_image "$application" "docker.io/library/shardshop-$application:local"
done
# Publications remain pending until ordering migrations create their outbox tables.
# CNPG retries reconciliation; migrate.sh waits for them before activating routing.
verify_kind kind-allocator
echo "Provisioned ${#shards[@]} shards and the ledger database; built, qualified and loaded the current images. Run scripts/migrate.sh, then scripts/deploy.sh <run-name>."
