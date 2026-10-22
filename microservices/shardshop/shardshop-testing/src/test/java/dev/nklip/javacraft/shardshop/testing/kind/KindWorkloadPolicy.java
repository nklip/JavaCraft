package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The deployed workloads may configure their HTTP clients, but cannot import arbitrary configuration. */
final class KindWorkloadPolicy {
    private static final Set<String> INTEGER_SETTINGS = Set.of(
            "SHARDSHOP_READER_REQUEST_RATE", "SHARDSHOP_READER_WORKERS", "SHARDSHOP_READER_CONNECTIONS",
            "SHARDSHOP_READER_MAX_ATTEMPTS", "SHARDSHOP_SEEDER_MAX_ATTEMPTS");
    private static final Set<String> DURATION_SETTINGS = Set.of(
            "SHARDSHOP_READER_CONNECT_TIMEOUT", "SHARDSHOP_READER_REQUEST_DEADLINE",
            "SHARDSHOP_READER_RETRY_INTERVAL", "SHARDSHOP_READER_MAX_CONNECTION_LIFETIME",
            "SHARDSHOP_READER_IDLE_TIMEOUT", "SHARDSHOP_READER_REPORT_INTERVAL",
            "SHARDSHOP_SEEDER_CONNECT_TIMEOUT", "SHARDSHOP_SEEDER_REQUEST_TIMEOUT",
            "SHARDSHOP_SEEDER_RETRY_BACKOFF", "SHARDSHOP_SEEDER_MAX_RETRY_BACKOFF");
    // SmallRye accepts seconds, unit suffixes and ISO-8601 durations. The applications validate ranges and
    // relationships between settings; this allowlist excludes configuration expressions and connection strings.
    private static final Pattern DURATION = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?(?:ms|[smhd])?");
    /** Initial rates: 0 or a positive decimal with at most three fractional digits, without sign or exponent. */
    private static final Pattern RATE = Pattern.compile("(0|[1-9][0-9]{0,4})(\\.[0-9]{1,3})?");

    private KindWorkloadPolicy() { }

    static void httpOnly(JsonNode spec) throws IOException {
        require(spec.path("automountServiceAccountToken").isBoolean() && !spec.path("automountServiceAccountToken").booleanValue(),
                "HTTP-only workload must disable API token automount");
        require(emptyArray(spec, "imagePullSecrets"), "HTTP-only workload must not reference image pull credentials");
        require(optionalArray(spec, "volumes"), "HTTP-only workload has invalid volumes");
        for (JsonNode volume : spec.path("volumes")) {
            require(volume.path("name").isTextual() && volume.path("emptyDir").isObject() && volume.size() == 2,
                    "HTTP-only workload has a non-scratch or credential-bearing volume");
        }
        require(spec.path("containers").isArray() && !spec.path("containers").isEmpty(), "HTTP-only workload has no containers");
        for (String group : List.of("containers", "initContainers", "ephemeralContainers")) {
            require(optionalArray(spec, group), "HTTP-only workload has an invalid container group");
            for (JsonNode container : spec.path(group)) validateContainer(container);
        }
    }

    private static void validateContainer(JsonNode container) throws IOException {
        require(emptyArray(container, "envFrom"), "HTTP-only workload uses envFrom");
        require(emptyArray(container, "command") && emptyArray(container, "args"),
                "HTTP-only workload overrides the image command or arguments");
        require(!container.has("lifecycle") || container.path("lifecycle").isObject() && container.path("lifecycle").isEmpty(),
                "HTTP-only workload has lifecycle hooks");
        for (String probe : List.of("startupProbe", "readinessProbe", "livenessProbe")) {
            require(!container.path(probe).has("exec"), "HTTP-only workload has an executable probe");
        }
        require(optionalArray(container, "env"), "HTTP-only workload has invalid environment settings");
        Set<String> names = new HashSet<>();
        for (JsonNode variable : container.path("env")) {
            require(!variable.has("valueFrom") && variable.path("name").isTextual() && variable.path("value").isTextual()
                            && variable.size() == 2,
                    "HTTP-only workload requires literal environment settings");
            String name = variable.path("name").textValue();
            require(names.add(name), "HTTP-only workload repeats an environment setting");
            validateSetting(name, variable.path("value").textValue());
        }
        require(!container.path("securityContext").path("privileged").asBoolean() && emptyArray(container, "volumeDevices"),
                "HTTP-only workload has a privileged container or block device");
    }

    private static void validateSetting(String name, String value) throws IOException {
        if (name.equals("SHARDSHOP_READER_PRODUCT_URL") || name.equals("SHARDSHOP_SEEDER_PRODUCT_URL")
                || name.equals("SHARDSHOP_PRODUCER_PRODUCT_URL") || name.equals("SHARDSHOP_PRODUCER_ORDER_URL")) {
            validateUrl(value);
        } else if (name.equals("SHARDSHOP_READER_CATALOG_VERSION") || name.equals("SHARDSHOP_SEEDER_CATALOG_VERSION")) {
            require(value.equals("catalog-v2"), "HTTP-only workload has an unsupported catalog version");
        } else if (name.equals("SHARDSHOP_PRODUCER_CATALOG_VERSION")) {
            require(value.equals("catalog-v2"), "HTTP-only producer has unsupported catalog version");
        } else if (name.equals("SHARDSHOP_PRODUCER_BUYER_VERSION")) {
            require(value.equals("buyers-v1"), "HTTP-only producer has unsupported buyer version");
        } else if (name.equals("SHARDSHOP_PRODUCER_RUN_NAME")) {
            // SmallRye Config expands ${...} and rewrites the escapes $$ and \$, thus no form of $ is literal.
            require(!value.isBlank() && value.length() <= 200 && value.strip().equals(value)
                            && !value.contains("\r") && !value.contains("\n") && !value.contains("$"),
                    "HTTP-only producer requires a literal durable run identity");
        } else if (name.equals("SHARDSHOP_PRODUCER_REJECTED_ORDER_PERCENT") || name.equals("SHARDSHOP_PRODUCER_MAX_ATTEMPTS")) {
            require(value.matches(name.endsWith("REJECTED_ORDER_PERCENT") ? "-1|[0-9]+" : "[0-9]+"), "HTTP-only producer has a nonnumeric selection/retry setting");
        } else if (name.equals("SHARDSHOP_PRODUCER_ORDER_RATE") || name.equals("SHARDSHOP_SEEDER_REQUEST_RATE")) {
            require(RATE.matcher(value).matches(), "HTTP-only workload has an invalid initial rate");
        } else if (name.equals("SHARDSHOP_SEEDER_INTERACTIVE")) {
            require(value.equals("true"), "HTTP-only seeder has an invalid interactive mode");
        } else if (name.matches("SHARDSHOP_PRODUCER_(CONNECT_TIMEOUT|REQUEST_TIMEOUT|POLL_INTERVAL|ORDER_DEADLINE|STARTUP_DEADLINE|RETRY_BACKOFF|MAX_RETRY_BACKOFF)")) {
            require(durationValue(value), "HTTP-only producer has invalid HTTP duration setting");
        } else if (INTEGER_SETTINGS.contains(name)) {
            require(value.matches("[+-]?[0-9]+"), "HTTP-only workload has a nonnumeric HTTP setting");
        } else if (DURATION_SETTINGS.contains(name)) {
            require(durationValue(value), "HTTP-only workload has an invalid HTTP duration setting");
        } else {
            throw new IOException("HTTP-only workload has an unsupported environment setting");
        }
    }

    private static boolean durationValue(String value) {
        if (DURATION.matcher(value).matches()) return true;
        try {
            Duration.parse(value);
            return true;
        } catch (java.time.format.DateTimeParseException failure) {
            return false;
        }
    }

    private static void validateUrl(String value) throws IOException {
        try {
            URI uri = new URI(value);
            require(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                            && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null,
                    "HTTP-only workload requires a product URL without credentials, query or fragment");
        } catch (URISyntaxException failure) {
            // Do not attach the parser exception: it includes the potentially credential-bearing input.
            throw new IOException("HTTP-only workload has an invalid product URL");
        }
    }

    private static boolean optionalArray(JsonNode node, String field) {
        return !node.has(field) || node.path(field).isArray();
    }

    private static boolean emptyArray(JsonNode node, String field) {
        return optionalArray(node, field) && node.path(field).isEmpty();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
