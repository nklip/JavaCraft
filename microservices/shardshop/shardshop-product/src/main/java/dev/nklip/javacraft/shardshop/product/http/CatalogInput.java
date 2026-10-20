package dev.nklip.javacraft.shardshop.product.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ProductCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.SellerCreation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Set;

/** Strict wire validation performed before calling the catalog service. */
final class CatalogInput {
    private static final String MONEY = "(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}";

    SellerCreation seller(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "companyName", "region");
            return new SellerCreation(JsonFields.nonblankText(node, "companyName", 200),
                    region(JsonFields.text(node, "region")));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    ProductCreation product(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "name", "description", "price", "unitCost", "currency", "initialStock");
            return new ProductCreation(JsonFields.nonblankText(node, "name", 200),
                    JsonFields.nonblankText(node, "description", 2000),
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

    ListingQuery listingQuery(UriInfo uri) {
        MultivaluedMap<String, String> query = uri.getQueryParameters();
        if (!Set.of("region", "limit", "after").containsAll(query.keySet())) {
            throw invalid();
        }
        String requestedRegion = region(singleQuery(query, "region", true));
        String limitText = singleQuery(query, "limit", false);
        int limit = 25;
        if (limitText != null) {
            if (!limitText.matches("[1-9][0-9]{0,2}")) {
                throw invalid();
            }
            limit = Integer.parseInt(limitText);
            if (limit > 100) {
                throw invalid();
            }
        }
        String afterText = singleQuery(query, "after", false);
        return new ListingQuery(requestedRegion, limit, afterText == null ? 0 : id(afterText));
    }

    private String singleQuery(MultivaluedMap<String, String> query, String name, boolean required) {
        List<String> values = query.get(name);
        if (values == null && !required) {
            return null;
        }
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw invalid();
        }
        return values.getFirst();
    }

    record ListingQuery(String region, int limit, long after) {
    }

    private String region(String value) {
        if (!"US".equals(value) && !"EU".equals(value) && !"ASIA".equals(value)) {
            throw invalid();
        }
        return value;
    }

    private JsonNode document(byte[] body, HttpHeaders headers) {
        try {
            JsonRequests.requireJsonMediaType(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE));
            MediaType mediaType = headers.getMediaType();
            if (mediaType == null || !MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType)
                    || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
                throw invalid();
            }
            return JsonRequests.parse(body);
        } catch (BadRequestException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    void requireJsonMediaType(List<String> types) {
        try {
            JsonRequests.requireJsonMediaType(types);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private CatalogFailure invalid() {
        return new CatalogFailure(CatalogFailure.Reason.INVALID_REQUEST);
    }
}
