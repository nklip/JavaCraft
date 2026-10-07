package dev.nklip.javacraft.shardshop.idgen.allocation;

/** Immutable snapshot; resourceVersion is an opaque CAS token, never an ordering key. */
public record RegistryState(String uid, String resourceVersion, int highWaterMark) {

    public RegistryState {
        requireUid(uid);
        if (resourceVersion == null || !resourceVersion.matches("[A-Za-z0-9._-]{1,128}")
                || highWaterMark < 0 || highWaterMark > 1023) {
            throw new IllegalArgumentException("Invalid generator registry state");
        }
    }

    static void requireUid(String uid) {
        if (uid == null || !uid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("A pinned generator registry UID is required");
        }
    }
}
