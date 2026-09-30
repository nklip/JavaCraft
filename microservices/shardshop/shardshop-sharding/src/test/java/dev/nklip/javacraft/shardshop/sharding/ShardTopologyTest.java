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
        List<Shard> supplied = new ArrayList<>(List.of(new Shard("shard-b"), new Shard("shard-a")));
        ShardTopology topology = new ShardTopology(supplied);

        assertTrue(supplied.remove(new Shard("shard-a")));

        assertEquals(List.of(new Shard("shard-b"), new Shard("shard-a")), topology.shards());
        assertThrows(UnsupportedOperationException.class, () -> topology.shards().add(new Shard("shard-c")));
    }

    @Test
    void parsesThePublishedCommaSeparatedNames() {
        assertEquals(new ShardTopology(List.of(new Shard("shard-a"), new Shard("shard-b"))),
                ShardTopology.fromNames("shard-a,shard-b"));
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
                () -> ShardTopology.fromNames("shard-a,shard-a"));

        assertEquals("Shard topology must contain a nonempty list of distinct shards", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",shard-a", "shard-a,", "shard-a,,shard-b", "shard-a, ,shard-b"})
    void rejectsEmptyNames(String names) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ShardTopology.fromNames(names));

        assertEquals("Shard name must not be blank", exception.getMessage());
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> new ShardTopology(null));
        assertThrows(NullPointerException.class, () -> new ShardTopology(Arrays.asList(new Shard("shard-a"), null)));
        NullPointerException exception = assertThrows(NullPointerException.class, () -> ShardTopology.fromNames(null));

        assertEquals("Shard cluster names are required", exception.getMessage());
    }
}
