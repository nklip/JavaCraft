package dev.nklip.javacraft.shardshop.idgen.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.idgen.JdkRootCertificate;
import dev.nklip.javacraft.shardshop.idgen.allocation.KubernetesRegistry;
import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdGenerationConfigurationTest {

    private static final String UID = "3b439d70-a189-4a6e-8045-ab5b1fa140ac";
    private final IdConfiguration configuration = mock(IdConfiguration.class);
    private final IdConfiguration.Registry registry = mock(IdConfiguration.Registry.class);
    private final IdGenerationConfiguration.Reservation reservation = mock(IdGenerationConfiguration.Reservation.class);

    @TempDir
    Path directory;

    @Test
    void aFixedIdOutsideAPodNeedsNoReservation() throws Exception {
        configure(OptionalLong.of(7), Optional.empty());

        assertEquals(7, IdGenerationConfiguration.generatorId(configuration, false, reservation));
        verify(reservation, never()).reserve(any());
    }

    @Test
    void aPodCannotUseAFixedId() throws Exception {
        configure(OptionalLong.of(7), Optional.empty());

        assertEquals("A Kubernetes pod must reserve its generator ID; set shardshop.id.registry.uid instead of"
                + " shardshop.id.generator-id", assertThrows(IllegalStateException.class,
                () -> IdGenerationConfiguration.generatorId(configuration, true, reservation)).getMessage());
        verify(reservation, never()).reserve(any());
    }

    @Test
    void aFixedIdCannotReplaceAReservation() throws Exception {
        configure(OptionalLong.of(7), Optional.of(UID));

        assertEquals("Set shardshop.id.registry.uid or shardshop.id.generator-id, not both",
                assertThrows(IllegalStateException.class,
                        () -> IdGenerationConfiguration.generatorId(configuration, false, reservation)).getMessage());
        verify(reservation, never()).reserve(any());
    }

    @Test
    void anIdSourceIsRequired() throws Exception {
        configure(OptionalLong.empty(), Optional.empty());

        assertEquals("Set shardshop.id.registry.uid to reserve a generator ID, or shardshop.id.generator-id for a"
                + " disposable test outside Kubernetes", assertThrows(IllegalStateException.class,
                () -> IdGenerationConfiguration.generatorId(configuration, false, reservation)).getMessage());
        verify(reservation, never()).reserve(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "3B439D70-a189-4a6e-8045-ab5b1fa140ac", UID + "\n"})
    void aRegistryUidMustBeCanonicalBeforeAnyRequest(String uid) throws Exception {
        configure(OptionalLong.empty(), Optional.of(uid));

        assertEquals("A pinned generator registry UID is required; set shardshop.id.registry.uid"
                        + " (SHARDSHOP_ID_REGISTRY_UID) from the identity ConfigMap.",
                assertThrows(IllegalArgumentException.class,
                        () -> IdGenerationConfiguration.generatorId(configuration, true, reservation)).getMessage());
        verify(reservation, never()).reserve(any());
    }

    @Test
    void aPodReservesItsIdInTheRegistry() throws Exception {
        configure(OptionalLong.empty(), Optional.of(UID));
        when(reservation.reserve(registry)).thenReturn(42);

        assertEquals(42, IdGenerationConfiguration.generatorId(configuration, true, reservation));
    }

    @Test
    void anAllocationFailureKeepsOnlyItsFixedMessage() throws Exception {
        configure(OptionalLong.empty(), Optional.of(UID));
        when(reservation.reserve(registry)).thenAnswer(invocation -> {
            KubernetesRegistry.connect("http://sensitive.example", "shardshop", "token", "ca.crt");
            return 1;
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> IdGenerationConfiguration.generatorId(configuration, true, reservation));
        assertEquals("Kubernetes API access is not configured correctly; check shardshop.id.registry.url,"
                + " namespace and ca-file.", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void anotherIoFailureHidesItsText() throws Exception {
        configure(OptionalLong.empty(), Optional.of(UID));
        when(reservation.reserve(registry)).thenThrow(new IOException("sensitive text"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> IdGenerationConfiguration.generatorId(configuration, true, reservation));
        assertEquals("Generator allocation failed; check the registry and Kubernetes API access.",
                failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void anInterruptedReservationKeepsTheInterruptFlag() throws Exception {
        configure(OptionalLong.empty(), Optional.of(UID));
        when(reservation.reserve(registry)).thenThrow(new InterruptedException("stop"));

        try {
            assertEquals("Generator allocation was interrupted.", assertThrows(IllegalStateException.class,
                    () -> IdGenerationConfiguration.generatorId(configuration, true, reservation)).getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @ParameterizedTest
    @CsvSource({"NORMAL, 10.96.0.1, true", "NORMAL, '', false", "NORMAL, ' ', false", "TEST, 10.96.0.1, false",
            "DEVELOPMENT, 10.96.0.1, false"})
    void onlyAPackagedJvmWithTheKubernetesServiceHostIsAPod(LaunchMode mode, String host, boolean pod) {
        assertEquals(pod, IdGenerationConfiguration.pod(mode, Map.of("KUBERNETES_SERVICE_HOST", host)));
        assertFalse(IdGenerationConfiguration.pod(mode, Map.of()));
    }

    @Test
    void theProducerUsesAFixedIdOutsideKubernetes() {
        // The producer refuses a fixed ID in a pod; a build host is not a pod.
        assumeFalse(System.getenv().containsKey("KUBERNETES_SERVICE_HOST"));
        configure(OptionalLong.of(7), Optional.empty());

        IdGenerator generator = new IdGenerationConfiguration().idGenerator(configuration);
        assertEquals(7, (generator.nextId() >>> 12) & 1023);
    }

    @Test
    void theProducerReservesThroughTheKubernetesApi() throws Exception {
        configure(OptionalLong.empty(), Optional.of(UID));
        when(registry.url()).thenReturn("https://127.0.0.1:" + closedPort());
        when(registry.namespace()).thenReturn("shardshop");
        when(registry.tokenFile()).thenReturn(Files.writeString(directory.resolve("token"), "token").toString());
        when(registry.caFile())
                .thenReturn(Files.writeString(directory.resolve("ca.crt"), JdkRootCertificate.pem()).toString());

        assertEquals("Generator registry could not be read; check registry existence, state, Kubernetes API access,"
                        + " the service account token and GET permission.",
                assertThrows(IllegalStateException.class,
                        () -> new IdGenerationConfiguration().idGenerator(configuration)).getMessage());
    }

    private void configure(OptionalLong generatorId, Optional<String> uid) {
        when(configuration.generatorId()).thenReturn(generatorId);
        when(configuration.registry()).thenReturn(registry);
        when(registry.uid()).thenReturn(uid);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
