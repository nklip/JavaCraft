package dev.nklip.javacraft.shardshop.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShardTest {

    @Test
    void keepsTheClusterName() {
        assertEquals("shard-a", new Shard("shard-a", "US").clusterName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsBlankClusterNames(String name) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> new Shard(name, "US"));

        assertEquals("Shard name must not be blank", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "EU", "ASIA"})
    void keepsTheRegion(String region) {
        assertEquals(region, new Shard("regional-shard", region).region());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"us", "Europe", "APAC", " US", "US ", "US\n"})
    void rejectsInvalidRegions(String region) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new Shard("shard-a", region));

        assertEquals("Shard region must be US, EU or ASIA", exception.getMessage());
    }

    @Test
    void clusterNameAndRegionAreItsIdentity() {
        assertEquals(new Shard("shard-a", "US"), new Shard("shard-a", "US"));
    }
}
