package dev.nklip.javacraft.shardshop.idgen.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@Singleton
class IdGenerationConfiguration {

    @Produces
    @Singleton
    @Startup
    IdGenerator idGenerator(IdConfiguration configuration) {
        long generatorId = configuration.generatorId();
        // Read the launcher's reservation directly; higher-ordinal config sources cannot replace it.
        String reservedId = System.getProperty("shardshop.launcher.reserved-generator-id");
        if (reservedId != null && !Long.toString(generatorId).equals(reservedId)) {
            throw new IllegalStateException("Live generator ID does not match its reserved identity");
        }
        return new IdGenerator(generatorId);
    }
}
