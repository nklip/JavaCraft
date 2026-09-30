#!/usr/bin/env bash
# Stop the local ShardShop kind cluster without deleting it: the node containers keep
# their volumes, databases and cluster state. Only kind delete cluster removes data.
# When: whenever the lab is not needed, to free Docker memory and CPU.
# Next: scripts/up.sh starts the same cluster again.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: down.sh' >&2; exit 1; }
for tool in docker kind; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done

nodes=$(kind get nodes --name shardshop)
[[ -n "$nodes" ]] || { echo 'No shardshop kind cluster to stop.'; exit 0; }
# All nodes stop together. PostgreSQL shuts down cleanly within seconds of the stop
# signal; Docker kills whatever is still running after its default 10 seconds.
echo "$nodes" | xargs docker stop >/dev/null
echo 'Stopped the shardshop kind cluster.'
echo 'Run scripts/up.sh to restart the shardshop kind cluster.'
