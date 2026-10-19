#!/usr/bin/env bash
# Stop the local ShardShop kind cluster without deleting it: the node containers keep
# their volumes, databases and cluster state.
# When: whenever the lab is not needed, to free Docker memory and CPU.
# Next: scripts/up.sh starts the same cluster again.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: down.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/lib/lifecycle.sh"
lifecycle_init
require_tools docker kind
nodes=$(kind get nodes --name "$cluster_name")
[[ -n "$nodes" ]] || { echo 'No shardshop kind cluster to stop.'; exit 0; }
# kind creates its nodes with restart policy on-failure:1. A node that Docker kills at the
# stop timeout exits with a failure code, thus the next Docker daemon start restarts it, for
# example when Docker Desktop's Resource Saver starts its VM again. Turn the policy off first.
docker update --restart=no $nodes >/dev/null
# Docker stops the nodes in parallel and sends SIGKILL after ten seconds. It does not
# change a stopped node, and it returns an error if a node does not stop.
docker stop --timeout 10 $nodes >/dev/null
echo 'Stopped the shardshop kind cluster.'
echo 'Run scripts/up.sh to restart the shardshop kind cluster.'
