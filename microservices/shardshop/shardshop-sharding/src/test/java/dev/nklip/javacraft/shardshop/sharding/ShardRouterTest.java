package dev.nklip.javacraft.shardshop.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

class ShardRouterTest {

    private final ShardRouter router = new ShardRouter(
            ShardTopology.fromNamesAndRegions("shard-a,shard-b,shard-c", "US,EU,ASIA"));

    @ParameterizedTest(name = "{0}")
    @CsvFileSource(resources = "/routing-vectors.csv")
    void routesGoldenVectors(String name, long id, String expectedShard) {
        assertEquals(expectedShard, router.route(id).clusterName(), name);
    }

    @ParameterizedTest
    @CsvSource({"1, US", "880803840000004097, EU", "9223372036854775807, ASIA"})
    void routesToTheImmutableHomeRegion(long id, String region) {
        assertEquals(region, router.route(id).region());
    }

    // Expected indices were calculated with Python hashlib, independently of Java.
    @ParameterizedTest
    @CsvSource({
            "1, 0, 1, 3",
            "2, 0, 1, 1",
            "3, 0, 0, 2",
            "9007199254740993, 0, 1, 1",
            "9223372036854775807, 0, 1, 1"
    })
    void routesOneTwoAndFourShardTopologies(long id, int oneShardIndex, int twoShardIndex, int fourShardIndex) {
        assertRoute(id, oneShardIndex, "shard-a");
        assertRoute(id, twoShardIndex, "shard-a,shard-b");
        assertRoute(id, fourShardIndex, "shard-a,shard-b,shard-c,shard-d");
    }

    @Test
    void usesPublishedOrderRatherThanSortingNames() {
        ShardTopology topology = ShardTopology.fromNamesAndRegions("shard-b,shard-a", "EU,US");

        assertEquals("shard-a", new ShardRouter(topology).route(1L).clusterName());
    }

    @Test
    void requiresTopology() {
        NullPointerException exception = assertThrows(NullPointerException.class, () -> new ShardRouter(null));

        assertEquals("Shard topology is required", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveIds(long id) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> router.route(id));
        assertEquals("Shard routing requires a positive ID, but was " + id, exception.getMessage());
    }

    @Test
    void failsWhenSha256IsUnavailable() {
        NoSuchAlgorithmException missing = new NoSuchAlgorithmException("SHA-256");
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256")).thenThrow(missing);

            IllegalStateException exception = assertThrows(IllegalStateException.class, () -> router.route(1L));

            assertEquals("SHA-256 is not available", exception.getMessage());
            assertSame(missing, exception.getCause());
        }
    }

    private static void assertRoute(long id, int expectedIndex, String names) {
        String regions = Arrays.stream(names.split(",")).map(name -> "US").collect(Collectors.joining(","));
        ShardTopology topology = ShardTopology.fromNamesAndRegions(names, regions);

        assertSame(topology.shards().get(expectedIndex), new ShardRouter(topology).route(id));
    }
}
