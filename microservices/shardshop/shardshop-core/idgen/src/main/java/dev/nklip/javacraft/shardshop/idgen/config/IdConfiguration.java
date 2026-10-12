package dev.nklip.javacraft.shardshop.idgen.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;
import java.util.OptionalLong;

@ConfigMapping(prefix = "shardshop.id")
public interface IdConfiguration {

    /** A fixed live generator ID, only for disposable tests outside Kubernetes; a deployed JVM reserves its ID. */
    OptionalLong generatorId();

    Registry registry();

    /** The registry ConfigMap in the Kubernetes API. A pinned UID makes the JVM reserve a new ID at each start. */
    interface Registry {

        /** The original UID of the registry: the registryUid key of the shardshop-generator-identity ConfigMap. */
        Optional<String> uid();

        @WithDefault("https://kubernetes.default.svc")
        String url();

        @WithDefault("shardshop")
        String namespace();

        @WithDefault("/var/run/secrets/kubernetes.io/serviceaccount/token")
        String tokenFile();

        @WithDefault("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt")
        String caFile();
    }
}
