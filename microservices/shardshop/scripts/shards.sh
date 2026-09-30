#!/usr/bin/env bash
# Shared script functions; the callers set strict mode and own temporary directories.
# When: sourced at the start of up.sh, migrate.sh, verify-topology.sh and
# build-migration-image.sh; never run directly.
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

render() { bash "$root/scripts/render.sh" "$@"; }
k() { kubectl --context kind-shardshop --request-timeout=30s -n shardshop "$@"; }

load_inventory() {
    local metadata shard
    metadata=$(render inventory | python3 -c '
import json, sys
data = json.load(sys.stdin)["data"]
print(data["version"], data["instances"], data["migrationImage"])
print(data["shards"].strip())
') || return 1
    read -r routing_version instances migration_image <<< "${metadata%%$'\n'*}"
    shards=()
    while IFS= read -r shard; do shards+=("$shard"); done <<< "${metadata#*$'\n'}"
}

assert_published_topology() {
    local published
    published=$(k get configmap shardshop-routing --ignore-not-found \
        -o jsonpath='{.metadata.name}{" "}{.data.version}') || return 1
    if [[ -n "$published" && "$published" != "shardshop-routing $routing_version" ]]; then
        echo 'Shard membership/order differs from the published topology. Stop applications and reset/reseed the lab before removing shardshop-routing; online resharding is not implemented.' >&2
        return 1
    fi
}
