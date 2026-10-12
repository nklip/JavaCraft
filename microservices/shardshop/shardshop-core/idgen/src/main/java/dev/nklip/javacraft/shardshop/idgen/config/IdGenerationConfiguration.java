package dev.nklip.javacraft.shardshop.idgen.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.idgen.allocation.AllocationFailure;
import dev.nklip.javacraft.shardshop.idgen.allocation.GeneratorAllocator;
import dev.nklip.javacraft.shardshop.idgen.allocation.KubernetesRegistry;
import dev.nklip.javacraft.shardshop.idgen.allocation.RegistryState;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

@Singleton
class IdGenerationConfiguration {

    private static final Logger LOG = Logger.getLogger(IdGenerationConfiguration.class);

    /**
     * Each JVM start reserves a new live generator ID before the generator exists; nothing caches the ID. The startup
     * beans with the default priority, for example the routing snapshot, do their checks first, thus a local
     * configuration error uses no generator slot. A bean that injects the generator creates it at that time.
     */
    @Produces
    @Singleton
    @Startup(ObserverMethod.DEFAULT_PRIORITY + 1)
    IdGenerator idGenerator(IdConfiguration configuration) {
        return new IdGenerator(generatorId(configuration, pod(LaunchMode.current(), System.getenv()),
                IdGenerationConfiguration::reserve));
    }

    /** The kubelet sets KUBERNETES_SERVICE_HOST in each container, also with enableServiceLinks: false. */
    static boolean pod(LaunchMode mode, Map<String, String> environment) {
        return mode == LaunchMode.NORMAL && !environment.getOrDefault("KUBERNETES_SERVICE_HOST", "").isBlank();
    }

    static long generatorId(IdConfiguration configuration, boolean pod, Reservation reservation) {
        OptionalLong fixedId = configuration.generatorId();
        Optional<String> uid = configuration.registry().uid();
        if (fixedId.isPresent() && uid.isPresent()) {
            throw new IllegalStateException("Set shardshop.id.registry.uid or shardshop.id.generator-id, not both");
        }
        if (fixedId.isPresent()) {
            if (pod) {
                throw new IllegalStateException("A Kubernetes pod must reserve its generator ID;"
                        + " set shardshop.id.registry.uid instead of shardshop.id.generator-id");
            }
            return fixedId.getAsLong();
        }
        RegistryState.requireUid(uid.orElseThrow(() -> new IllegalStateException("Set shardshop.id.registry.uid to"
                + " reserve a generator ID, or shardshop.id.generator-id for a disposable test outside Kubernetes")));
        try {
            int generatorId = reservation.reserve(configuration.registry());
            LOG.infof("Reserved generator ID %d for this JVM start.", generatorId);
            return generatorId;
        } catch (AllocationFailure failure) {
            // The fixed message only: API responses and exception texts stay out of the log.
            throw new IllegalStateException(failure.getMessage());
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Generator allocation failed; check the registry and Kubernetes API access.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generator allocation was interrupted.");
        }
    }

    private static int reserve(IdConfiguration.Registry registry) throws IOException, InterruptedException {
        try (KubernetesRegistry kubernetes = KubernetesRegistry.connect(registry.url(), registry.namespace(),
                registry.tokenFile(), registry.caFile())) {
            return new GeneratorAllocator(kubernetes, registry.uid().orElseThrow()).allocate();
        }
    }

    @FunctionalInterface
    interface Reservation {
        int reserve(IdConfiguration.Registry registry) throws IOException, InterruptedException;
    }
}
