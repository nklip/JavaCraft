#!/usr/bin/env bash
# Create or safely reconcile the kind/database/broker foundation; never deploy applications.
# Run up.sh afterward to build, migrate and restart the applications.
set -euo pipefail

[[ $# == 0 ]] || { echo 'Usage: cluster-create.sh' >&2; exit 1; }
source "$(dirname -- "${BASH_SOURCE[0]}")/lib/lifecycle.sh"
lifecycle_init
require_tools mvn docker kind kubectl helm helmfile openssl jq
nodes=$(kind get nodes --name "$cluster_name")
snapshot_configuration
render namespace >"$work/namespace.yaml"
require_memory

# Build only the safety observers and their shared test helpers. Install those
# dependencies so each subsequent gate can resolve them without an application build.
# Their published POM chain also needs the two inherited parent POMs. Cache only
# that metadata; do not run either parent's reactor or build unrelated modules.
for parent_pom in "$root/../../pom.xml" "$root/../pom.xml"; do
    mvn -B -ntp -f "$root/pom.xml" -N -Dshardshop.infrastructureOnly=true \
        install:install-file -Dfile="$parent_pom" -DpomFile="$parent_pom"
done
mvn -B -ntp -f "$root/pom.xml" -pl shardshop-testing -am \
    -Dshardshop.infrastructureOnly=true clean install -DinstallAtEnd=true
verify_kind kind-config
if [[ -z "$nodes" ]]; then
    kind create cluster --name "$cluster_name" --config "$work/chart/kind.yaml" --wait 180s
    created_cluster=true
    nodes=$(kind get nodes --name "$cluster_name")
fi
resume_cluster
# An existing lab must prove its allocator protections before provisioning.
# A partial failed bootstrap requires explicit recovery, never a counter reset.
if [[ "$created_cluster" == false ]]; then
    verify_kind kind-allocator
fi
kubectl --context "$context" --request-timeout=30s apply -f "$work/namespace.yaml"
verify_dns

# Preserve existing passwords; only provision roles whose Secret is absent.
for role in catalog_migrator ordering_migrator product_app order_app ordering_cdc ledger_migrator ledger_app; do
    secret="${role//_/-}"
    existing=$(k get secret "$secret" --ignore-not-found -o name)
    if [[ -z "$existing" ]]; then
        openssl rand -hex 32 | tr -d '\n' >"$work/password"
        k create secret generic "$secret" --type=kubernetes.io/basic-auth \
            --from-literal="username=$role" --from-file="password=$work/password"
    fi
done
provision_rabbitmq_credentials
export SHARDSHOP_FRESH="$created_cluster"
helmfile -f "$work/helmfile.yaml.gotmpl" -l stage=infrastructure sync --skip-deps --skip-needs
if [[ "$created_cluster" == true ]]; then
    verify_kind kind-admission-bootstrap
    registry_uid=$(k create configmap shardshop-snowflake-generators --from-literal=highWaterMark=0 \
        -o jsonpath='{.metadata.uid}')
    render generator-identity --set-literal "generatorRegistryUid=$registry_uid" >"$work/generator-identity.yaml"
    k create -f "$work/generator-identity.yaml"
fi
verify_kind kind-allocator
verify_kind kind-infrastructure
echo 'ShardShop cluster, database and broker foundation are ready; no application release was changed.'
echo 'Run scripts/up.sh to build, migrate and start the applications.'
