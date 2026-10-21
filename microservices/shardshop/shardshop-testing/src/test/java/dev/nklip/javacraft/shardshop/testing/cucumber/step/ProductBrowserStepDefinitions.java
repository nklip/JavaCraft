package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductBrowser;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Regional browser acceptance through the packaged product HTTP stack and real migrated shards. */
public final class ProductBrowserStepDefinitions {
    private final CatalogShards shards;
    private final ProductRuntime product;
    private final CatalogApi api;
    private final ProductBrowser browser;

    public ProductBrowserStepDefinitions(CatalogShards shards, ProductRuntime product, CatalogApi api,
                                         ProductBrowser browser) {
        this.shards = shards;
        this.product = product;
        this.api = api;
        this.browser = browser;
    }

    @Given("arbitrary browser products occupy both US shards and the other regions")
    public void fixtures() throws Exception {
        for (var shard : shards.all()) {
            String key = "browser-" + shard.name();
            for (int candidate = 0; !shards.ofKey(shard.region(), key).equals(shard); candidate++) {
                assertTrue(candidate < 1000, "Could not select seller's shard");
                key = "browser-" + shard.name() + "-" + candidate;
            }
            ObjectNode request = CatalogApi.sellerRequest("Arbitrary <company> & " + shard.name(), shard.region());
            var response = api.createSeller(key, request);
            assertEquals(201, response.status(), response.text());
            var seller = api.remember(shard.name(), key, request, response);
            assertEquals(shard, shards.ofSeller(seller.id()));
        }
        // Interleave creation across shards so shard inventory order cannot accidentally satisfy global ID sorting.
        for (int index = 0; index < 52; index++) {
            for (var shard : shards.all()) {
                if (!shard.region().equals("US") && index >= 2) continue;
                var seller = api.seller(shard.name());
                ObjectNode payload = CatalogApi.itemRequest(shard.region())
                        .put("name", "Unseeded <item> & " + shard.name() + " " + index)

                        .put("initialStock", index == 0 ? 0 : 100 + index)
                        .put("price", index == 3 ? "99999999999999999.99" : "19.95");
                var created = api.post(seller.path() + "/products", "browser-item-" + index, Json.bytes(payload));
                assertEquals(201, created.status(), created.text());
                api.remember(shard.name() + "-" + index, seller, "browser-item-" + index, payload, created);
            }
        }
        // Independent stored facts: same seller, different products/currencies and multiple order credits.
        credit(usProduct(0), 1, "0.10");
        credit(usProduct(0), 2, "0.20");
        credit(usProduct(1), 3, "-7.45");
        credit(usProduct(2), 4, "0.00");
        credit(usProduct(3), 5, "99999999999999999.99");
        credit(usProduct(3), 6, "0.02");
    }

    @Then("the regional browser paginates every stored product once with exact product earnings")
    public void pagesMatchStoredTruth() throws Exception {
        browser.assertProduct(usProduct(0), 0, Map.of("USD", "0.30"));
        browser.assertProduct(usProduct(1), 101, Map.of("USD", "-7.45"));
        browser.assertProduct(usProduct(2), 102, Map.of("USD", "0.00"));
        browser.assertProduct(usProduct(3), 103, Map.of("USD", "100000000000000000.01"));
        browser.assertProduct(usProduct(4), 104, Map.of("USD", "0.00"));
        for (String region : List.of("US", "EU", "ASIA")) {
            List<JsonNode> expected = stored(region);
            List<JsonNode> seen = new ArrayList<>();
            String after = null;
            for (int pageCount = 0; ; pageCount++) {
                assertTrue(pageCount < 30, "Cursor did not finish");
                JsonNode page = browser.page("region=" + region + "&limit=7" + (after == null ? "" : "&after=" + after));
                assertEquals(region, page.path("region").asText());
                List<JsonNode> items = new ArrayList<>();
                page.path("items").forEach(items::add);
                int end = Math.min(seen.size() + 7, expected.size());
                assertEquals(expected.subList(seen.size(), end), items, "Stored catalog page differs in " + region);
                seen.addAll(items);
                assertEquals(seen.size() < expected.size(), !page.path("nextAfter").isNull(),
                        "nextAfter must exist exactly when another product exists");
                if (page.path("nextAfter").isNull()) break;
                after = page.path("nextAfter").asText();
            }
            assertEquals(expected, seen, "Keyset traversal must contain no omissions or duplicates");
        }
        JsonNode defaults = browser.page("region=US");
        assertEquals(25, defaults.path("items").size());
        assertEquals(stored("US").subList(0, 25), rows(defaults));
        JsonNode maximum = browser.page("region=US&limit=100");
        assertEquals(100, maximum.path("items").size());
        assertEquals(stored("US").subList(0, 100), rows(maximum));
        JsonNode last = browser.page("region=US&limit=100&after=" + maximum.path("nextAfter").asText());
        assertEquals(4, last.path("items").size());
        assertTrue(last.path("nextAfter").isNull());
        JsonNode pastEnd = browser.page("region=US&after=9223372036854775807&limit=1");
        assertTrue(pastEnd.path("items").isEmpty());
        assertTrue(pastEnd.path("nextAfter").isNull());
        JsonNode exactEnd = browser.page("region=EU&limit=2");
        assertEquals(2, exactEnd.path("items").size());
        assertTrue(exactEnd.path("nextAfter").isNull(), "A full final page has no next cursor");
        JsonNode between = browser.page("region=US&limit=1&after=1");
        assertEquals(stored("US").getFirst(), between.path("items").get(0), "Cursor need not identify a product");
    }

    @Then("the regional browser rejects malformed filters and canonical ID violations")
    public void invalidQueries() throws Exception {
        for (String query : List.of("", "region=", "region=us", "region=AFRICA", "region=US&region=US",
                "region=US&unknown=1", "region=US&limit=", "region=US&limit=0", "region=US&limit=-1",
                "region=US&limit=101", "region=US&limit=1.0", "region=US&limit=2147483648",
                "region=US&limit=1&limit=2", "region=US&after=1&after=2", "region=US&after=",
                "region=US&after=0", "region=US&after=01", "region=US&after=-1", "region=US&after=%2B1",
                "region=US&after=1.0", "region=US&after=1e0", "region=US&after=%201", "region=US&after=1%0A",
                "region=US&after=1%0D%0A", "region=US&after=1%09", "region=US&after=9223372036854775808")) {
            var response = api.get("/api/v1/products" + (query.isEmpty() ? "" : "?" + query));
            CatalogApi.assertError(response, 400, "INVALID_REQUEST");
        }
        assertEquals(108, stored("US").size() + stored("EU").size() + stored("ASIA").size(),
                "Rejected reads must preserve the catalog");
    }

    @Then("an empty region returns an empty terminal browser page")
    public void emptyRegion() throws Exception {
        JsonNode page = browser.page("region=EU&limit=1");
        assertEquals("EU", page.path("region").asText());
        assertTrue(page.path("items").isEmpty());
        assertTrue(page.path("nextAfter").isNull());
    }

    @Then("one unavailable US shard fails the complete regional page while EU remains available")
    public void unavailableShard() throws Exception {
        product.restart(ProductRuntime.Endpoints.REFUSED_SECOND_US_PRIMARY);
        var response = api.get("/api/v1/products?region=US&limit=1");
        CatalogApi.assertError(response, 503, "CATALOG_UNAVAILABLE");
        assertTrue(response.elapsed().compareTo(Duration.ofSeconds(4)) < 0, response.elapsed().toString());
        assertFalse(Json.parse(response.body()).has("items"), "No partial regional success is allowed");
        assertEquals(stored("EU"), rows(browser.page("region=EU&limit=100")));
        product.restart(ProductRuntime.Endpoints.PRIMARIES);
        assertEquals(stored("US").subList(0, 25), rows(browser.page("region=US")));
    }

    @Then("unavailable replicas fail browser reads without falling back to healthy primaries")
    public void unavailableReplicas() throws Exception {
        product.restart(ProductRuntime.Endpoints.REFUSED_REPLICAS);
        var response = api.get("/api/v1/products?region=US&limit=1");
        CatalogApi.assertError(response, 503, "READ_REPLICA_UNAVAILABLE");
        assertTrue(response.elapsed().compareTo(Duration.ofSeconds(4)) < 0, response.elapsed().toString());
        assertFalse(Json.parse(response.body()).has("items"));
        assertEquals(104, stored("US").size(), "Healthy primaries still hold the requested products");
        product.restart(ProductRuntime.Endpoints.PRIMARIES);
        assertEquals(stored("US").subList(0, 25), rows(browser.page("region=US")));
    }

    @Then("the packaged product serves the browser page and its local script and stylesheet")
    public void packagedResources() throws Exception {
        var response = raw("/products/");
        assertEquals(200, response.status(), response.text());
        assertTrue(response.header("content-type").orElse("").contains("text/html"));
        assertEquals("no-store", response.header("cache-control").orElse(""));
        var matcher = Pattern.compile("(?:src|href)=[\"']([^\"']+\\.(?:js|css)(?:\\?[^\"']*)?)[\"']")
                .matcher(response.text());
        int scripts = 0;
        int styles = 0;
        while (matcher.find()) {
            String target = matcher.group(1);
            assertFalse(target.contains(":"), "Browser assets must be packaged locally");
            String path = target.startsWith("/") ? target : "/products/" + target;
            var asset = raw(path);
            assertEquals(200, asset.status(), path + ": " + asset.text());
            assertFalse(asset.text().isBlank());
            assertEquals("no-store", asset.header("cache-control").orElse(""), path);
            String type = asset.header("content-type").orElse("");
            if (java.net.URI.create(target).getPath().endsWith(".js")) {
                assertTrue(type.contains("javascript"), type);
                scripts++;
            } else {
                assertTrue(type.contains("text/css"), type);
                styles++;
            }
        }
        assertTrue(scripts > 0, "The packaged page must load its JavaScript");
        assertTrue(styles > 0, "The packaged page must load its stylesheet");
    }

    private CatalogApi.Product usProduct(int index) {
        return api.product("shard-a-" + index);
    }

    private RawHttp.Response raw(String path) throws Exception {
        return RawHttp.exchange(product.port(), "GET", path, List.of(), new byte[0], RawHttp.Framing.NONE);
    }

    private void credit(CatalogApi.Product fixture, long orderId, String amount) throws Exception {
        try (Connection connection = shards.superuser(shards.ofSeller(fixture.seller().id()));
             PreparedStatement insert = connection.prepareStatement("INSERT INTO catalog.profit_credits "
                     + "(order_id, product_id, seller_id, currency, amount) VALUES (?, ?, ?, ?, ?::numeric)")) {
            insert.setLong(1, orderId);
            insert.setLong(2, Long.parseLong(fixture.id()));
            insert.setLong(3, Long.parseLong(fixture.seller().id()));
            insert.setString(4, fixture.request().path("currency").asText());
            insert.setString(5, amount);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private List<JsonNode> stored(String region) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        for (var shard : shards.all()) {
            try (Connection connection = shards.superuser(shard);
                 PreparedStatement query = connection.prepareStatement("SELECT p.*, s.company_name, s.region, s.country_code, "
                         + "COALESCE((SELECT sum(c.amount) FROM catalog.profit_credits c "
                         + "WHERE c.product_id=p.product_id), 0.00) AS profit "
                         + "FROM catalog.products p JOIN catalog.sellers s ON s.seller_id=p.seller_id WHERE s.region=?")) {
                query.setString(1, region);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        ObjectNode row = Json.newObject().put("sellerId", result.getString("seller_id"))
                                .put("productId", result.getString("product_id"))
                                .put("sellerName", result.getString("company_name")).put("name", result.getString("name"))
                                .put("region", result.getString("region")).put("countryCode", result.getString("country_code")).put("initialStock", result.getInt("initial_stock"))
                                .put("stock", result.getInt("stock")).put("price", result.getBigDecimal("price").toPlainString())
                                .put("currency", result.getString("currency")).put("newOrderEligible", true).putNull("legacyReason");
                        row.putObject("profitsEarned").put(result.getString("currency"),
                                result.getBigDecimal("profit").setScale(2).toPlainString());
                        rows.add(row);
                    }
                }
            }
        }
        rows.sort(Comparator.comparingLong(row -> Long.parseLong(row.path("productId").asText())));
        return rows;
    }

    private static List<JsonNode> rows(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.path("items").forEach(rows::add);
        return rows;
    }
}
