package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonResponsesTest {

    @Test
    void decodesBodiesOfAnyTransportWithTheSameStrictRules() throws Exception {
        assertEquals("42", JsonResponses.decode(200, bytes("{\"id\":\"42\"}"), 200, node -> JsonFields.id(node, "id")));
        assertEquals("{}", JsonResponses.decode(201, bytes("{}"), 201, JsonNode::toString));
        assertThrows(JsonProcessingException.class,
                () -> JsonResponses.decode(200, bytes("{\"id\":\"1\",\"id\":\"1\"}"), 200, JsonNode::toString));
        assertThrows(CharacterCodingException.class,
                () -> JsonResponses.decode(200, new byte[]{(byte) 0xC3, (byte) 0x28}, 200, JsonNode::toString));
        assertThrows(NullPointerException.class, () -> JsonResponses.decode(200, bytes("{}"), 200, null));
    }

    @Test
    void rejectsOtherStatusesWithOnlyTheBoundedErrorCode() {
        HttpResponseException rejected = assertThrows(HttpResponseException.class, () -> JsonResponses.decode(503,
                bytes("{\"code\":\"READ_REPLICA_UNAVAILABLE\",\"message\":\"text\"}"), 200, JsonNode::toString));
        assertEquals(503, rejected.statusCode());
        assertEquals(Optional.of("READ_REPLICA_UNAVAILABLE"), rejected.errorCode());
        assertEquals(Optional.empty(), assertThrows(HttpResponseException.class,
                () -> JsonResponses.decode(404, null, 200, JsonNode::toString)).errorCode());
        assertEquals(201, assertThrows(HttpResponseException.class,
                () -> JsonResponses.decode(201, bytes("{}"), 200, JsonNode::toString)).statusCode());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
