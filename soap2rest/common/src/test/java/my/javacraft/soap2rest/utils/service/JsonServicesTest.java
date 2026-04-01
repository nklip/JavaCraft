package my.javacraft.soap2rest.utils.service;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class JsonServicesTest {

    @Test
    void testObjectToJsonAndJsonToObjectHandlePayload() throws IOException {
        SamplePayload payload = new SamplePayload();
        payload.name = "alpha";
        payload.count = 3;
        payload.nullable = null;
        payload.emptyList = List.of();

        String json = JsonServices.objectToJson(payload);

        Assertions.assertTrue(json.contains("\"name\""));
        Assertions.assertTrue(json.contains("\"alpha\""));
        Assertions.assertTrue(json.contains("\"count\""));
        Assertions.assertFalse(json.contains("nullable"));
        Assertions.assertFalse(json.contains("emptyList"));

        SamplePayload restored = JsonServices.jsonToObject(json, SamplePayload.class);
        Assertions.assertEquals("alpha", restored.name);
        Assertions.assertEquals(3, restored.count);
        Assertions.assertNull(restored.nullable);
        Assertions.assertNull(restored.emptyList);
    }

    @Test
    void testJsonToObjectThrowsForInvalidJson() {
        Assertions.assertThrows(
                IOException.class,
                () -> JsonServices.jsonToObject("{not-valid-json}", SamplePayload.class)
        );
    }

    @Test
    void testIsJsonHandlesValidInvalidAndNonObjectInput() {
        Assertions.assertTrue(JsonServices.isJson("{\"id\": 1}"));
        Assertions.assertFalse(JsonServices.isJson("plain-text"));
        Assertions.assertFalse(JsonServices.isJson("{not-valid-json}"));
    }

    static final class SamplePayload {
        public String name;
        public String nullable;
        public List<String> emptyList;
        public int count;
    }
}
