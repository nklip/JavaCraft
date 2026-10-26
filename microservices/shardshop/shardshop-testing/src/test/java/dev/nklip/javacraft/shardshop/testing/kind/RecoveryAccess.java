package dev.nklip.javacraft.shardshop.testing.kind;

import java.sql.Connection;
import java.util.List;

/** Privileged recovery connections; the caller must hold the writer-freeze/owned-target lifetime. */
public interface RecoveryAccess {
    Connection open(String database) throws Exception;
    List<String> shards();

    static RecoveryAccess owned(OwnedRestoreTarget target) {
        return new RecoveryAccess() {
            @Override public Connection open(String database) throws Exception {
                return target.connection(database);
            }
            @Override public List<String> shards() { return KindBackupRestore.SHARDS; }
        };
    }
}
