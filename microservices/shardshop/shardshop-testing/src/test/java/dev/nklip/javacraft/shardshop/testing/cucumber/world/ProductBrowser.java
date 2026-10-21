package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the real HTTP listing, including exact decimal strings and IDs safe from JSON number rounding. */
public final class ProductBrowser {
    private static final Set<String> FIELDS = Set.of("sellerId", "productId", "sellerName", "name", "region",
            "initialStock", "stock", "price", "currency", "profitsEarned", "countryCode", "newOrderEligible", "legacyReason");
    private final CatalogApi api;

    public ProductBrowser(CatalogApi api) {
        this.api = api;
    }

    public JsonNode page(String query) throws IOException {
        var response = api.get("/api/v1/products?" + query);
        assertEquals(200, response.status(), response.text());
        JsonNode page = Json.parse(response.body());
        assertEquals(Set.of("region", "items", "nextAfter"), fields(page));
        assertTrue(page.path("items").isArray());
        assertTrue(page.path("nextAfter").isNull() || canonicalId(page.path("nextAfter")));
        long previous = 0;
        for (JsonNode row : page.path("items")) {
            assertEquals(FIELDS, fields(row));
            assertTrue(canonicalId(row.path("sellerId")));
            assertTrue(canonicalId(row.path("productId")));
            long id = Long.parseLong(row.path("productId").asText());
            assertTrue(id > previous, "Products must be sorted by numeric product ID");
            previous = id;
            assertEquals(page.path("region"), row.path("region"));
            assertTrue(row.path("initialStock").isIntegralNumber());
            assertTrue(row.path("stock").isIntegralNumber());
            assertTrue(row.path("stock").asLong() >= 0);
            assertTrue(row.path("stock").asLong() <= row.path("initialStock").asLong());
            assertTrue(row.path("price").isTextual());
            assertTrue(row.path("price").asText().matches("(?:0|[1-9][0-9]*)\\.[0-9]{2}"));
            assertEquals(Set.of(row.path("currency").asText()), fields(row.path("profitsEarned")));
            for (JsonNode profit : row.path("profitsEarned")) {
                assertTrue(profit.isTextual());
                assertTrue(profit.asText().matches("-?(?:0|[1-9][0-9]*)\\.[0-9]{2}"));
            }
        }
        if (!page.path("nextAfter").isNull()) {
            assertFalse(page.path("items").isEmpty());
            assertEquals(page.path("items").get(page.path("items").size() - 1).path("productId"), page.path("nextAfter"));
        }
        return page;
    }

    public void assertProduct(CatalogApi.Product product, int stock, Map<String, String> profits) throws IOException {
        long before = Long.parseLong(product.id()) - 1;
        JsonNode page = page("region=" + product.seller().region() + "&limit=1"
                + (before == 0 ? "" : "&after=" + before));
        JsonNode found = null;
        for (JsonNode row : page.path("items")) {
            if (row.path("productId").asText().equals(product.id())) found = row;
        }
        assertNotNull(found, "The browser omitted product " + product.id());
        assertEquals(product.seller().id(), found.path("sellerId").asText());
        assertEquals(product.seller().companyName(), found.path("sellerName").asText());
        assertEquals(product.request().path("name"), found.path("name"));
        assertEquals(product.request().path("initialStock"), found.path("initialStock"));
        assertEquals(stock, found.path("stock").asInt());
        assertEquals(product.request().path("price"), found.path("price"));
        assertEquals(product.request().path("currency"), found.path("currency"));
        assertEquals(profits.keySet(), fields(found.path("profitsEarned")));
        for (var profit : profits.entrySet()) {
            assertEquals(profit.getValue(), found.path("profitsEarned").path(profit.getKey()).asText());
        }
    }

    public static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static boolean canonicalId(JsonNode node) {
        return node.isTextual() && node.asText().matches("[1-9][0-9]{0,18}")
                && new java.math.BigInteger(node.asText()).bitLength() <= 63;
    }
}
