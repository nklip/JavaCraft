package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import org.picocontainer.Disposable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * A database session that holds the transaction advisory lock of one seller creation key, as a slow concurrent
 * creation does. Product then waits for the lock until its database deadline.
 */
public final class CatalogLock implements Disposable {
    private final CatalogShards shards;
    private Connection session;
    private CatalogShards.Shard shard;
    private String region;
    private String key;

    public CatalogLock(CatalogShards shards) {
        this.shards = shards;
    }

    public void hold(String region, String key) throws SQLException {
        this.region = region;
        this.key = key;
        this.shard = shards.ofKey(region, key);
        session = shards.superuser(shard);
        session.setAutoCommit(false);
        try (PreparedStatement statement = session.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, CatalogRouting.creationLock("seller", region, key));
            statement.execute();
        }
    }

    public void release() throws SQLException {
        if (session != null) {
            session.rollback();
            session.close();
            session = null;
        }
    }

    public CatalogShards.Shard shard() {
        return shard;
    }

    public String region() {
        return region;
    }

    public String key() {
        return key;
    }

    @Override
    public void dispose() {
        try {
            release();
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not release the advisory lock session", failure);
        }
    }
}
