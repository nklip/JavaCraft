#!/usr/bin/env bash
# Verify real launchers/RBAC in kind-shardshop after building product and order.
# Uses a temporary verification image, not the application images from step 3.9.
# Burns about four live generator slots; never resets/deletes allocator state.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: verify-generator-allocation.sh' >&2; exit 1; }
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
for tool in docker kind kubectl python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
for service in product order; do
    [[ -f "$root/shardshop-$service/target/quarkus-app/quarkus-run.jar" ]] \
        || { echo "Build shardshop-$service before running this drill." >&2; exit 1; }
done
k() { kubectl --context kind-shardshop --request-timeout=10s -n shardshop "$@"; }
fail() { echo "$*" >&2; exit 1; }
high_water() { k get configmap shardshop-snowflake-generators -o jsonpath='{.data.highWaterMark}'; }
bash "$root/scripts/generator-registry.sh" check
k get configmap shardshop-routing >/dev/null
work=$(mktemp -d)
run="generator-check-$(date +%s)-$$"
image="shardshop-generator-verification:$run"
selector="shardshop.javacraft/generator-verification=$run"
nodes=()
while IFS= read -r node; do nodes+=("$node"); done < <(kind get nodes --name shardshop)
cleanup() {
    local result=$? node
    trap - EXIT
    if (( result != 0 )); then
        k get pods -l "$selector" -o wide >&2 || true
        k logs -l "$selector" --all-containers --prefix --tail=30 >&2 || true
    fi
    k delete pods -l "$selector" --ignore-not-found --wait=true --timeout=40s >/dev/null || true
    for node in "${nodes[@]}"; do
        docker exec "$node" crictl rmi "docker.io/library/$image" >/dev/null 2>&1 || true
    done
    docker image rm "$image" >/dev/null 2>&1 || true
    rm -rf -- "$work"
    exit "$result"
}
trap cleanup EXIT

# Read only the two reference scalars from the lock's known image sections; no YAML
# dependency is needed, and a missing/unpinned reference stops the drill.
references=$(python3 - "$root/versions.lock.yaml" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
for name in ("kind_node", "application_jre"):
    section = re.search(r"^  " + name + r":\n((?:    .*\n|\n)+)", text, re.M)
    reference = re.search(r"^    reference: (\S+)$", section[1], re.M) if section else None
    if not reference or not re.fullmatch(r"docker.io/[a-z0-9/]+@sha256:[0-9a-f]{64}", reference[1]):
        sys.exit(f"Missing digest-pinned {name} reference in versions.lock.yaml")
    print(reference[1])
PY
)
kubectl_image=${references%%$'\n'*}
jre_image=${references#*$'\n'}
for service in product order; do cp -R "$root/shardshop-$service/target/quarkus-app" "$work/$service"; done
cat >"$work/Dockerfile" <<DOCKERFILE
FROM $kubectl_image AS tooling
FROM $jre_image
COPY --from=tooling /usr/bin/kubectl /usr/bin/kubectl
COPY product /product
COPY order /order
USER 10001:10001
ENV HOME=/tmp
DOCKERFILE
docker build --tag "$image" "$work"
kind load docker-image --name shardshop "$image"

access() {
    local account=$1 verb=$2 resource=$3 expected=$4 actual
    actual=$(k auth can-i "$verb" "$resource" --as="system:serviceaccount:shardshop:$account") || true
    [[ "$actual" == "$expected" ]] || fail "$account must have '$expected' for $verb $resource (got '$actual')."
}
for service in product order; do
    for verb in get patch; do
        access "shardshop-$service" "$verb" configmap/shardshop-snowflake-generators yes
        access "shardshop-$service" "$verb" configmap/shardshop-generator-identity no
        access "shardshop-$service" "$verb" configmap/shardshop-routing no
    done
    for verb in create list watch; do
        access "shardshop-$service" "$verb" configmaps no
    done
    for verb in update delete; do
        access "shardshop-$service" "$verb" configmap/shardshop-snowflake-generators no
    done
done
for account in default shardshop-ledger shardshop-product-seeder shardshop-product-reader shardshop-order-producer; do
    for verb in get patch; do access "$account" "$verb" configmap/shardshop-snowflake-generators no; done
done

before=$(high_water)
(( before <= 1013 )) || fail 'This drill needs ten unallocated slots; it never resets an exhausted registry.'
echo 'This drill permanently consumes fresh generator slots (normally four, with room for up to ten).'
python3 - "$work" "$image" "$run" <<'PY'
import json, pathlib, sys
work, image, run = sys.argv[1:]
def pod(suffix, service, restart="Never", stale=False):
    identity = {"value": "00000000-0000-0000-0000-000000000000"} if stale else {
        "valueFrom": {"configMapKeyRef": {"name": "shardshop-generator-identity", "key": "registryUid"}}}
    return {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": f"{run}-{suffix}", "namespace": "shardshop",
            "labels": {"shardshop.javacraft/generator-verification": run}}, "spec": {
        "serviceAccountName": f"shardshop-{service}", "restartPolicy": restart, "activeDeadlineSeconds": 90,
        "terminationGracePeriodSeconds": 5, "securityContext": {"runAsNonRoot": True, "runAsUser": 10001,
            "runAsGroup": 10001, "fsGroup": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
        "containers": [{"name": "launcher", "image": image, "imagePullPolicy": "Never",
            "command": ["/usr/bin/java", "-cp", f"/{service}/lib/main/*",
                "dev.nklip.javacraft.shardshop.idgen.allocation.GeneratorLauncher", service, f"/{service}"],
            "env": [{"name": "SHARDSHOP_GENERATOR_REGISTRY_UID", **identity},
                {"name": "SHARDSHOP_ROUTING_CONFIG", "value": "file:/etc/shardshop/routing/application.properties"}],
            "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                "capabilities": {"drop": ["ALL"]}},
            "resources": {"requests": {"cpu": "100m", "memory": "128Mi"}, "limits": {"cpu": "1", "memory": "256Mi"}},
            "volumeMounts": [{"name": "routing", "mountPath": "/etc/shardshop/routing", "readOnly": True},
                {"name": "tmp", "mountPath": "/tmp"}]}],
        "volumes": [{"name": "routing", "configMap": {"name": "shardshop-routing"}},
            {"name": "tmp", "emptyDir": {"sizeLimit": "64Mi"}}]}}
for name, manifest in {
    "concurrent": {"apiVersion": "v1", "kind": "List", "items": [pod("product", "product"), pod("order", "order")]},
    "restart": pod("restart", "product", "Always"), "stale": pod("stale", "product", stale=True)
}.items():
    pathlib.Path(work, name + ".json").write_text(json.dumps(manifest))
PY
allocation() {
    python3 - "$1" "$2" <<'PY'
import pathlib, re, sys
ids = re.findall(r"^Reserved generator ID ([1-9][0-9]{0,3}) for " + sys.argv[1] + r" JVM startup\.$",
                 pathlib.Path(sys.argv[2]).read_text(), re.M)
if len(ids) != 1 or int(ids[0]) > 1023:
    sys.exit("Expected exactly one successful allocation in launcher logs")
print(ids[0])
PY
}
k create -f "$work/concurrent.json"
k wait "pod/$run-product" "pod/$run-order" --for=jsonpath='{.status.phase}'=Succeeded --timeout=100s
ids=()
for service in product order; do
    k logs "$run-$service" >"$work/$service.log"
    ids+=("$(allocation "$service" "$work/$service.log")")
done

pod_uid=$(k create -f "$work/restart.json" -o jsonpath='{.metadata.uid}')
deadline=$((SECONDS + 85))
restarted=false
while (( SECONDS < deadline )); do
    count=$(k get pod "$run-restart" -o jsonpath='{.status.containerStatuses[0].restartCount}')
    if [[ -n "$count" ]] && (( count >= 1 )) \
        && k logs "$run-restart" --previous >"$work/previous.log" 2>/dev/null \
        && k logs "$run-restart" >"$work/current.log" 2>/dev/null \
        && previous=$(allocation product "$work/previous.log" 2>/dev/null) \
        && current=$(allocation product "$work/current.log" 2>/dev/null); then
        restarted=true
        break
    fi
    sleep 1
done
[[ "$restarted" == true ]] || fail 'The same-pod container restart did not produce two confirmed allocations.'
[[ "$(k get pod "$run-restart" -o jsonpath='{.metadata.uid}')" == "$pod_uid" ]] || fail 'Restart pod UID changed.'
ids+=("$previous" "$current")
k delete pod "$run-restart" --wait=true --timeout=40s >/dev/null
echo "Same pod $pod_uid reserved $previous then $current across a container restart."

stale_before=$(high_water)
k create -f "$work/stale.json"
k wait "pod/$run-stale" --for=jsonpath='{.status.phase}'=Failed --timeout=100s
k logs "$run-stale" >"$work/stale.log"
[[ "$(high_water)" == "$stale_before" ]] || fail 'High-water changed during the stale-identity refusal.'
python3 - "$work/stale.log" <<'PY'
import pathlib, sys
log = pathlib.Path(sys.argv[1]).read_text()
if "Generator registry is stale or has been replaced;" not in log or "Reserved generator ID " in log or "Quarkus" in log:
    sys.exit("Stale identity did not fail before allocation/application startup")
PY
after=$(high_water)
# A pre-drill snapshot is stale even though it has the original registry UID.
if k patch configmap shardshop-snowflake-generators --type=merge --dry-run=server \
    -p "{\"data\":{\"highWaterMark\":\"$before\"}}" >"$work/rollback.log" 2>&1; then
    fail 'A stale allocator snapshot passed admission.'
fi
python3 - "$work/rollback.log" <<'PY'
import pathlib, sys
error = pathlib.Path(sys.argv[1]).read_text()
if "shardshop-generator-retention" not in error or "denied request" not in error:
    sys.exit("Stale allocator restore was not rejected by the retention policy")
PY
python3 - "$before" "$after" "${ids[@]}" <<'PY'
import sys
before, after, *ids = map(int, sys.argv[1:])
if len(ids) != 4 or len(set(ids)) != 4 or not all(before < value <= after for value in ids) or not 4 <= after - before <= 10:
    sys.exit("Concurrent/restarted allocations were not distinct within the retained high-water range")
print(f"Verified four distinct allocations {ids}; retained high-water advanced {before} -> {after}.")
PY
bash "$root/scripts/generator-registry.sh" check
echo 'Generator allocation drill passed: concurrent services, same-pod restart, stale identity, narrow RBAC and retained state.'
