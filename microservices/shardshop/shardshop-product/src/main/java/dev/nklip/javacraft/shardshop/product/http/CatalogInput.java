package dev.nklip.javacraft.shardshop.product.http;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ProductCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.SellerCreation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** Strict wire validation performed before calling the catalog service. */
final class CatalogInput {
    private static final String MONEY = "(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}";
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final String QUOTED = "\"(?:[\\t\\x20\\x21\\x23-\\x5B\\x5D-\\x7E\\x80-\\xFF]"
            + "|\\\\[\\t\\x20-\\x7E\\x80-\\xFF])*\"";
    private static final Pattern MEDIA_TYPE = Pattern.compile("[\\t ]*" + TOKEN + "/" + TOKEN
            + "(?:[\\t ]*;[\\t ]*" + TOKEN + "=(?:" + TOKEN + "|" + QUOTED + "))*[\\t ]*");
    private static final ObjectReader JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build().readerFor(JsonNode.class);

    SellerCreation seller(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "companyName", "region");
            return new SellerCreation(text(node, "companyName", 200),
                    region(JsonFields.text(node, "region")));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    ProductCreation product(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "name", "description", "price", "unitCost", "currency", "initialStock");
            return new ProductCreation(text(node, "name", 200), text(node, "description", 2000),
                    JsonFields.matchingText(node, "price", MONEY),
                    JsonFields.matchingText(node, "unitCost", MONEY),
                    JsonFields.matchingText(node, "currency", "[A-Z]{3}"),
                    JsonFields.nonnegativeInteger(node, "initialStock"));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    long id(String text) {
        try {
            return new IdParser().parse(text);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    String key(String text) {
        if (text == null || !text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw invalid();
        }
        return text;
    }

    String headerKey(HttpHeaders headers) {
        List<String> values = headers.getRequestHeader("Idempotency-Key");
        if (values == null || values.size() != 1) {
            throw invalid();
        }
        return key(values.getFirst());
    }

    void noQuery(UriInfo uri) {
        if (!uri.getQueryParameters().isEmpty()) {
            throw invalid();
        }
    }

    String regionQuery(UriInfo uri) {
        MultivaluedMap<String, String> query = uri.getQueryParameters();
        List<String> regions = query.get("region");
        if (query.size() != 1 || regions == null || regions.size() != 1) {
            throw invalid();
        }
        return region(regions.getFirst());
    }

    private String region(String value) {
        if (!"US".equals(value) && !"EU".equals(value) && !"ASIA".equals(value)) {
            throw invalid();
        }
        return value;
    }

    private JsonNode document(byte[] body, HttpHeaders headers) {
        try {
            requireJsonMediaType(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE));
            MediaType mediaType = headers.getMediaType();
            if (body == null || mediaType == null || !MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType)
                    || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
                throw invalid();
            }
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            return JSON.readValue(text);
        } catch (IOException | BadRequestException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    void requireJsonMediaType(List<String> types) {
        if (types == null || types.size() != 1 || !MEDIA_TYPE.matcher(types.getFirst()).matches()
                || !types.getFirst().split(";", 2)[0].strip().equalsIgnoreCase(MediaType.APPLICATION_JSON)) {
            throw invalid();
        }
    }

    private String text(JsonNode node, String field, int maximumLength) {
        String value = JsonFields.text(node, field);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maximumLength) {
            throw invalid();
        }
        boolean nonblank = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0 || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw invalid();
            }
            nonblank |= !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) && codePoint != 0x85;
            offset += Character.charCount(codePoint);
        }
        if (!nonblank) {
            throw invalid();
        }
        return value;
    }

    private CatalogFailure invalid() {
        return new CatalogFailure(CatalogFailure.Reason.INVALID_REQUEST);
    }
}
