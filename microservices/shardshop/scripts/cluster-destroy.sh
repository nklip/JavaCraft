#!/usr/bin/env bash
# Permanently delete the ShardShop kind lab, its storage and kubeconfig entries.
# This is an explicit reset. For a reversible stop, use down.sh instead.
set -euo pipefail
umask 077

[[ $# == 0 ]] || { echo 'Usage: cluster-destroy.sh (permanently deletes all ShardShop cluster data)' >&2; exit 1; }
for tool in docker kind kubectl jq; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
# Always use the same provider as up.sh/down.sh, regardless of kind auto-detection.
export KIND_EXPERIMENTAL_PROVIDER=docker
cluster_name=shardshop
context="kind-$cluster_name"
lock="${TMPDIR:-/tmp}/shardshop-up.lock"
state="${TMPDIR:-/tmp}/shardshop-destroy.json"
mkdir "$lock" 2>/dev/null || {
    echo "Another ShardShop lifecycle invocation owns $lock. Inspect active processes and retained Jobs before removing a stale lock." >&2
    exit 1
}
work=
cleanup() {
    trap '' INT TERM
    [[ -z "$work" ]] || rm -rf -- "$work"
    rmdir "$lock"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
work=$(mktemp -d)
daemon_id=$(docker info --format '{{.ID}}')
[[ -n "$daemon_id" ]] || { echo 'Cannot establish the Docker daemon identity.' >&2; exit 1; }
printf '[]\n' >"$work/volumes.json"
if [[ -e "$state" ]]; then
    # A failed delete may already have removed the nodes that identified their
    # volumes. Retain this exact inventory across retries, bound to this daemon.
    jq -e --arg daemon "$daemon_id" '
        .daemonId == $daemon and (.volumes | type == "array") and
        all(.volumes[]; type == "string" and test("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$"))
    ' "$state" >/dev/null || {
        echo "Invalid destruction inventory or different Docker daemon: $state. Inspect it before retrying." >&2
        exit 1
    }
    jq '.volumes' "$state" >"$work/volumes.json"
fi

# Discover by kind's exact ownership label, including stopped or partially deleted
# clusters. Never infer ownership from a name prefix or prune Docker resources.
docker ps -a --no-trunc --filter "label=io.x-k8s.kind.cluster=$cluster_name" \
    --format '{{.ID}}' >"$work/nodes"
nodes=()
while IFS= read -r node; do [[ -z "$node" ]] || nodes+=("$node"); done <"$work/nodes"
printf '[]\n' >"$work/nodes.json"
if (( ${#nodes[@]} )); then
    docker inspect "${nodes[@]}" >"$work/nodes.json"
    # The supported kind lab uses Docker /var volumes plus the read-only kernel
    # module bind. Host data needs an explicit recovery/removal procedure.
    jq -e --arg cluster "$cluster_name" '
        all(.[]; .Config.Labels["io.x-k8s.kind.cluster"] == $cluster and
            all(.Mounts[]; .Type == "volume" or
                (.Type == "bind" and .Source == "/lib/modules" and
                 .Destination == "/lib/modules" and .RW == false)))
    ' "$work/nodes.json" >/dev/null || {
        echo 'Refusing to destroy a cluster with unexpected ownership or host mounts; inspect its storage first.' >&2
        exit 1
    }
    jq --slurpfile previous "$work/volumes.json" \
        '([.[].Mounts[] | select(.Type == "volume") | .Name] + $previous[0]) | unique' \
        "$work/nodes.json" >"$work/merged-volumes.json"
    mv "$work/merged-volumes.json" "$work/volumes.json"
fi
jq -r '.[]' "$work/volumes.json" >"$work/volumes"
while IFS= read -r volume; do
    docker ps -a --no-trunc --filter "volume=$volume" --format '{{.ID}}' >"$work/users"
    while IFS= read -r consumer; do
        [[ -z "$consumer" ]] || jq -e --arg id "$consumer" 'any(.[]; .Id == $id)' \
            "$work/nodes.json" >/dev/null || {
            echo "Refusing to delete shared volume $volume." >&2
            exit 1
        }
    done <"$work/users"
done <"$work/volumes"

# kind owns these three kubeconfig keys. Reject references from another context
# before kind removes them; respect KUBECONFIG rather than editing a fixed file.
kubectl config view -o json >"$work/kubeconfig.json"
jq -e --arg context "$context" '
    all(.contexts[]?; .name == $context or
        (.context.cluster != $context and .context.user != $context))
' "$work/kubeconfig.json" >/dev/null || {
    echo 'Another kubeconfig context references ShardShop credentials; resolve that reference before destruction.' >&2
    exit 1
}
echo 'Permanently deleting the shardshop cluster, including databases, volumes, Secrets and allocator history.'
jq --arg daemon "$daemon_id" '{daemonId: $daemon, volumes: .}' \
    "$work/volumes.json" >"$work/destroy.json"
mv "$work/destroy.json" "$state"
echo "Deletion inventory: $state (retained on failure; rerun with the same TMPDIR and Docker daemon)."
# kind deletes node containers with docker rm -f -v and removes its kubeconfig.
kind delete cluster --name "$cluster_name"
remaining=$(docker ps -a --filter "label=io.x-k8s.kind.cluster=$cluster_name" --format '{{.ID}}')
[[ -z "$remaining" ]] || { echo 'ShardShop node containers remain after deletion.' >&2; exit 1; }

# kind removes anonymous volumes. Also remove any exclusive named volumes from
# the captured nodes; never remove a volume that has acquired another consumer.
docker volume ls --format '{{.Name}}' >"$work/remaining-volumes"
jq -r '.[]' "$work/volumes.json" >"$work/volumes"
while IFS= read -r volume; do
    if grep -Fxq -- "$volume" "$work/remaining-volumes"; then
        docker volume rm "$volume" >/dev/null
    fi
done <"$work/volumes"
docker volume ls --format '{{.Name}}' >"$work/remaining-volumes"
while IFS= read -r volume; do
    if grep -Fxq -- "$volume" "$work/remaining-volumes"; then
        echo "ShardShop volume $volume remains after deletion." >&2
        exit 1
    fi
done <"$work/volumes"
kubectl config view -o json >"$work/kubeconfig.json"
jq -e --arg context "$context" '
    all(.contexts[]?, .clusters[]?, .users[]?; .name != $context) and
    .["current-context"] != $context
' "$work/kubeconfig.json" >/dev/null || {
    echo 'ShardShop kubeconfig entries remain; inspect KUBECONFIG and its file permissions.' >&2
    exit 1
}
rm -- "$state"
echo 'Destroyed the shardshop cluster, its attached volumes and kubeconfig entries.'
