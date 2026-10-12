package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.flywaydb.core.api.output.MigrateResult;
import org.picocontainer.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The shard databases of one scenario, in inventory order. The scenario end drops them. */
public final class CatalogShards implements Disposable {
    public static final Path CATALOG_STREAM = ShardShopFiles.resolve("database/shard/catalog");

    private final CatalogDatabase database = CatalogDatabase.instance();
    private final List<Shard> shards = new ArrayList<>();

    public record Shard(String name, String region, String database) {
    }

    /** Creates a database for each "name=REGION" entry of the inventory and migrates the catalog stream. */
    public void create(String inventory) throws SQLException {
        for (String entry : inventory.split(",")) {
            String[] parts = entry.trim().split("=");
            shards.add(new Shard(parts[0], parts[1], database.createDatabase()));
        }
        for (Shard shard : shards) {
            migrate(shard, CATALOG_STREAM);
        }
    }

    /** One shard of the region US without migrations: the only shard of its inventory. */
    public Shard createSingle() throws SQLException {
        Shard shard = new Shard("shard-a", "US", database.createDatabase());
        shards.add(shard);
        return shard;
    }

    /** Migrates with the placeholders that the migration Jobs of the lab receive for this inventory position. */
    public MigrateResult migrate(Shard shard, Path location) {
        return database.migrate(shard.database(), location, Map.of(
                "shardRegion", shard.region(),
                "shardIndex", Integer.toString(shards.indexOf(shard)),
                "shardCount", Integer.toString(shards.size())));
    }

    /** A copy of the catalog stream with only the given files. */
    public static Path streamWith(Path directory, String... files) throws IOException {
        Path location = Files.createTempDirectory(directory, "catalog-stream-");
        for (String file : files) {
            Files.copy(CATALOG_STREAM.resolve(file), location.resolve(file));
        }
        return location;
    }

    public List<Shard> all() {
        return List.copyOf(shards);
    }

    public Shard only() {
        if (shards.size() != 1) {
            throw new IllegalStateException("The scenario has " + shards.size() + " shards");
        }
        return shards.getFirst();
    }

    public List<String> regions() {
        return shards.stream().map(Shard::region).toList();
    }

    public Shard ofSeller(String sellerId) {
        return shards.get(CatalogRouting.sellerShard(sellerId, shards.size()));
    }

    public Shard ofKey(String region, String key) {
        return shards.get(CatalogRouting.keyShard(regions(), region, key));
    }

    public Connection superuser(Shard shard) throws SQLException {
        return database.superuser(shard.database());
    }

    @Override
    public void dispose() {
        for (Shard shard : shards) {
            try {
                database.dropDatabase(shard.database());
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not drop " + shard.database(), failure);
            }
        }
    }
}
