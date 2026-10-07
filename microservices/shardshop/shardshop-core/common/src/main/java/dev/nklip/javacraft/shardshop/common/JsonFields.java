package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Strict field readers for externally supplied JSON; messages never contain payload data. */
public final class JsonFields {
    private JsonFields() {
    }

    public static void requireFields(JsonNode node, String... names) {
        Set<String> expected = Set.copyOf(Arrays.asList(names));
        if (node == null || !node.isObject() || node.size() != expected.size()
                || !expected.stream().allMatch(node::has)) {
            throw new IllegalArgumentException("Unexpected JSON object fields");
        }
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Expected a JSON string");
        }
        return value.textValue();
    }

    public static String matchingText(JsonNode node, String field, String pattern) {
        String value = text(node, field);
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException("Invalid JSON string");
        }
        return value;
    }

    public static Map<String, String> signedMoneyMap(JsonNode node, String field, String... requiredCurrencies) {
        JsonNode amounts = node.get(field);
        if (amounts == null || !amounts.isObject() || !Arrays.stream(requiredCurrencies).allMatch(amounts::has)) {
            throw new IllegalArgumentException("Invalid monetary object fields");
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : amounts.properties()) {
            String currency = entry.getKey();
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("Invalid currency key");
            }
            result.put(currency, matchingText(amounts, currency,
                    "(?!-0\\.00$)-?(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}"));
        }
        return Map.copyOf(result);
    }

    public static String id(JsonNode node, String field) {
        String value = text(node, field);
        new IdParser().parse(value);
        return value;
    }

    public static int nonnegativeInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Expected a JSON integer");
        }
        final int integer;
        try {
            integer = value.decimalValue().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid JSON integer");
        }
        if (integer < 0) {
            throw new IllegalArgumentException("Invalid JSON integer");
        }
        return integer;
    }
}
