package dev.nklip.javacraft.soap2rest.common.json;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JsonServiceTest {

    @Test
    void testObjectToJsonAndJsonToObjectHandlePayload() throws IOException {
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
        Assertions.assertThrows(
                IOException.class,
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
