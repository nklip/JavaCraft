package dev.nklip.javacraft.shardshop.sharding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

class ShardRouterTest {

    private final ShardRouter router = new ShardRouter();

    @ParameterizedTest(name = "{0}")
    @CsvFileSource(resources = "/routing-vectors.csv")
    void routesGoldenVectors(String name, long id, String expectedShard) {
        assertEquals(expectedShard, router.route(id).clusterName(), name);
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
}
