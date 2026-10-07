#!/usr/bin/env bash
# The allocation high-water is retained independently of database backups.
# initialize is an explicit one-time operation for a fresh lab; check never writes.
# Never repair partial state or restore/reset the counter through this script.
set -euo pipefail

[[ $# == 1 && ( "$1" == initialize || "$1" == check ) ]] \
    || { echo 'Usage: generator-registry.sh initialize|check' >&2; exit 1; }
for tool in kubectl python3; do
    command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
done
k() { kubectl --context kind-shardshop --request-timeout=10s -n shardshop "$@"; }
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

read_state() {
    k get configmap shardshop-snowflake-generators shardshop-generator-identity \
        --ignore-not-found -o json >"$work/raw-state.json"
    # kubectl emits no JSON when every requested name is absent and may return
    # one ConfigMap rather than a List when only one object exists.
    python3 - "$work/raw-state.json" >"$work/state.json" <<'PY'
import json, pathlib, sys
raw = pathlib.Path(sys.argv[1]).read_text().strip()
payload = json.loads(raw) if raw else {}
json.dump({"items": payload.get("items", [payload] if payload else [])}, sys.stdout)
PY
}

# Callers supply --dry-run=server, or dryRun=All in the raw collection URL.
# A dry-run must be rejected by our policy, not merely by RBAC or a network error.
# Checking enforcement also avoids relying on admission propagation timing.
assert_denied() {
    if k "$@" >"$work/guard-check" 2>&1; then
        echo 'Generator retention policy is not enforcing; run scripts/up.sh before continuing.' >&2
        return 1
    fi
    if ! python3 - "$work/guard-check" <<'PY'
import pathlib, sys
error = pathlib.Path(sys.argv[1]).read_text()
sys.exit(0 if "shardshop-generator-retention" in error and "denied request" in error else 1)
PY
    then
        cat "$work/guard-check" >&2
        echo 'Could not verify the generator retention policy; refusing to continue.' >&2
        return 1
    fi
}

read_state
count=$(python3 -c 'import json, sys; print(len(json.load(sys.stdin)["items"]))' <"$work/state.json")
if [[ "$count" == 0 && "$1" == initialize ]]; then
    # Both objects must be absent. Creation uses create, never apply/replace/patch.
    assert_denied create configmap shardshop-snowflake-generators --from-literal=highWaterMark=-1 --dry-run=server
    k create configmap shardshop-snowflake-generators --from-literal=highWaterMark=0 -o json >"$work/created.json"
    python3 - "$work/created.json" >"$work/identity.json" <<'PY'
import json, sys
registry = json.load(open(sys.argv[1]))
json.dump({"apiVersion": "v1", "kind": "ConfigMap",
           "metadata": {"name": "shardshop-generator-identity", "namespace": "shardshop"},
           "immutable": True, "data": {"registryUid": registry["metadata"]["uid"]}}, sys.stdout)
PY
    k create -f "$work/identity.json" >/dev/null
    read_state
elif [[ "$count" != 2 ]]; then
    echo 'Generator registry is missing or partial; startup must remain stopped.' >&2
    echo 'Only for a fresh lab with no retained IDs, run scripts/generator-registry.sh initialize.' >&2
    echo 'Recover partial or lost state from the latest independent allocator backup; never reset it.' >&2
    exit 1
fi

metadata=$(python3 - "$work/state.json" <<'PY'
import json, re, sys
items = {item["metadata"]["name"]: item for item in json.load(open(sys.argv[1]))["items"]}
registry = items.get("shardshop-snowflake-generators", {})
identity = items.get("shardshop-generator-identity", {})
uid = registry.get("metadata", {}).get("uid", "")
data = registry.get("data", {})
high_water = data.get("highWaterMark", "")
valid = (re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", uid)
         and re.fullmatch(r"0|[1-9][0-9]{0,3}", high_water) and int(high_water) <= 1023
         and len(data) == 1 and not registry.get("binaryData") and not registry.get("immutable")
         and identity.get("immutable") is True and not identity.get("binaryData")
         and identity.get("data") == {"registryUid": uid}
         and not registry["metadata"].get("deletionTimestamp")
         and not identity.get("metadata", {}).get("deletionTimestamp"))
if not valid:
    sys.exit("Generator registry is malformed or its identity changed; refusing startup.")
print(uid, high_water)
PY
)
read -r uid high_water <<< "$metadata"

assert_denied patch configmap shardshop-snowflake-generators --type=merge \
    -p "{\"data\":{\"highWaterMark\":\"$high_water\"}}" --dry-run=server
assert_denied delete configmap shardshop-snowflake-generators --dry-run=server
assert_denied delete configmap shardshop-generator-identity --dry-run=server
for name in shardshop-snowflake-generators shardshop-generator-identity; do
    # Keep dryRun=All: kubectl's ordinary --all issues individual deletes and
    # does not exercise the API server's collection-delete admission path.
    assert_denied delete --raw "/api/v1/namespaces/shardshop/configmaps?dryRun=All&fieldSelector=metadata.name%3D$name"
done
assert_denied patch configmap shardshop-generator-identity --type=merge \
    -p '{"metadata":{"annotations":{"shardshop.javacraft/guard-check":"true"}}}' --dry-run=server
echo "Generator registry verified: UID $uid, high-water $high_water/1023."
