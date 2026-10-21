package dev.nklip.javacraft.shardshop.ledger.config;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class LedgerConfigurationTest {
    private static final Path MIGRATIONS = Path.of("../infra/helm/shardshop/templates/_migrations.tpl");

    /** Step 4.5 regression: the default named ledger-rw, but the ledger-db cluster serves ledger-db-rw. */
    @Test
    void defaultDatabaseUrlNamesTheServiceOfTheChartLedgerMigration() throws IOException {
        Matcher migration = Pattern.compile("jdbc:postgresql://([a-z0-9-]+)\\.\\{\\{[^}]*}}\\.svc:5432/ledger\\?")
                .matcher(Files.readString(MIGRATIONS));
        assertTrue(migration.find(), "The chart's ledger migration URL");
        // Quarkus reads durations such as the broker timeout default 5s with this converter.
        LedgerConfiguration defaults = new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withMapping(LedgerConfiguration.class).build().getConfigMapping(LedgerConfiguration.class);
        // The default repeats the shared policy; a policy change must change the default.
        assertEquals(java.util.Set.copyOf(dev.nklip.javacraft.shardshop.common.CurrencyPolicy.SUPPORTED_CURRENCIES),
                defaults.allowedCurrencies());
        assertEquals("jdbc:postgresql://" + migration.group(1) + ":5432/ledger", defaults.database().url());
    }
}
