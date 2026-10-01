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
print(data["version"], data["regionVersion"], data["instances"], data["migrationImage"])
print(data["shards"].strip())
') || return 1
    read -r routing_version region_version instances migration_image <<< "${metadata%%$'\n'*}"
    shards=()
    while IFS= read -r shard; do shards+=("$shard"); done <<< "${metadata#*$'\n'}"
}

assert_published_topology() {
    local snapshot published published_name published_version published_regions
    for snapshot in shardshop-routing shardshop-migration-topology; do
        published=$(k get configmap "$snapshot" --ignore-not-found \
            -o jsonpath='{.metadata.name}{" "}{.data.version}{" "}{.data.regionVersion}') || return 1
        read -r published_name published_version published_regions <<< "$published"
        # Every saved snapshot must pin both the routing names and their regions.
        if [[ -n "$published_name" && ( "$published_version" != "$routing_version" ||
              "$published_regions" != "$region_version" ) ]]; then
            echo "Shard membership/order or regions differ from $snapshot. Stop applications and reset/reseed the lab before removing saved topology; online resharding is not implemented." >&2
            return 1
        fi
    done
}

reserve_migration_topology() {
    local reserved
    reserved=$(k get configmap shardshop-migration-topology --ignore-not-found -o name) || return 1
    if [[ -z "$reserved" ]]; then
        # Create once, before any SQL runs. Retain across failed migrations/publication.
        if ! k create configmap shardshop-migration-topology \
            --from-literal="version=$routing_version" --from-literal="regionVersion=$region_version" >/dev/null; then
            echo 'Topology reservation was not confirmed; checking the stored reservation before continuing.' >&2
        fi
    fi
    # A concurrent or uncertain create is safe only if the saved versions match exactly.
    reserved=$(k get configmap shardshop-migration-topology \
        -o jsonpath='{.data.version}{" "}{.data.regionVersion}') || return 1
    if [[ "$reserved" != "$routing_version $region_version" ]]; then
        echo 'Migration topology differs from the saved reservation; refusing to run SQL.' >&2
        return 1
    fi
}
