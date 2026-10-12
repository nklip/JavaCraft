package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Strict JSON for request bodies and response checks: exact decimals, no duplicate members, no trailing data. */
public final class Json {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private Json() {
    }

    public static JsonNode parse(byte[] document) {
        try {
            return MAPPER.readTree(document);
        } catch (IOException failure) {
            throw new AssertionError("The body is not one valid JSON document: "
                    + new String(document, StandardCharsets.UTF_8), failure);
        }
    }

    public static ObjectNode object(String document) {
        JsonNode node = parse(document.getBytes(StandardCharsets.UTF_8));
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Not a JSON object: " + document);
        }
        return object;
    }

    public static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    public static byte[] bytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (JsonProcessingException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
