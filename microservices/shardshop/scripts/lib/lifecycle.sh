# Shared implementation for the four lifecycle scripts. Source it from Bash.
# down.sh and cluster-destroy.sh use only lifecycle_init and require_tools.
# Provisioning and application delivery remain in their respective entry points.

lifecycle_init() {
    root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
    cluster_name=shardshop
    context="kind-$cluster_name"
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

# Own only this invocation's forwards; stopping them never stops the lab.
page_access_cleanup() {
    trap '' INT TERM
    local pid
    for pid in "${forward_pids[@]}"; do kill "$pid" 2>/dev/null || true; done
    for pid in "${forward_pids[@]}"; do wait "$pid" 2>/dev/null || true; done
    rm -rf -- "$forward_state"
}

page_access() {
    local service port index status deadline ready
    local services=(product order) ports=(18080 18081)
    forward_pids=()
    forward_state=$(mktemp -d)
    trap page_access_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    for index in 0 1; do
        service=${services[$index]}
        port=${ports[$index]}
        kubectl --context "$context" -n shardshop port-forward --address 127.0.0.1 \
            "service/shardshop-$service" "$port:8080" > >(
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
        ready=true
        for index in 0 1; do
            service=${services[$index]}
            if ! kill -0 "${forward_pids[$index]}" 2>/dev/null; then
                status=0
                wait "${forward_pids[$index]}" || status=$?
                echo "ShardShop $service forwarding stopped (exit $status); stopping both local tunnels. The lab remains running." >&2
                # Access requires two live tunnels, even after a clean CLI exit.
                [[ "$status" != 0 ]] || status=1
                return "$status"
            fi
            [[ -f "$forward_state/$service-ready" ]] || ready=false
        done
        [[ "$ready" == true ]] && break
        if (( SECONDS >= deadline )); then
            echo 'ShardShop forwarding did not become ready within 15 seconds; stopping both local tunnels. The lab remains running.' >&2
            return 1
        fi
        sleep 0.1
    done
    echo 'Product page: http://127.0.0.1:18080/products/'
    echo 'Order page: http://127.0.0.1:18081/orders/'
    echo 'Keep this terminal open. Ctrl+C stops both tunnels only; use scripts/down.sh to stop the cluster.'
    # Bash 3 (the macOS default) has no wait -n. Poll both children so either
    # tunnel's failure promptly terminates its sibling, including after startup.
    while :; do
        for index in 0 1; do
            if ! kill -0 "${forward_pids[$index]}" 2>/dev/null; then
                status=0
                wait "${forward_pids[$index]}" || status=$?
                echo "ShardShop ${services[$index]} forwarding stopped (exit $status); stopping both local tunnels. The lab remains running." >&2
                [[ "$status" != 0 ]] || status=1
                return "$status"
            fi
        done
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
    cp "$root/infra/kind.yaml" "$work/chart/kind.yaml"
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
    # The 12 GB Docker VM in PLAN.md §5 reports about 11.7 GiB.
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
        -Dshardshop.kind.cluster="$cluster_name" -Dshardshop.kind.fresh="$created_cluster" "$@"
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
