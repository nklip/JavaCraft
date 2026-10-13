#!/usr/bin/env bash
# Build and qualify the current ShardShop images, provision the lab, and load every node.
# Tools come from PATH. Safe to rerun: it never deletes a cluster, namespace or volume.
# When: run first to create the lab, after down.sh to start it again, and after changing
# the shared chart or its values. Changing infra/shards.yaml later needs the README's
# lab reset first. Migrations and application deployment still use migrate.sh and deploy.sh.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: up.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
context=kind-shardshop

for tool in mvn docker kind kubectl helm openssl python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
docker buildx version >/dev/null 2>&1 || { echo 'Docker Buildx is not available in Docker' >&2; exit 1; }
docker scout version >/dev/null 2>&1 || { echo 'Docker Scout is not available in Docker' >&2; exit 1; }

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
        kind load docker-image "$tag" --name shardshop --nodes "$node"
        digest=$(docker exec "$node" crictl inspecti "$tag" | python3 -c '
import json, sys
digests = {reference.split("@", 1)[1] for reference in json.load(sys.stdin)["status"]["repoDigests"]}
if len(digests) != 1:
    sys.exit(f"Expected one manifest digest, found {sorted(digests)}")
print(digests.pop())
')
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
mvn -B -ntp -f "$root/pom.xml" clean verify
# Step 4.8 adds order, ledger and order-producer when their deployments are implemented.
applications=(product product-seeder product-reader)
build_image flyway "${migration_image%@*}"
for application in "${applications[@]}"; do
    build_image "$application" "docker.io/library/shardshop-$application:local"
done

if kind get clusters | grep -qx shardshop; then
    # down.sh stops the node containers; starting them keeps volumes and cluster state.
    kind get nodes --name shardshop | xargs docker start >/dev/null
    # A restarted API server needs a few seconds before kubectl can wait on nodes.
    for attempt in {1..60}; do
        kubectl --context "$context" --request-timeout=5s get --raw /readyz >/dev/null 2>&1 && break
        (( attempt < 60 )) || { echo 'The kind-shardshop API server did not start' >&2; exit 1; }
        sleep 2
    done
else
    kind create cluster --config "$root/infra/kind.yaml" --wait 180s
fi
kubectl --context "$context" wait --for=condition=Ready nodes --all --timeout=180s
# The operator comes from its verified, vendored chart; --wait covers its rollout.
helm --kube-context "$context" upgrade --install cnpg \
    "$root/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz" \
    --namespace cnpg-system --create-namespace \
    --values "$root/infra/helm/cnpg/values.yaml" --server-side=true --wait --timeout 3m \
    --hide-notes
# CNPG's CRDs carry no common label; waiting for all CRDs survives operator upgrades.
kubectl --context "$context" wait --for=condition=Established crd --all --timeout=180s
# Generate each login role's password once, before the DatabaseRoles that use it.
# Shard logins share credentials; existing Secrets are never overwritten on a rerun.
kubectl --context "$context" apply -f "$work/namespace.yaml"
assert_published_topology
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
kubectl --context "$context" apply -f "$work/infrastructure.yaml"
kubectl --context "$context" -n shardshop wait --for=condition=Ready \
    clusters.postgresql.cnpg.io --all --timeout=300s
# After down.sh the stored statuses predate the restart, so the waits above can pass
# early. Wait until each cluster's -rw Service really accepts connections.
# The probe uses the Service name, thus it also needs cluster DNS on the primary's node.
for cluster in "${shards[@]}" ledger-db; do
    for attempt in {1..60}; do
        primary=$(k get cluster "$cluster" -o jsonpath='{.status.currentPrimary}')
        k exec "$primary" -c postgres -- pg_isready -q -h "$cluster-rw" -t 5 >/dev/null 2>&1 && break
        (( attempt < 60 )) || { echo "$cluster-rw does not accept connections from $primary" >&2; exit 1; }
        sleep 2
    done
done
# Roles first: a Database resource creates each schema with its owner role.
# Compare each status with its current generation, including operator spec updates.
for attempt in {1..90}; do
    pending=$(k get databaseroles.postgresql.cnpg.io -o json | python3 -c '
import json, sys
roles = json.load(sys.stdin)["items"]
pending = [role["metadata"]["name"] for role in roles
           if role.get("status", {}).get("observedGeneration") != role["metadata"]["generation"]
           or role.get("status", {}).get("applied") is not True]
print(", ".join(pending) if roles else "no DatabaseRoles found")
')
    [[ -z "$pending" ]] && break
    (( attempt < 90 )) || { echo "DatabaseRoles not applied at their current generation: $pending" >&2; exit 1; }
    sleep 2
done
databases=("${shards[@]/%/-shardshop}" ledger-db-ledger)
for database in "${databases[@]}"; do
    # Adding a schema must not pass on the previous Database generation's status.
    generation=$(k get "database/$database" -o jsonpath='{.metadata.generation}')
    k wait "database/$database" --for=jsonpath='{.status.observedGeneration}'="$generation" --timeout=180s
    k wait "database/$database" --for=jsonpath='{.status.applied}'=true --timeout=180s
done
nodes=$(kind get nodes --name shardshop)
[[ -n "$nodes" ]] || { echo 'No shardshop kind nodes found after provisioning' >&2; exit 1; }
load_image flyway "${migration_image%@*}"
for application in "${applications[@]}"; do
    load_image "$application" "docker.io/library/shardshop-$application:local"
done
# Publications remain pending until ordering migrations create their outbox tables.
# CNPG retries reconciliation; migrate.sh waits for them before activating routing.
bash "$root/scripts/generator-registry.sh" check
echo "Provisioned ${#shards[@]} shards and the ledger database; built, qualified and loaded the current images. Run scripts/migrate.sh, then scripts/deploy.sh <run-name>."
