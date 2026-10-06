package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdParserTest {

    private final IdParser ids = new IdParser();
    private final JsonFactory jsonFactory = new JsonFactory();

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "9, 9",
            "10, 10",
            "9007199254740991, 9007199254740991",
            "9007199254740992, 9007199254740992",
            "9007199254740993, 9007199254740993",
            "880803840000004605, 880803840000004605",
            "9223372036854775806, 9223372036854775806",
            "9223372036854775807, 9223372036854775807"
    })
    void acceptsExactCanonicalIdsInPathsAndJson(String text, long expected) throws IOException {
        assertEquals(expected, ids.parse(text));
        try (JsonParser json = jsonFactory.createParser(jsonString(text))) {
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());

            assertEquals(expected, ids.parseJson(json));
            assertEquals(JsonToken.VALUE_STRING, json.currentToken());
            assertNull(json.nextToken());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidText")
    void rejectsNoncanonicalTextAndOverflow(String text) throws IOException {
        assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parse(text)));
        try (JsonParser json = jsonFactory.createParser(jsonString(text))) {
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());

            assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(json)));
            assertEquals(JsonToken.VALUE_STRING, json.currentToken());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\t\"", "\"1\n\"", "\"\\x31\"", "\"1"})
    void rejectsMalformedJsonStringsWithTheFixedMessage(String document) throws IOException {
        try (JsonParser json = jsonFactory.createParser(document)) {
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());

            assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(json)));
        }
    }

    @Test
    void propagatesStreamFailures() throws IOException {
        StringReader input = new StringReader("\"");
        try (JsonParser json = jsonFactory.createParser(input)) {
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());
            input.close();

            IOException exception = assertThrows(IOException.class, () -> ids.parseJson(json));

            assertEquals(IOException.class, exception.getClass());
            assertEquals("Stream closed", exception.getMessage());
        }
    }

    @Test
    void rejectsMissingInput() {
        assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parse(null)));
        assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0", "1", "-1", "9007199254740993", "9223372036854775807",
            "9223372036854775808", "18446744073709551616", "1.0", "1e0", "1e999",
            "null", "true", "false", "[]", "{}", "[\"1\"]", "{\"id\":\"1\"}"
    })
    void rejectsNonStringJsonTokensWithoutConversion(String document) throws IOException {
        try (JsonParser json = jsonFactory.createParser(document)) {
            JsonToken token = json.nextToken();
            assertEquals(token, json.currentToken());

            assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(json)));
            assertEquals(token, json.currentToken());
        }
    }

    @Test
    void rejectsAnUnpositionedOrExhaustedJsonParser() throws IOException {
        try (JsonParser json = jsonFactory.createParser("\"1\"")) {
            assertNull(json.currentToken());
            assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(json)));
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());
            assertEquals(1L, ids.parseJson(json));
            assertNull(json.nextToken());
            assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(json)));
        }
    }

    @Test
    void readsOnlyTheCurrentFieldValue() throws IOException {
        try (JsonParser json = jsonFactory.createParser("{\"id\":\"9007199254740993\",\"next\":\"1\"}")) {
            assertEquals(JsonToken.START_OBJECT, json.nextToken());
            assertEquals(JsonToken.FIELD_NAME, json.nextToken());
            assertEquals("id", json.currentName());
            assertInvalid(assertThrows(IllegalArgumentException.class, () -> ids.parseJson(json)));
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());

            assertEquals(9007199254740993L, ids.parseJson(json));

            assertEquals(JsonToken.FIELD_NAME, json.nextToken());
            assertEquals("next", json.currentName());
            assertEquals(JsonToken.VALUE_STRING, json.nextToken());
            assertEquals(1L, ids.parseJson(json));
            assertEquals(JsonToken.END_OBJECT, json.nextToken());
            assertNull(json.nextToken());
        }
    }

    private static Stream<String> invalidText() {
        return Stream.of(
                "", "0", "00", "01", "+1", "-1", "-0",
                " 1", "1 ", "\t1", "1\t", "1\n", "1\r\n", "\n1", "1\r", "1\n2",
                "1.0", "1.5", "1e0", "1E3", "0x1", "1_000",
                "9223372036854775808", "9999999999999999999", "10000000000000000000",
                "١", "１", "1١", "1１", "1\u00a0", "1\u2028", "1\u2029", "1\0", "null"
        );
    }

    private String jsonString(String text) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonGenerator json = jsonFactory.createGenerator(output)) {
            json.writeString(text);
        }
        return output.toString();
    }

    private static void assertInvalid(IllegalArgumentException exception) {
        assertEquals("ID must be a canonical decimal string in 1..9223372036854775807", exception.getMessage());
    }
}
