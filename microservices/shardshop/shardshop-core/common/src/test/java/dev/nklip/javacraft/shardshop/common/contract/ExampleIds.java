package dev.nklip.javacraft.shardshop.common.contract;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The rules for the illustrative IDs of contract examples. The examples use the Snowflake layout of the ARCHITECTURE
 * (epoch 2026-01-01T00:00:00Z, 41 time bits, 10 generator bits, 12 sequence bits) and the routing contract of the
 * three lab shards in inventory order (US, EU, ASIA). The rules are calculated again here from these contracts, not
 * with the production classes.
 */
public final class ExampleIds {
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    public static final Set<String> ENTITY_KEYS = Set.of("sellerId", "productId", "buyerId");
    public static final Set<String> TRANSPORT_KEYS = Set.of("orderId", "sagaId", "commandId", "messageId",
            "resultMessageId");
    private static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private ExampleIds() {
    }

    /** The generator ID bits of a Snowflake ID. */
    public static int generator(String id) {
        return (int) ((Long.parseLong(id) >> 12) & 1023);
    }

    /** The creation time that a Snowflake ID encodes. */
    public static Instant time(String id) {
        return EPOCH.plusMillis(Long.parseLong(id) >> 22);
    }

    /** The home region of a seller or buyer ID: the unsigned SHA-256 of its text modulo the three lab shards. */
    public static String region(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return REGIONS.get(new BigInteger(1, digest).mod(BigInteger.valueOf(REGIONS.size())).intValue());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Every JSON object in a value, the value first. */
    public static List<JsonObject> objects(Object value) {
        List<JsonObject> objects = new ArrayList<>();
        collect(ContractDocument.vertx(value), objects);
        return objects;
    }

    /**
     * The violations of the illustrative ID rules in example values: each expected entity kind occurs; entity IDs
     * are positive signed longs of generators 1..1023, and they differ from the transport IDs and from the IDs of
     * the other entity kinds; transport IDs use generators 1..1023; a status URL names the buyer and order of its
     * object; stock is at most the initial stock; a region is the home region of the seller or buyer ID beside it.
     */
    public static List<String> violations(Collection<?> values, Set<String> expectedEntities) {
        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> entities = new TreeMap<>();
        expectedEntities.forEach(key -> entities.put(key, new TreeSet<>()));
        Set<String> transport = new TreeSet<>();
        for (Object value : values) {
            for (JsonObject node : objects(value)) {
                for (String key : ENTITY_KEYS) {
                    if (node.getValue(key) instanceof String id) {
                        entities.computeIfAbsent(key, ignored -> new TreeSet<>()).add(id);
                    }
                }
                for (String key : TRANSPORT_KEYS) {
                    if (node.getValue(key) instanceof String id) {
                        transport.add(id);
                    }
                }
                if (node.containsKey("statusUrl") && !node.getValue("statusUrl").equals("/api/v1/buyers/"
                        + node.getValue("buyerId") + "/orders/" + node.getValue("orderId"))) {
                    violations.add("statusUrl " + node.getValue("statusUrl") + " does not name the buyer and order of "
                            + node);
                }
                if (node.getValue("stock") instanceof Number stock && node.getValue("initialStock")
                        instanceof Number initial && new BigInteger(stock.toString()).compareTo(
                        new BigInteger(initial.toString())) > 0) {
                    violations.add("stock exceeds initialStock in " + node);
                }
                if (node.getValue("region") instanceof String region) {
                    for (String key : List.of("sellerId", "buyerId")) {
                        if (node.getValue(key) instanceof String id && !region.equals(region(id))) {
                            violations.add(key + " " + id + " routes to " + region(id) + ", not to " + region);
                        }
                    }
                }
            }
        }
        entities.forEach((key, ids) -> {
            if (ids.isEmpty()) {
                violations.add("No illustrative " + key + " found");
            }
            for (String id : ids) {
                if (!positiveLong(id) || generator(id) == 0) {
                    violations.add(key + " " + id + " is not a positive signed long of generators 1..1023");
                } else if (transport.contains(id)) {
                    violations.add(key + " " + id + " is also a transport ID");
                }
            }
            entities.forEach((other, otherIds) -> {
                if (key.compareTo(other) < 0 && otherIds.stream().anyMatch(ids::contains)) {
                    violations.add(key + " and " + other + " share an illustrative ID");
                }
            });
        });
        for (String id : transport) {
            if (!positiveLong(id) || generator(id) == 0) {
                violations.add("Transport ID " + id + " is not a positive signed long of generators 1..1023");
            }
        }
        return violations;
    }

    private static boolean positiveLong(String id) {
        if (!id.matches("[1-9][0-9]*")) {
            return false;
        }
        return new BigInteger(id).compareTo(LONG_MAX) <= 0;
    }

    private static void collect(Object value, List<JsonObject> objects) {
        switch (value) {
            case JsonObject object -> {
                objects.add(object);
                object.fieldNames().stream().sorted().forEach(name -> collect(object.getValue(name), objects));
            }
            case JsonArray array -> array.forEach(item -> collect(item, objects));
            case null, default -> {
            }
        }
    }
}
