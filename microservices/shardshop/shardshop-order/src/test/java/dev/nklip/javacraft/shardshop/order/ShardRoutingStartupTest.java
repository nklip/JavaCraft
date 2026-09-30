package dev.nklip.javacraft.shardshop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShardRoutingStartupTest {

    @TempDir
    Path directory;

    @Test
    void failsStartupWhenImportedTopologyIsMissing() {
        Path missing = directory.resolve("missing.properties");

        ConfigDataResourceNotFoundException failure = assertThrows(ConfigDataResourceNotFoundException.class,
                () -> application().run(arguments(missing)));

        assertThat(failure.getResource().toString()).contains("missing.properties");
    }

    @Test
    void failsStartupWhenShardsPropertyIsMissing() throws IOException {
        Path imported = directory.resolve("application.properties");
        assertEquals(imported, Files.writeString(imported, "unrelated=value\n"));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> application().run(arguments(imported)));

        assertThat(failure).hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessage("Required key 'shardshop.routing.shards' not found");
    }

    @Test
    void failsStartupWhenTopologyIsInvalid() throws IOException {
        Path imported = directory.resolve("application.properties");
        assertEquals(imported, Files.writeString(imported, "shardshop.routing.shards=shard-a,shard-a\n"));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> application().run(arguments(imported)));

        assertThat(failure).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessage("Shard topology must contain a nonempty list of distinct shards");
    }

    private SpringApplication application() {
        SpringApplication application = new SpringApplication(OrderApplication.class);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    private String[] arguments(Path imported) {
        return new String[] {"--shardshop.routing.config=" + imported.toUri(), "--spring.main.banner-mode=off", "--logging.level.root=OFF"};
    }
}
