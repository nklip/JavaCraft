package dev.nklip.javacraft.shardshop.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonRequestsTest {

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "APPLICATION/JSON", " application/json ", "application/json;charset=utf-8",
            "application/json ; charset=\"utf-8\"", "application/json;a=b;c=\"d\\\"e\""})
    void acceptsOneJsonMediaTypeWithParameters(String contentType) {
        JsonRequests.requireJsonMediaType(List.of(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text/plain", "application/*", "*/*", "application/json+x", "application/jsonx",
            "application/json;", "application/json;charset", "application/json;charset=\"open",
            "application/json,text/plain", "application/json "})
    void rejectsOtherMediaTypes(String contentType) {
        assertThrows(IllegalArgumentException.class, () -> JsonRequests.requireJsonMediaType(List.of(contentType)));
    }

    @Test
    void rejectsMissingOrRepeatedContentTypes() {
        assertThrows(IllegalArgumentException.class, () -> JsonRequests.requireJsonMediaType(null));
        assertThrows(IllegalArgumentException.class, () -> JsonRequests.requireJsonMediaType(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> JsonRequests.requireJsonMediaType(List.of("application/json", "application/json")));
    }

    @Test
    void parsesExactNumbersAndUnicodeText() {
        var node = JsonRequests.parse(bytes("{\"quantity\":2.000000000000000000001,\"name\":\"\\ud83d\\uded2\"}"));
        assertEquals(new BigDecimal("2.000000000000000000001"), node.get("quantity").decimalValue());
        assertEquals("🛒", node.get("name").textValue());
    }

    @Test
    void rejectsMalformedDocumentsWithoutDisclosingThem() {
        for (byte[] body : List.of(bytes("{\"a\":1,\"a\":1}"), bytes("{} {}"), bytes("{\"a\":"),
                new byte[]{'"', (byte) 0xC3, (byte) 0x28, '"'}, bytes("{\"secret\":tru}"))) {
            assertEquals("Invalid JSON body",
                    assertThrows(IllegalArgumentException.class, () -> JsonRequests.parse(body)).getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> JsonRequests.parse(null));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
