#!/usr/bin/env bash
# Permanently delete the ShardShop kind lab, its storage and kubeconfig entries.
# This is an explicit reset. For a reversible stop, use down.sh instead.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: cluster-destroy.sh (permanently deletes all ShardShop cluster data)' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/lib/lifecycle.sh"
lifecycle_init
require_tools docker kind
echo "Permanently deleting the $cluster_name cluster, including databases, volumes, Secrets and allocator history."
# kind removes the node containers with docker rm -f -v, which also removes their anonymous
# /var volumes. Then kind removes the selected context entries from the kubeconfig. kind returns
# an error if one of these steps fails, and it ends with success if the cluster does not exist.
kind delete cluster --name "$cluster_name"
echo "Destroyed the $cluster_name cluster, its attached volumes and kubeconfig entries."
