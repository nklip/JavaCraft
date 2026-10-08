package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;

/**
 * Strict decoding of JSON response bodies for any HTTP transport: strict UTF-8, no duplicate members and no
 * trailing tokens. Exceptions never contain body data.
 */
public final class JsonResponses {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private JsonResponses() {
    }

    /**
     * Decodes the body of a 200 or {@code acceptedStatus} response. Another status becomes an
     * {@link HttpResponseException} with the bounded error-code token of the body, if it has one.
     */
    public static <T> T decode(int status, byte[] body, int acceptedStatus, Function<JsonNode, T> decoder)
            throws IOException {
        Objects.requireNonNull(decoder);
        if (status != 200 && status != acceptedStatus) {
            throw new HttpResponseException(status, errorCode(body));
        }
        return Objects.requireNonNull(decoder.apply(readJson(body)));
    }

    private static String errorCode(byte[] body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode root = readJson(body);
            if (!root.isObject()) {
                return null;
            }
            JsonNode code = root.get("code");
            if (code == null || !code.isTextual()) {
                return null;
            }
            String value = code.textValue();
            return value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : null;
        } catch (IOException failure) {
            return null;
        }
    }

    private static JsonNode readJson(byte[] bytes) throws IOException {
        String body = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        return JSON.readTree(body);
    }
}
