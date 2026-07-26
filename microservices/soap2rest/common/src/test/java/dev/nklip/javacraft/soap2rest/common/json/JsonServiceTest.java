package dev.nklip.javacraft.soap2rest.common.json;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class JsonServiceTest {

    @Test
    void testObjectToJsonAndJsonToObjectHandlePayload() {
        SamplePayload payload = new SamplePayload();
        payload.name = "alpha";
        payload.count = 3;
        payload.nullable = null;
        payload.emptyList = List.of();

        String json = JsonService.objectToJson(payload);

        Assertions.assertTrue(json.contains("\"name\""));
        Assertions.assertTrue(json.contains("\"alpha\""));
        Assertions.assertTrue(json.contains("\"count\""));
        Assertions.assertFalse(json.contains("nullable"));
        Assertions.assertFalse(json.contains("emptyList"));

        SamplePayload restored = JsonService.jsonToObject(json, SamplePayload.class);
        Assertions.assertEquals("alpha", restored.name);
        Assertions.assertEquals(3, restored.count);
        Assertions.assertNull(restored.nullable);
        Assertions.assertNull(restored.emptyList);
    }

    @Test
    void testJsonToObjectThrowsForInvalidJson() {
        // Jackson 3 rooted its exception hierarchy at the unchecked JacksonException;
        // in Jackson 2 the equivalent JsonProcessingException extended IOException.
        Assertions.assertThrows(
                JacksonException.class,
                () -> JsonService.jsonToObject("{not-valid-json}", SamplePayload.class)
        );
    }

    @Test
    void testIsJsonHandlesValidInvalidAndNonObjectInput() {
        Assertions.assertTrue(JsonService.isJson("{\"id\": 1}"));
        Assertions.assertFalse(JsonService.isJson("plain-text"));
        Assertions.assertFalse(JsonService.isJson("{not-valid-json}"));
    }

    static final class SamplePayload {
        public String name;
        public String nullable;
        public List<String> emptyList;
        public int count;
    }
}
