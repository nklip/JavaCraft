package dev.nklip.javacraft.shardshop.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardTopologyTest {

    @Test
    void preservesOrderInAnImmutableSnapshot() {
        List<Shard> supplied = new ArrayList<>(List.of(new Shard("shard-b", "EU"), new Shard("shard-a", "US")));
        ShardTopology topology = new ShardTopology(supplied);

        assertTrue(supplied.remove(new Shard("shard-a", "US")));

        assertEquals(List.of(new Shard("shard-b", "EU"), new Shard("shard-a", "US")), topology.shards());
        assertThrows(UnsupportedOperationException.class, () -> topology.shards().add(new Shard("shard-c", "ASIA")));
    }

    @Test
    void parsesThePublishedCommaSeparatedNames() {
        assertEquals(new ShardTopology(List.of(new Shard("shard-a", "US"), new Shard("shard-b", "EU"))),
                ShardTopology.fromNamesAndRegions("shard-a,shard-b", "US,EU"));
    }

    @Test
    void rejectsNoShards() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ShardTopology(List.of()));

        assertEquals("Shard topology must contain a nonempty list of distinct shards", exception.getMessage());
    }

    @Test
    void rejectsDuplicateShards() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ShardTopology.fromNamesAndRegions("shard-a,shard-a", "US,EU"));

        assertEquals("Shard topology must contain a nonempty list of distinct shards", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",shard-a", "shard-a,", "shard-a,,shard-b", "shard-a, ,shard-b"})
    void rejectsEmptyNames(String names) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ShardTopology.fromNamesAndRegions(names, "US,".repeat(names.split(",", -1).length - 1) + "US"));

        assertEquals("Shard name must not be blank", exception.getMessage());
    }

    @Test
    void rejectsMismatchedNamesAndRegions() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ShardTopology.fromNamesAndRegions("shard-a,shard-b", "US"));

        assertEquals("Each shard must have a region", exception.getMessage());
    }

    @Test
    void rejectsMissingRegions() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ShardTopology.fromNamesAndRegions("shard-a", null));

        assertEquals("Shard regions are required", exception.getMessage());
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> new ShardTopology(null));
        assertThrows(NullPointerException.class, () -> new ShardTopology(Arrays.asList(new Shard("shard-a", "US"), null)));
        NullPointerException exception = assertThrows(NullPointerException.class, () -> ShardTopology.fromNamesAndRegions(null, "US"));

        assertEquals("Shard cluster names are required", exception.getMessage());
    }
}
