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
        return new IdGenerator(configuration.generatorId());
    }
}
