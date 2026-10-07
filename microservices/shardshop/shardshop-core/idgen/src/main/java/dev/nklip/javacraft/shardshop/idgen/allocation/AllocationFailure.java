package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;

/** Only fixed, local diagnostics may cross the launcher's logging boundary. */
final class AllocationFailure extends IOException {

    AllocationFailure(Reason reason) {
        super(reason.message);
    }

    enum Reason {
        EXHAUSTED("Generator IDs exhausted; stop ID-producing services and retire all prior emitters, data, backups"
                + " and replay inputs before a fresh-lab reset."),
        STALE_REGISTRY("Generator registry is stale or has been replaced; verify SHARDSHOP_GENERATOR_REGISTRY_UID"
                + " against the identity ConfigMap and investigate rollback before restarting."),
        DEADLINE("Generator allocation exceeded its 30-second deadline; check Kubernetes API availability,"
                + " registry PATCH permission and competing starts before retrying."),
        HOST_CONTEXT("Host launches require SHARDSHOP_KUBE_CONTEXT=kind-shardshop; correct the launcher environment."),
        IN_CLUSTER_CONTEXT("In-cluster launches use the pod credentials; unset SHARDSHOP_KUBE_CONTEXT."),
        PINNED_UID("A pinned generator registry UID is required; set SHARDSHOP_GENERATOR_REGISTRY_UID"
                + " from the identity ConfigMap."),
        KUBECTL_START("kubectl could not be started; install it on PATH and check executable permissions."),
        REGISTRY_READ("Generator registry could not be read; check registry existence, state, Kubernetes API access"
                + " and GET permission.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
