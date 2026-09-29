#!/usr/bin/env bash
# Reversible drills for one shard cluster (default shard-a) in the disposable
# kind-shardshop lab only. Temporarily fences standbys and replaces one standby
# pod; never deletes PVCs.
set -euo pipefail

usage="Usage: $0 [shard-a|shard-b|shard-c]"
(( $# <= 1 )) || { echo "$usage" >&2; exit 1; }
context=kind-shardshop
cluster=${1:-shard-a}
# Independent routing fixtures from shardshop-sharding's golden vectors.
case "$cluster" in
    shard-a) fixture=880803840000004432 ;;
    shard-b) fixture=880803840000004097 ;;
    shard-c) fixture=880803840000004096 ;;
    *) echo "$usage" >&2; exit 1 ;;
esac
selector="cnpg.io/cluster=$cluster,cnpg.io/podRole=instance"
lock="$cluster-topology-drill"
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
        psql -X -qAt -v ON_ERROR_STOP=1 -U postgres -d shardshop -c "$2" 2>"$work/sql-stderr"); then
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

k wait --for=condition=Ready "cluster/$cluster" --timeout=180s
existing_fence=$(k get cluster "$cluster" -o jsonpath='{.metadata.annotations.cnpg\.io/fencedInstances}')
[[ -z "$existing_fence" || "$existing_fence" == '[]' ]] || fail 'Cluster is already fenced; recover it before this drill'
k create configmap "$lock" --from-literal=probe="$probe"
locked=true
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
done
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
echo 'Topology verification passed; both standbys restored. Removing only drill-owned SQL objects.'
