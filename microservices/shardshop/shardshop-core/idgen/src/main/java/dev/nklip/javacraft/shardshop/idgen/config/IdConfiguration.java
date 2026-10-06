package dev.nklip.javacraft.shardshop.idgen.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "shardshop.id")
public interface IdConfiguration {

    long generatorId();
}
