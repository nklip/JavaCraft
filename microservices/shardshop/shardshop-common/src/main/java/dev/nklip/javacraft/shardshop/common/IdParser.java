package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.regex.Pattern;

/** Validates ShardShop wire IDs before conversion, routing or IO. */
public final class IdParser {

    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final String INVALID_ID = "ID must be a canonical decimal string in 1..9223372036854775807";

    public long parse(String text) {
        if (text == null || !CANONICAL_ID.matcher(text).matches()) {
            throw new IllegalArgumentException(INVALID_ID);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INVALID_ID);
        }
    }

    /**
     * Reads an ID from the current JSON value token without advancing the parser.
     * Call before binding to a typed DTO so numeric tokens cannot be coerced to strings.
     * The caller owns the parser and the surrounding document validation.
     * Malformed string content is an invalid ID; stream IO failures propagate unchanged.
     */
    public long parseJson(JsonParser json) throws IOException {
        if (json == null || json.currentToken() != JsonToken.VALUE_STRING) {
            throw new IllegalArgumentException(INVALID_ID);
        }
        try {
            return parse(json.getText());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(INVALID_ID);
        }
    }
}
