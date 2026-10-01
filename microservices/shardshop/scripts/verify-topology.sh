#!/usr/bin/env bash
# Check every inventory shard, then drill one (default first shard) in the disposable
# kind-shardshop lab only. Temporarily fences standbys and replaces one standby
# pod; never deletes PVCs. Run once per shard to exercise every primary.
# When: on demand, once up.sh has made the clusters Ready, e.g. after changing cluster
# settings. It briefly blocks writes on the drilled shard. --routing-only checks
# deployed routing constraints on every primary without performing the failure drill.
set -euo pipefail

source "$(dirname -- "${BASH_SOURCE[0]}")/shards.sh"
usage="Usage: $0 [shard from infra/shards.yaml] | --routing-only"
routing_only=false
if [[ ${1:-} == --routing-only ]]; then routing_only=true; shift; fi
(( $# <= 1 )) || { echo "$usage" >&2; exit 1; }
[[ "$routing_only" != true || $# == 0 ]] || { echo "$usage" >&2; exit 1; }
load_inventory
context=kind-shardshop
cluster=${1:-${shards[0]}}
found=false
for shard in "${shards[@]}"; do [[ "$shard" != "$cluster" ]] || found=true; done
[[ "$found" == true ]] || { echo "$usage" >&2; exit 1; }
[[ "$routing_only" == true || "$instances" == 3 ]] || { echo 'The quorum failure drill requires three instances per shard' >&2; exit 1; }
# This row is only the replication probe; verify_routing uses the shared golden vectors.
fixture=1
shard_selector=$(IFS=,; echo "${shards[*]}")
selector="cnpg.io/cluster=$cluster,cnpg.io/podRole=instance"
lock=shardshop-topology-drill
probe="topology_probe_$$"
primary=
writer=
fenced=false
created=false
locked=false
work=$(mktemp -d)

k() { kubectl --context "$context" -n shardshop --request-timeout=20s "$@"; }
fail() { echo "$*" >&2; exit 1; }
equal() { [[ "$1" == "$2" ]] || fail "$3: expected '$2', got '$1'"; }
sql() {
    local result
    if ! result=$(k exec "$1" -c postgres -- env \
        PGOPTIONS='-c statement_timeout=15000 -c lock_timeout=5000' \
        psql -X -qAt -v ON_ERROR_STOP=1 -v VERBOSITY="${3:-default}" \
        -U postgres -d shardshop -c "$2" 2>"$work/sql-stderr"); then
        cat "$work/sql-stderr" >&2
        return 1
    fi
    # PostgreSQL may cancel a synchronous wait with a warning and exit zero.
    if [[ -s "$work/sql-stderr" ]]; then
        cat "$work/sql-stderr" >&2
        return 1
    fi
    printf '%s\n' "$result"
}
await_sql() {
    local deadline=$((SECONDS + 180)) result
    while (( SECONDS < deadline )); do
        if result=$(sql "$1" "$2" 2>"$work/sql-error") && [[ "$result" == "$3" ]]; then
            return
        fi
        sleep 1
    done
    cat "$work/sql-error" >&2
    fail "Timed out on $1: $2 (expected $3, last result ${result:-unavailable})"
}
current_primary() { k get cluster "$cluster" -o jsonpath='{.status.currentPrimary}'; }
endpoints() {
    k get endpointslices -l "kubernetes.io/service-name=$1" \
        -o jsonpath='{range .items[*].endpoints[?(@.conditions.ready==true)]}{.targetRef.name}{"\n"}{end}' | sort
}
volumes() {
    k get pvc -l "cnpg.io/cluster=$cluster" \
        -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.uid}{" "}{.spec.volumeName}{" "}{.status.phase}{"\n"}{end}' | sort
}
verify_routing() {
    local vectors shard leader predicate entity schema
    vectors=$(python3 - "$root/shardshop-sharding/src/test/resources/routing-vectors.csv" "${shards[@]}" <<'PY'
import csv, hashlib, re, sys

# This frozen mapping belongs to the golden fixture contract, not the live inventory.
golden_shards = ("shard-a", "shard-b", "shard-c")
deployed_shards = sys.argv[2:]
values = []
with open(sys.argv[1]) as source:
    for name, identifier, expected in csv.reader(line for line in source if not line.startswith("#")):
        if not re.fullmatch(r"[1-9][0-9]{0,18}", identifier) or int(identifier) > 9223372036854775807:
            sys.exit(f"Invalid golden ID: {name}")
        digest = int.from_bytes(hashlib.sha256(identifier.encode("utf-8")).digest(), "big")
        if golden_shards[digest % len(golden_shards)] != expected:
            sys.exit(f"Golden routing vector disagrees with independent SHA-256: {name}")
        # Also support reordered and differently sized deployment inventories.
        shard = deployed_shards[digest % len(deployed_shards)]
        values.append(f"({identifier}::bigint, '{shard}')")
if not values:
    sys.exit("No golden routing vectors found")
print(",".join(values))
PY
    ) || return 1
    for shard in "${shards[@]}"; do
        leader=$(k get cluster "$shard" -o jsonpath='{.status.currentPrimary}')
        for entity in seller buyer; do
            schema=catalog
            [[ "$entity" != buyer ]] || schema=ordering
            predicate=$(sql "$leader" "SELECT pg_get_expr(conbin, conrelid) FROM pg_constraint WHERE conrelid = '$schema.${entity}s'::regclass AND conname = '${entity}s_shard_check' AND contype = 'c' AND convalidated")
            [[ -n "$predicate" ]] || fail "$shard lacks a validated ${entity}s_shard_check"
            equal "$(sql "$leader" "BEGIN READ ONLY; SELECT count(*) FROM (VALUES $vectors) AS vectors(${entity}_id, expected_shard) WHERE ($predicate) IS DISTINCT FROM (expected_shard = '$shard'); ROLLBACK;")" 0 "$shard deployed $entity routing vs golden vectors"
            echo "Verified $shard deployed $entity routing against the shared Java golden vectors."
        done
    done
}
verify_shared_topology() {
    local shard leader pod identity role shard_pods replicas expected all_volumes
    local claim uid volume phase
    local identities=() all_claims=() all_uids=() all_pvs=()
    local primary_count=0 standby_count=0
    peer_pods=()
    for shard in "${shards[@]}"; do
        k wait --for=condition=Ready "cluster/$shard" --timeout=180s
        k wait --for=condition=Ready pods -l "cnpg.io/cluster=$shard,cnpg.io/podRole=instance" --timeout=180s
        leader=$(k get cluster "$shard" -o jsonpath='{.status.currentPrimary}')
        identity=$(sql "$leader" 'SELECT system_identifier FROM pg_control_system()')
        [[ "$identity" =~ ^[0-9]+$ ]] || fail "$shard has no database system identifier"
        identities+=("$identity")
        shard_pods=($(k get pods -l "cnpg.io/cluster=$shard,cnpg.io/podRole=instance" -o jsonpath='{.items[*].metadata.name}'))
        equal "${#shard_pods[@]}" 3 "$shard instance count"
        replicas=()
        for pod in "${shard_pods[@]}"; do
            [[ "$pod" =~ ^${shard}-[0-9]+$ ]] || fail "Unexpected pod name: $pod"
            equal "$(sql "$pod" 'SELECT system_identifier FROM pg_control_system()')" "$identity" "$pod replication lineage"
            role=$(sql "$pod" "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')")
            if [[ "$pod" == "$leader" ]]; then
                equal "$role" 'f|off' "$pod writable primary"
                primary_count=$((primary_count + 1))
            else
                equal "$role" 't|on' "$pod read-only standby"
                replicas+=("$pod")
                standby_count=$((standby_count + 1))
            fi
            all_claims+=("$(k get pod "$pod" -o jsonpath='{.spec.volumes[?(@.name=="pgdata")].persistentVolumeClaim.claimName}')")
            if [[ "$shard" != "$cluster" ]]; then peer_pods+=("$pod"); fi
        done
        equal "${#replicas[@]}" 2 "$shard standby count"
        expected=$(printf '%s\n' "${replicas[@]}" | sort)
        await_sql "$leader" "SELECT application_name FROM pg_stat_replication WHERE state='streaming' AND sync_state='quorum' ORDER BY application_name" "$expected"
        equal "$(sql "$leader" 'SELECT count(*) FROM pg_stat_replication')" 2 "$shard replication connections"
        equal "$(endpoints "$shard-rw")" "$leader" "$shard read-write Service"
        equal "$(endpoints "$shard-ro")" "$expected" "$shard read-only Service"
    done
    equal "$primary_count" "${#shards[@]}" 'Shared writable primaries'
    equal "$standby_count" "$(( ${#shards[@]} * (instances - 1) ))" 'Shared standbys'
    equal "$(printf '%s\n' "${identities[@]}" | sort -u | wc -l | tr -d ' ')" "${#shards[@]}" 'Independent database lineages'
    all_volumes=$(k get pvc -l "cnpg.io/cluster in ($shard_selector)" \
        -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.uid}{" "}{.spec.volumeName}{" "}{.status.phase}{"\n"}{end}')
    equal "$(printf '%s\n' "$all_volumes" | wc -l | tr -d ' ')" "$(( ${#shards[@]} * instances ))" 'Shared PVC count'
    while read -r claim uid volume phase; do
        equal "$phase" Bound "$claim status"
        [[ -n "$uid" && -n "$volume" ]] || fail "$claim identity is incomplete"
        printf '%s\n' "${all_claims[@]}" | grep -Fxq "$claim" || fail "$claim is not attached to a shard instance"
        all_uids+=("$uid")
        all_pvs+=("$volume")
    done <<<"$all_volumes"
    equal "$(printf '%s\n' "${all_claims[@]}" | sort -u | wc -l | tr -d ' ')" "$(( ${#shards[@]} * instances ))" 'Distinct pod PVCs'
    equal "$(printf '%s\n' "${all_uids[@]}" | sort -u | wc -l | tr -d ' ')" "$(( ${#shards[@]} * instances ))" 'Distinct PVC identities'
    equal "$(printf '%s\n' "${all_pvs[@]}" | sort -u | wc -l | tr -d ' ')" "$(( ${#shards[@]} * instances ))" 'Distinct backing volumes'
    echo "Verified ${#shards[@]} writable primaries, $standby_count shard-local quorum standbys, role Services and $(( ${#shards[@]} * instances )) independent volumes."
}
verify_isolation() {
    local pod
    # A one-shard lab has no peers (empty arrays also trip nounset on Bash 3.2).
    if (( ${#shards[@]} == 1 )); then return; fi
    for pod in "${peer_pods[@]}"; do
        equal "$(sql "$pod" "SELECT to_regnamespace('$probe') IS NULL")" t "$pod must not contain $cluster probe data"
    done
}
fence() {
    equal "$(current_primary)" "$primary" 'Primary changed during drill'
    fenced=true
    k annotate cluster "$cluster" "cnpg.io/fencedInstances=$1" --overwrite
}
unfence() {
    k annotate cluster "$cluster" cnpg.io/fencedInstances- || return 1
    fenced=false
}
cleanup() {
    local status=$?
    trap - EXIT
    # Recovery comes first, even if an assertion or signal interrupted the drill.
    if [[ "$fenced" == true ]]; then
        if ! unfence; then
            echo "Recovery required: remove cnpg.io/fencedInstances from $cluster" >&2
            status=1
        fi
    fi
    if [[ -n "$writer" ]]; then
        if ! wait "$writer"; then status=1; fi
    fi
    if [[ "$created" == true ]]; then
        if ! k wait --for=condition=Ready pods -l "$selector" --timeout=180s; then status=1; fi
        if ! k wait --for=condition=Ready "cluster/$cluster" --timeout=180s; then status=1; fi
        primary=$(current_primary) || status=1
        if ! sql "$primary" "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = '$probe'; DROP PUBLICATION $probe; DROP TABLE $probe.rows; DROP SCHEMA $probe;"; then
            echo "Probe cleanup failed: inspect schema/slot $probe" >&2
            status=1
        fi
    fi
    if [[ "$locked" == true && "$fenced" == false ]]; then
        if ! k delete configmap "$lock" --wait=true --timeout=20s; then status=1; fi
    fi
    rm -rf -- "$work"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

assert_published_topology
if [[ "$routing_only" == true ]]; then
    verify_routing
    exit 0
fi
k wait --for=condition=Ready "cluster/$cluster" --timeout=180s
existing_fence=$(k get cluster "$cluster" -o jsonpath='{.metadata.annotations.cnpg\.io/fencedInstances}')
[[ -z "$existing_fence" || "$existing_fence" == '[]' ]] || fail 'Cluster is already fenced; recover it before this drill'
k create configmap "$lock" --from-literal=probe="$probe" --from-literal=cluster="$cluster"
locked=true
verify_shared_topology
verify_routing
primary=$(current_primary)
budget=$(k get cluster "$cluster" -o jsonpath='{.spec.postgresql.parameters.max_slot_wal_keep_size}')
[[ -n "$budget" && "$budget" != -1 ]] || fail "$cluster must set a finite max_slot_wal_keep_size"
pods=($(k get pods -l "$selector" -o jsonpath='{.items[*].metadata.name}'))
equal "${#pods[@]}" 3 'Instance count'
standbys=()
nodes=()
claims=()
for pod in "${pods[@]}"; do
    [[ "$pod" =~ ^${cluster}-[0-9]+$ ]] || fail "Unexpected pod name: $pod"
    nodes+=("$(k get pod "$pod" -o jsonpath='{.spec.nodeName}')")
    claims+=("$(k get pod "$pod" -o jsonpath='{.spec.volumes[?(@.name=="pgdata")].persistentVolumeClaim.claimName}')")
    if [[ "$pod" == "$primary" ]]; then
        equal "$(sql "$pod" 'SELECT pg_is_in_recovery()')" f 'Primary role'
    else
        standbys+=("$pod")
        equal "$(sql "$pod" 'SELECT pg_is_in_recovery()')" t 'Standby role'
    fi
    equal "$(sql "$pod" 'SHOW wal_level')" logical 'Logical WAL'
    equal "$(sql "$pod" 'SHOW hot_standby_feedback')" on 'Standby feedback'
    equal "$(sql "$pod" 'SHOW sync_replication_slots')" on 'Slot synchronization'
    equal "$(sql "$pod" 'SHOW max_slot_wal_keep_size')" "$budget" 'Slot WAL budget'
done
equal "${#standbys[@]}" 2 'Standby count'
equal "$(printf '%s\n' "${nodes[@]}" | sort -u | wc -l | tr -d ' ')" 3 'Distinct nodes'
workers=$(k get nodes -l '!node-role.kubernetes.io/control-plane' -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
for node in "${nodes[@]}"; do
    grep -Fxq "$node" <<<"$workers" || fail "$node is not a worker"
done
equal "$(endpoints "$cluster-rw")" "$primary" 'Read-write Service'
equal "$(endpoints "$cluster-ro")" "$(printf '%s\n' "${standbys[@]}" | sort)" 'Read-only Service'
before_volumes=$(volumes)
equal "$(printf '%s\n' "$before_volumes" | wc -l | tr -d ' ')" 3 'PVC count'
equal "$(printf '%s\n' "${claims[@]}" | sort -u | wc -l | tr -d ' ')" 3 'Distinct pod PVCs'
while read -r claim uid volume phase; do
    equal "$phase" Bound 'PVC status'
    [[ -n "$uid" && -n "$volume" ]] || fail 'PVC identity is incomplete'
    printf '%s\n' "${claims[@]}" | grep -Fxq "$claim" || fail "PVC $claim is not attached to an instance"
done <<<"$before_volumes"
await_sql "$primary" "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state='quorum'" 2
equal "$(sql "$primary" 'SHOW synchronous_commit')" on 'Durable commit'
sync_names=$(sql "$primary" 'SHOW synchronous_standby_names')
[[ "$sync_names" == 'ANY 1 '* ]] || fail "Unexpected synchronous standbys: $sync_names"
(( $(sql "$primary" 'SHOW max_replication_slots') >= 3 )) || fail 'Not enough replication slots'
(( $(sql "$primary" 'SHOW max_wal_senders') >= 4 )) || fail 'Not enough WAL senders for standbys, CDC and bootstrap'
await_sql "$primary" "SELECT count(*) FROM pg_replication_slots WHERE slot_type='physical' AND active AND slot_name = ANY(string_to_array(current_setting('synchronized_standby_slots'), ','))" 2
echo "Verified primary/standby roles, Service endpoints, three worker PVCs and quorum settings."

sql "$primary" "CREATE SCHEMA $probe; CREATE TABLE $probe.rows (id bigint PRIMARY KEY, phase text NOT NULL); CREATE PUBLICATION $probe FOR TABLE $probe.rows;"
created=true
equal "$(sql "$primary" "INSERT INTO $probe.rows VALUES ($fixture, 'initial') RETURNING id::text")" "$fixture" 'Fixture insert'
for standby in "${standbys[@]}"; do
    await_sql "$standby" "SELECT phase FROM $probe.rows WHERE id=$fixture" initial
    if sql "$standby" "UPDATE $probe.rows SET phase='forbidden' WHERE id=$fixture" sqlstate >"$work/read-only-result" 2>"$work/read-only-error"; then
        fail "$standby accepted a write"
    fi
    grep -Eq '(^|[[:space:]])25006($|[[:space:]])' "$work/read-only-error" \
        || { cat "$work/read-only-error" >&2; fail "$standby did not reject the write as read-only"; }
    equal "$(sql "$standby" "SELECT phase FROM $probe.rows WHERE id=$fixture")" initial 'Rejected write preserved the row'
done
verify_isolation
echo "Verified fixture $fixture stays inside $cluster and its standbys reject writes."
equal "$(sql "$primary" "SELECT slot_name FROM pg_create_logical_replication_slot('$probe', 'pgoutput', false, false, true)")" "$probe" 'Failover slot creation'
# An idle slot can retain an older catalog horizon than the standbys. Act as a
# bounded consumer until both standbys can persist the synchronized slot.
deadline=$((SECONDS + 180))
while :; do
    equal "$(sql "$primary" "UPDATE $probe.rows SET phase='initial' WHERE id=$fixture RETURNING id::text")" "$fixture" 'CDC probe write'
    equal "$(sql "$primary" 'SELECT pg_log_standby_snapshot() IS NOT NULL')" t 'Standby snapshot'
    equal "$(sql "$primary" "SELECT count(*) > 0 FROM pg_logical_slot_get_binary_changes('$probe', NULL, NULL, 'proto_version', '1', 'publication_names', '$probe')")" t 'CDC probe consumed'
    synchronized=true
    for standby in "${standbys[@]}"; do
        ready=$(sql "$standby" "SELECT count(*) FROM pg_replication_slots WHERE slot_name='$probe' AND synced AND failover AND NOT temporary AND invalidation_reason IS NULL")
        [[ "$ready" == 1 ]] || synchronized=false
    done
    [[ "$synchronized" == true ]] && break
    (( SECONDS < deadline )) || fail 'Failover slot did not become persistent on both standbys'
    sleep 1
done
sql "$primary" "SELECT pg_drop_replication_slot('$probe')"
for standby in "${standbys[@]}"; do
    await_sql "$standby" "SELECT count(*) FROM pg_replication_slots WHERE slot_name='$probe'" 0
done
echo 'Verified committed writes and a persistent failover slot reach both standbys.'

# Replace only a standby, retaining its PVC and requiring a new pod identity.
equal "$(current_primary)" "$primary" 'Primary before pod replacement'
old_uid=$(k get pod "${standbys[0]}" -o jsonpath='{.metadata.uid}')
k delete pod "${standbys[0]}" --wait=true --timeout=60s
deadline=$((SECONDS + 180))
while :; do
    new_uid=$(k get pod "${standbys[0]}" --ignore-not-found -o jsonpath='{.metadata.uid}')
    [[ -n "$new_uid" && "$new_uid" != "$old_uid" ]] && break
    (( SECONDS < deadline )) || fail 'Standby pod was not recreated'
    sleep 1
done
k wait --for=condition=Ready "pod/${standbys[0]}" --timeout=180s
equal "$(volumes)" "$before_volumes" 'PVC identity after standby replacement'
await_sql "${standbys[0]}" "SELECT phase FROM $probe.rows WHERE id=$fixture" initial
await_sql "$primary" "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" 2
echo 'Verified standby replacement preserves its PVC and replicated data.'

fence "[\"${standbys[0]}\"]"
k wait --for=condition=Ready=false "pod/${standbys[0]}" --timeout=120s
await_sql "$primary" "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'" 1
equal "$(sql "$primary" "UPDATE $probe.rows SET phase='one-standby' WHERE id=$fixture RETURNING phase")" one-standby 'Commit with one standby absent'
await_sql "${standbys[1]}" "SELECT phase FROM $probe.rows WHERE id=$fixture" one-standby
echo 'Verified writes continue with one standby absent.'

fence "[\"${standbys[0]}\",\"${standbys[1]}\"]"
k wait --for=condition=Ready=false "pod/${standbys[1]}" --timeout=120s
await_sql "$primary" 'SELECT count(*) FROM pg_stat_replication' 0
k --request-timeout=150s exec "$primary" -c postgres -- env \
    PGAPPNAME="$probe" PGOPTIONS='-c statement_timeout=120000 -c lock_timeout=5000' \
    psql -X -qAt -v ON_ERROR_STOP=1 -U postgres -d shardshop \
    -c "BEGIN; UPDATE $probe.rows SET phase='recovered' WHERE id=$fixture; COMMIT; SELECT 'committed';" \
    >"$work/writer" 2>"$work/writer-error" &
writer=$!
await_sql "$primary" "SELECT count(*) FROM pg_stat_activity WHERE application_name='$probe' AND wait_event='SyncRep'" 1
kill -0 "$writer" || fail 'Writer exited instead of waiting for a standby'
echo 'Verified the commit waits in SyncRep with both standbys absent.'

# Restore one standby first: the pending commit must finish without the other.
fence "[\"${standbys[0]}\"]"
k wait --for=condition=Ready "pod/${standbys[1]}" --timeout=120s
if ! wait "$writer"; then
    writer=
    cat "$work/writer-error" >&2
    fail 'Blocked commit failed after standby recovery'
fi
writer=
[[ ! -s "$work/writer-error" ]] || { cat "$work/writer-error" >&2; fail 'Blocked commit emitted a warning'; }
equal "$(cat "$work/writer")" committed 'Commit resumed after one standby recovered'
unfence
k wait --for=condition=Ready pods -l "$selector" --timeout=180s
k wait --for=condition=Ready "cluster/$cluster" --timeout=180s
for standby in "${standbys[@]}"; do
    await_sql "$standby" "SELECT phase FROM $probe.rows WHERE id=$fixture" recovered
done
await_sql "$primary" "SELECT count(*) FROM pg_stat_replication WHERE state='streaming' AND sync_state='quorum'" 2
equal "$(endpoints "$cluster-ro")" "$(printf '%s\n' "${standbys[@]}" | sort)" 'Read-only Service after recovery'
equal "$(volumes)" "$before_volumes" 'PVC identity after quorum drill'
equal "$(current_primary)" "$primary" 'Primary after quorum drill'
verify_isolation
verify_shared_topology
echo 'Topology verification passed; both standbys restored. Removing only drill-owned SQL objects.'
