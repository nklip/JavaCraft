package dev.nklip.javacraft.shardshop.common;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strict decoding of JSON request bodies before a provider builds typed values. Exceptions never contain body or
 * header data.
 */
public final class JsonRequests {
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final String QUOTED = "\"(?:[\\t\\x20\\x21\\x23-\\x5B\\x5D-\\x7E\\x80-\\xFF]"
            + "|\\\\[\\t\\x20-\\x7E\\x80-\\xFF])*\"";
    private static final Pattern MEDIA_TYPE = Pattern.compile("[\\t ]*" + TOKEN + "/" + TOKEN
            + "(?:[\\t ]*;[\\t ]*" + TOKEN + "=(?:" + TOKEN + "|" + QUOTED + "))*[\\t ]*");

    private JsonRequests() {
    }

    /** Accepts exactly one Content-Type field value: {@code application/json} with optional RFC 9110 parameters. */
    public static void requireJsonMediaType(List<String> contentTypes) {
        if (contentTypes == null || contentTypes.size() != 1 || !MEDIA_TYPE.matcher(contentTypes.getFirst()).matches()
                || !contentTypes.getFirst().split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new IllegalArgumentException("Expected one application/json media type");
        }
    }

    /** Strict UTF-8 JSON with unique member names and no trailing tokens; numbers keep their exact value. */
    public static JsonNode parse(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("Missing JSON body");
        }
        try {
            return StrictJson.read(body);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }
}
