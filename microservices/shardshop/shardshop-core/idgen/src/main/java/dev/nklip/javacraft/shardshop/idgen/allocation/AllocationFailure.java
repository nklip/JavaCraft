package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;

/** Only fixed, local diagnostics may cross the logging boundary of the allocation; never API text or tokens. */
public final class AllocationFailure extends IOException {

    AllocationFailure(Reason reason) {
        super(reason.message);
    }

    enum Reason {
        EXHAUSTED("Generator IDs exhausted; stop ID-producing services and retire all prior emitters, data, backups"
                + " and replay inputs before a fresh-lab reset."),
        STALE_REGISTRY("Generator registry is stale or has been replaced; verify shardshop.id.registry.uid"
                + " (SHARDSHOP_ID_REGISTRY_UID) against the identity ConfigMap and investigate rollback before"
                + " restarting."),
        DEADLINE("Generator allocation exceeded its 30-second deadline; check Kubernetes API availability"
                + " and competing starts before retrying."),
        API_CLIENT("Kubernetes API access is not configured correctly; check shardshop.id.registry.url, namespace"
                + " and ca-file."),
        REGISTRY_READ("Generator registry could not be read; check registry existence, state, Kubernetes API access,"
                + " the service account token and GET permission."),
        REGISTRY_WRITE("Generator registry could not be updated; check registry existence, the service account token"
                + " and PATCH permission.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
