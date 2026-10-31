# Shared implementation for the lifecycle and webpage scripts. Source it from Bash.
# down.sh and cluster-destroy.sh use only lifecycle_init and require_tools.
# Provisioning and application delivery remain in their respective entry points.

# Test orchestration owns the chosen cluster's lifetime. Normal calls keep the
# fixed lab and the caller's ordinary kubeconfig behavior.
lifecycle_target() {
    cluster_name=shardshop
    if [[ -n "${SHARDSHOP_TEST_CLUSTER+x}${SHARDSHOP_TEST_KUBECONFIG+x}" ]]; then
        cluster_name=${SHARDSHOP_TEST_CLUSTER:-}
        [[ "$cluster_name" =~ ^shardshop-test-[a-z0-9]([a-z0-9-]*[a-z0-9])?$ && ${#cluster_name} -le 63 ]] || {
            echo 'SHARDSHOP_TEST_CLUSTER must be a shardshop-test- name with a lowercase DNS-label suffix (maximum 63 characters).' >&2
            exit 1
        }
        local config=${SHARDSHOP_TEST_KUBECONFIG:-} directory default_directory
        [[ "$config" == /* && "$config" != *:* && "$config" != *$'\n'* && "$config" != *$'\r'* && ! -L "$config" ]] || {
            echo 'SHARDSHOP_TEST_KUBECONFIG must be one absolute private kubeconfig path, without symlinks or path lists.' >&2
            exit 1
        }
        directory=$(cd -P -- "$(dirname -- "$config")" && pwd) || exit 1
        default_directory="$(cd -- "$HOME" && pwd -P)/.kube"
        if [[ -d "$default_directory" ]]; then
            default_directory=$(cd -P -- "$default_directory" && pwd) || exit 1
        fi
        [[ "$directory" != "$default_directory" && "$directory" != "$default_directory/"* && -z "$(find "$directory" -prune ! -perm 700 -print)" ]] || {
            echo 'The test kubeconfig requires a private mode-700 directory outside ~/.kube.' >&2
            exit 1
        }
        config="$directory/$(basename -- "$config")"
        [[ ! -e "$config" || ( -f "$config" && -z "$(find "$config" -prune ! -perm 600 -print)" ) ]] || {
            echo 'An existing test kubeconfig must be a regular mode-600 file.' >&2
            exit 1
        }
        if [[ ! -e "$config" ]]; then
            (umask 077; set -o noclobber; : >"$config") || {
                echo 'Cannot exclusively create the private test kubeconfig.' >&2
                exit 1
            }
        fi
        export SHARDSHOP_TEST_KUBECONFIG="$config" KUBECONFIG="$config"
    fi
    context="kind-$cluster_name"
    export SHARDSHOP_CLUSTER_NAME="$cluster_name" SHARDSHOP_CONTEXT="$context"
}

lifecycle_init() {
    lifecycle_target
    root="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
    export KIND_EXPERIMENTAL_PROVIDER=docker
    created_cluster=false
    work=
    lock="${TMPDIR:-/tmp}/shardshop-up.lock"
    mkdir "$lock" 2>/dev/null || {
        echo "Another ShardShop lifecycle call owns $lock. If its process has ended, inspect the retained Jobs before removing that lock." >&2
        exit 1
    }
    trap lifecycle_cleanup EXIT
    # Bash defers signals until its foreground CLI exits. Keep the lock until
    # that mutation finishes; neither a signal nor cleanup may release it early.
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

lifecycle_cleanup() {
    trap '' INT TERM
    if [[ -n "$work" && -f "$work/dns-probes.yaml" ]]; then
        k delete -f "$work/dns-probes.yaml" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    fi
    [[ -z "$work" ]] || rm -rf -- "$work"
    rmdir "$lock"
}

require_tools() {
    local tool
    for tool in "$@"; do
        command -v "$tool" >/dev/null || { echo "$tool is not on PATH" >&2; exit 1; }
    done
}

# webpages.sh owns these local page ports. Index i of each list belongs to one
# page: the provider pages, then the workload control pages of step 4.13.
page_ports=(18080 18081 18082 18083 18084)
page_services=(product order order-producer product-reader product-seeder-control)
page_paths=(products orders workload-order-producer workload-product-reader workload-product-seeder)
page_titles=('Product page' 'Order page' 'Workload Order Producer page' 'Workload Product Reader page' 'Workload Product Seeder page')

# Inspect all ports before returning any PIDs, so an inspection error cannot
# turn into a partial cleanup. Only listening processes are selected.
page_listener_pids() {
    local port output status pid pids=
    for port in "${page_ports[@]}"; do
        if output=$(lsof -t -nP -iTCP:"$port" -sTCP:LISTEN 2>&1); then
            for pid in $output; do
                [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]] || {
                    echo "Unable to inspect local port $port: unexpected lsof output." >&2
                    return 1
                }
            done
            pids="$pids$output"$'\n'
        else
            status=$?
            # lsof returns 1 with no output when no listener matches.
            if [[ "$status" != 1 || -n "$output" ]]; then
                echo "Unable to inspect local port $port (lsof exit $status): $output" >&2
                return 1
            fi
        fi
    done
    [[ -z "$pids" ]] || printf '%s' "$pids" | sort -u
}

# Clear old forwards or other listeners before opening new tunnels. Only the
# discovered listener PIDs are signalled.
page_access_clear_ports() {
    local pids pid deadline
    pids=$(page_listener_pids) || return 1
    [[ -n "$pids" ]] || return 0
    echo 'Clearing local page ports 18080-18084.'
    for pid in $pids; do
        echo "Stopping listener PID $pid (TERM)."
        kill -TERM "$pid" 2>/dev/null || true
    done
    deadline=$((SECONDS + 5))
    while :; do
        pids=$(page_listener_pids) || return 1
        [[ -n "$pids" ]] || return 0
        (( SECONDS < deadline )) || break
        sleep 0.1
    done
    # Refresh from the actual listeners instead of retaining stale PIDs.
    for pid in $pids; do
        echo "Stopping remaining listener PID $pid (KILL)."
        kill -KILL "$pid" 2>/dev/null || true
    done
    deadline=$((SECONDS + 2))
    while :; do
        pids=$(page_listener_pids) || return 1
        [[ -n "$pids" ]] || return 0
        if (( SECONDS >= deadline )); then
            echo "Unable to release local ports 18080-18084; listener PIDs: $pids. Startup stopped." >&2
            return 1
        fi
        sleep 0.1
    done
}

# Session cleanup owns only this invocation's forwards; it never stops the lab.
page_access_cleanup() {
    trap '' INT TERM
    local pid
    for pid in "${forward_pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    for pid in "${forward_pids[@]}"; do wait "$pid" 2>/dev/null || true; done
    rm -rf -- "$forward_state"
}

# Returns the exit status of a stopped forward, or 0 while all forwards run.
page_forward_stopped() {
    local index status
    for index in "${!page_services[@]}"; do
        if ! kill -0 "${forward_pids[$index]}" 2>/dev/null; then
            status=0
            wait "${forward_pids[$index]}" || status=$?
            echo "ShardShop ${page_services[$index]} forwarding stopped (exit $status); stopping all local tunnels. The lab remains running." >&2
            # Access requires every live tunnel, even after a clean CLI exit.
            [[ "$status" != 0 ]] || status=1
            return "$status"
        fi
    done
    return 0
}

page_access() {
    local service index status deadline ready pids
    pids=$(page_listener_pids) || return 1
    if [[ -n "$pids" ]]; then
        echo "Local page ports became occupied (PIDs: $pids). Run scripts/webpages.sh again to reopen all pages. The lab remains running." >&2
        return 1
    fi
    forward_pids=()
    forward_state=$(mktemp -d)
    trap page_access_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    for index in "${!page_services[@]}"; do
        service=${page_services[$index]}
        kubectl --context "$context" -n shardshop port-forward --address 127.0.0.1 \
            "service/shardshop-$service" "${page_ports[$index]}:8080" > >(
                # Stream diagnostics rather than retaining a session-sized log.
                # The reader exits when kubectl closes its output pipe.
                while IFS= read -r line; do
                    printf '[%s] %s\n' "$service" "$line"
                    [[ "$line" != 'Forwarding from '* ]] || : >"$forward_state/$service-ready"
                done
            ) 2>&1 &
        forward_pids+=("$!")
    done
    deadline=$((SECONDS + 15))
    while :; do
        page_forward_stopped || return
        ready=true
        for service in "${page_services[@]}"; do
            [[ -f "$forward_state/$service-ready" ]] || ready=false
        done
        [[ "$ready" == true ]] && break
        if (( SECONDS >= deadline )); then
            echo 'ShardShop forwarding did not become ready within 15 seconds; stopping all local tunnels. The lab remains running.' >&2
            return 1
        fi
        sleep 0.1
    done
    for index in "${!page_services[@]}"; do
        echo "${page_titles[$index]}: http://127.0.0.1:${page_ports[$index]}/${page_paths[$index]}/"
    done
    echo 'Keep this terminal open. Ctrl+C stops all tunnels only; use scripts/down.sh to stop the cluster.'
    # Bash 3 (the macOS default) has no wait -n. Poll all children so a failed
    # tunnel promptly terminates its siblings, including after startup.
    while :; do
        page_forward_stopped || return
        sleep 0.1
    done
}

k() { kubectl --context "$context" --request-timeout=10s -n shardshop "$@"; }

provision_rabbitmq_credentials() {
    local existing retained key
    existing=$(k get secret shardshop-rabbitmq --ignore-not-found -o name)
    if [[ -n "$existing" ]]; then
        # Never print Secret contents or rotate credentials on retained storage.
        k get secret shardshop-rabbitmq -o json | jq -e '
            .data as $data | ["users.json", "admin-password", "order-password", "ledger-password", "erlang-cookie"] |
            all(.[]; $data[.] | type == "string" and length > 0)' >/dev/null || {
            echo 'RabbitMQ Secret is incomplete. Restore shardshop-rabbitmq from backup before reconciling.' >&2
            exit 1
        }
        return
    fi
    retained=$(k get statefulset/shardshop-rabbitmq persistentvolumeclaim/data-shardshop-rabbitmq-0 \
        --ignore-not-found -o name)
    [[ -z "$retained" ]] || {
        echo 'RabbitMQ data exists but its Secret is missing. Restore shardshop-rabbitmq from backup; credentials were not regenerated.' >&2
        exit 1
    }
    # The invocation directory is private and removed by lifecycle_cleanup.
    for key in admin order ledger erlang-cookie; do
        openssl rand -hex 32 | tr -d '\n' >"$work/rabbitmq-$key"
    done
    jq -n --rawfile admin "$work/rabbitmq-admin" --rawfile order "$work/rabbitmq-order" \
        --rawfile ledger "$work/rabbitmq-ledger" -f "$work/chart/files/rabbitmq/users.jq" >"$work/rabbitmq-users.json"
    k create secret generic shardshop-rabbitmq \
        --from-file="users.json=$work/rabbitmq-users.json" \
        --from-file="admin-password=$work/rabbitmq-admin" \
        --from-file="order-password=$work/rabbitmq-order" \
        --from-file="ledger-password=$work/rabbitmq-ledger" \
        --from-file="erlang-cookie=$work/rabbitmq-erlang-cookie"
}

render() {
    local mode=$1
    shift
    helm template shardshop "$work/chart" --namespace shardshop \
        --values "$work/environment.yaml" --values "$work/inventory.yaml" \
        --set-string "render=$mode" "$@" |
        if [[ "$mode" == inventory || "$mode" == kind-configuration ]]; then
            sed '/^# Source:/d; /^---$/d'
        else cat; fi
}

snapshot_configuration() {
    # All gates and Helmfile releases consume this one immutable invocation copy.
    work=$(mktemp -d)
    cp -R "$root/infra/helm/shardshop" "$work/chart"
    cp "$root/infra/helmfile.yaml.gotmpl" "$work/helmfile.yaml.gotmpl"
    export SHARDSHOP_ROOT="$root" SHARDSHOP_SNAPSHOT="$work"
    cp -R "$root/database" "$work/chart/migrations"
    sed "s/^name: shardshop$/name: $cluster_name/" "$root/infra/kind.yaml" >"$work/chart/kind.yaml"
    cp "${SHARDSHOP_ENV_VALUES:-$root/infra/helm/shardshop/kind-values.yaml}" "$work/environment.yaml"
    cp "${SHARDSHOP_INVENTORY:-$root/infra/shards.yaml}" "$work/inventory.yaml"
    # Helmfile parses these release inputs even when selecting application only.
    cp "$root/infra/helm/cnpg/values.yaml" "$work/operator-values.yaml"
    cp "$root/infra/helm/cnpg/cloudnative-pg-0.29.1.tgz" "$work/operator.tgz"
    render inventory >"$work/inventory.json"
    render kind-configuration >"$work/kind.json"
    render infrastructure >"$work/infrastructure.yaml"
}

require_memory() {
    local mem
    # The 12 GB Docker VM of infra/ARCHITECTURE.md (Local infrastructure rules) reports about 11.7 GiB.
    mem=$(docker info --format '{{.MemTotal}}')
    (( mem >= 11 * 1024**3 + 512 * 1024**2 )) \
        || { echo "Docker VM has $(( mem / 1024**2 )) MiB; set Docker Desktop to 12 GB" >&2; exit 1; }
}

verify_kind() {
    local phase=$1
    shift
    echo "Verifying kind phase: $phase"
    # Both entry points compile these observers first. Direct Failsafe goals
    # reuse them; no application artifact is needed on the observer classpath.
    mvn -B -ntp -f "$root/pom.xml" -pl shardshop-testing -Pkind \
        -Dshardshop.infrastructureOnly=true failsafe:integration-test failsafe:verify \
        -Dit.test=KindIT \
        -Dshardshop.kind.tags="$phase" -Dshardshop.kind.snapshot="$work" \
        -Dshardshop.kind.cluster="$cluster_name" -Dshardshop.kind.fresh="$created_cluster" \
        -Dshardshop.kind.kubeconfig="${SHARDSHOP_TEST_KUBECONFIG:-}" "$@"
}

resume_cluster() {
    local node running attempt started_nodes=false
    verify_kind kind-nodes
    # down.sh turns the restart policy off. A running cluster gets kind's default again.
    docker update --restart=on-failure:1 $nodes >/dev/null
    for node in $nodes; do
        running=$(docker inspect --format '{{.State.Running}}' "$node")
        if [[ "$running" != true ]]; then
            docker start "$node" >/dev/null
            started_nodes=true
        fi
    done
    # kind respects KUBECONFIG, otherwise updating ~/.kube/config.
    kind export kubeconfig --name "$cluster_name"
    for attempt in {1..60}; do
        k get --raw /readyz >/dev/null 2>&1 && break
        (( attempt < 60 )) || { echo "$context API server did not start" >&2; exit 1; }
        sleep 2
    done
    k wait --for=condition=Ready nodes --all --timeout=180s
    verify_kind kind-retention
    # Refresh downward-API HOST_IP after Docker assigns new node addresses.
    if [[ "$started_nodes" == true ]]; then
        k -n kube-system rollout restart daemonset/kindnet
        k -n kube-system rollout status daemonset/kindnet --timeout=180s
    fi
}

verify_dns() {
    local probe_run="dns-$(openssl rand -hex 6)"
    k -n kube-system rollout status deployment/coredns --timeout=180s
    render dns-probes --set-literal "probe.nodes=${nodes//$'\n'/,}" \
        --set-literal "probe.run=$probe_run" >"$work/dns-probes.yaml"
    verify_kind kind-dns
}
