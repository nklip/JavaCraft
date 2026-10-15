#!/usr/bin/env bash
# Stop the local ShardShop kind cluster without deleting it: the node containers keep
# their volumes, databases and cluster state.
# When: whenever the lab is not needed, to free Docker memory and CPU.
# Next: scripts/up.sh starts the same cluster again.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: down.sh' >&2; exit 1; }
export KIND_EXPERIMENTAL_PROVIDER=docker
timeout=${SHARDSHOP_SHUTDOWN_TIMEOUT_SECONDS:-60}
case "$timeout" in
    ''|*[!0-9]*) echo 'SHARDSHOP_SHUTDOWN_TIMEOUT_SECONDS must be an integer from 1 to 3600.' >&2; exit 1;;
esac
[[ ${#timeout} -le 4 ]] && (( 10#$timeout >= 1 && 10#$timeout <= 3600 )) \
    || { echo 'SHARDSHOP_SHUTDOWN_TIMEOUT_SECONDS must be an integer from 1 to 3600.' >&2; exit 1; }
timeout=$((10#$timeout))

lock="${TMPDIR:-/tmp}/shardshop-up.lock"
mkdir "$lock" 2>/dev/null || {
    echo "Another ShardShop lifecycle call owns $lock. If its process has ended, inspect the retained Jobs before removing that lock." >&2
    exit 1
}
work=
active_pid=
stop_active() {
    local attempt
    if [[ -n "$active_pid" ]]; then
        kill -TERM "$active_pid" 2>/dev/null || true
        for attempt in {1..20}; do
            kill -0 "$active_pid" 2>/dev/null || break
            sleep 0.1
        done
        kill -KILL "$active_pid" 2>/dev/null || true
        wait "$active_pid" 2>/dev/null || true
        active_pid=
    fi
}
cleanup() {
    trap '' INT TERM
    # Reap the owned CLI before allowing another lifecycle caller to mutate nodes.
    stop_active
    [[ -z "$work" ]] || rm -rf -- "$work"
    rmdir "$lock"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in docker kind; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
work=$(mktemp -d)
deadline=$((SECONDS + timeout))

# Native Docker's stop grace period does not bound daemon discovery or a hung CLI.
# Apply one deadline to discovery, stopping and verification, without timeout(1).
run_bounded() {
    local output=$1 status=0
    shift
    if (( SECONDS >= deadline )); then
        echo "ShardShop shutdown exceeded its ${timeout}s timeout." >&2
        return 124
    fi
    "$@" >"$output" &
    active_pid=$!
    while kill -0 "$active_pid" 2>/dev/null; do
        if (( SECONDS >= deadline )); then
            echo "ShardShop shutdown exceeded its ${timeout}s timeout during $1." >&2
            stop_active
            return 124
        fi
        sleep 0.1
    done
    wait "$active_pid" || status=$?
    active_pid=
    if (( status == 0 && SECONDS >= deadline )); then
        echo "ShardShop shutdown exceeded its ${timeout}s timeout during $1." >&2
        return 124
    fi
    if (( status != 0 )); then
        echo "ShardShop shutdown failed during $1 (exit $status); retained nodes and data were not deleted." >&2
    fi
    return "$status"
}

run_bounded "$work/nodes" kind get nodes --name shardshop
nodes=()
running_nodes=()
while IFS= read -r node; do
    [[ -n "$node" ]] || continue
    nodes+=("$node")
    run_bounded "$work/running" docker inspect --format '{{.State.Running}}' "$node"
    running=$(cat "$work/running")
    case "$running" in
        true) running_nodes+=("$node");;
        false) ;;
        *) echo "Cannot establish whether $node is running; refusing to stop unverified nodes." >&2; exit 1;;
    esac
done <"$work/nodes"
(( ${#nodes[@]} > 0 )) || { echo 'No shardshop kind cluster to stop.'; exit 0; }
if (( ${#running_nodes[@]} > 0 )); then
    # Stop all running workers and the control plane together, preserving containers.
    run_bounded /dev/null docker stop --time 10 "${running_nodes[@]}"
fi
for node in "${nodes[@]}"; do
    run_bounded "$work/running" docker inspect --format '{{.State.Running}}' "$node"
    [[ $(cat "$work/running") == false ]] \
        || { echo "$node is still running after shutdown; retry down.sh after inspecting Docker." >&2; exit 1; }
done
echo 'Stopped the shardshop kind cluster.'
echo 'Run scripts/up.sh to restart the shardshop kind cluster.'
