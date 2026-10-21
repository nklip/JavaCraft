package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Uses service-created fixtures and exact saved timestamps, including sub-millisecond ties. */
public final class OrderListingStepDefinitions {
    private final CatalogShards shards;
    private final ProductRuntime product;
    private final CatalogApi catalog;
    private final OrderRuntime order;
    private final Map<String, List<JsonNode>> expected = new LinkedHashMap<>();
    private CatalogApi.Product selected;
    private final Map<String, String> productNames = new LinkedHashMap<>();
    private String otherProductId;

    public OrderListingStepDefinitions(CatalogShards shards, ProductRuntime product, CatalogApi catalog,
                                       OrderRuntime order) {
        this.shards = shards;
        this.product = product;
        this.catalog = catalog;
        this.order = order;
    }

    @Given("the packaged order browser uses four migrated regional shards")
    public void start() throws Exception {
        shards.create("shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US");
        for (var shard : shards.all()) {
            CatalogDatabase.instance().migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                    Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                            "shardCount", Integer.toString(shards.all().size())), "ordering");
        }
        product.start(ProductRuntime.Endpoints.PRIMARIES);
        order.start(Set.of());
        for (String region : List.of("US", "EU", "ASIA")) expected.put(region, new ArrayList<>());
    }

    @After("@order-listing")
    public void stop() throws Exception {
        order.stop();
        product.stop();
        assertEquals(List.of(), order.problems());
        assertEquals(List.of(), product.problems());
    }

    @Given("arbitrary orders with tied creation times occupy every regional shard")
    public void fixtures() throws Exception {
        selected = product("selected", "US", "USD", "Cedar Travel Kit");
        var other = product("other", "US", "USD", "Cedar 50%_ ' OR 1=1 --");
        otherProductId = other.id();
        var euros = product("euros", "EU", "EUR", "Euro Umbrella");
        Map<CatalogShards.Shard, String> buyers = new LinkedHashMap<>();
        Map<String, String> buyerNames = new LinkedHashMap<>();
        for (var shard : shards.all()) {
            String surname = List.of("Anderson", "Baker", "Clark", "Davis").get(shards.all().indexOf(shard));
            for (int candidate = 0; ; candidate++) {
                assertTrue(candidate < 100, "No buyer routed to " + shard.name());
                String buyer = body(exchange("POST", "/api/v1/buyers", shard.name() + "-" + candidate,
                        buyerRequest(shard.region(), surname)), 201).path("buyerId").asText();
                if (CatalogRouting.sellerShard(buyer, shards.all().size()) == shards.all().indexOf(shard)) {
                    buyers.put(shard, buyer);
                    buyerNames.put(buyer, "Alex " + surname);
                    break;
                }
            }
        }
        // Interleaving creation defeats accidental concatenation in shard inventory order.
        for (int index = 0; index < 28; index++) {
            for (var entry : buyers.entrySet()) {
                var shard = entry.getKey();
                if (!shard.region().equals("US") && index >= 3) continue;
                String buyer = entry.getValue();
                String orderId = body(exchange("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                        Json.newObject().put("runName", "regional-browser").put("requestOrdinal", Integer.toString(index))),
                        201).path("orderId").asText();
                String currency = index == 0 ? "EUR" : "USD";
                var request = Json.newObject().put("currency", currency);
                var items = request.putArray("items");
                if (index % 3 != 0) items.addObject().put("sellerId", selected.seller().id())
                        .put("productId", selected.id()).put("quantity", 2);
                var secondary = index == 0 ? euros : other;
                items.addObject().put("sellerId", secondary.seller().id()).put("productId", secondary.id()).put("quantity", 1);
                body(exchange("PUT", "/api/v1/buyers/" + buyer + "/orders/" + orderId, null, request), 202);
                Instant created = Instant.parse("2026-01-01T12:00:00.123456Z").plusSeconds(index / 3)
                        .plusNanos(index % 3 == 2 ? 1000 : 0);
                String status = switch (index % 4) {
                    case 0 -> "PENDING_STOCK";
                    case 1 -> "PENDING_LEDGER";
                    case 2 -> "CONFIRMED";
                    default -> "CANCELLED";
                };
                // Freeze fixture state; no coordinator is enabled in this provider.
                try (var connection = shards.superuser(shard)) {
                    try (var update = connection.prepareStatement(
                            "UPDATE ordering.orders SET created_at=?, status=? WHERE order_id=?")) {
                        update.setTimestamp(1, Timestamp.from(created));
                        update.setString(2, status);
                        update.setLong(3, Long.parseLong(orderId));
                        assertEquals(1, update.executeUpdate());
                    }
                    try (var update = connection.prepareStatement(
                            "UPDATE ordering.order_sagas SET status=?, cancellation_reason=? WHERE order_id=?")) {
                        update.setString(1, status);
                        update.setString(2, status.equals("CANCELLED") ? "OUT_OF_STOCK" : null);
                        update.setLong(3, Long.parseLong(orderId));
                        assertEquals(1, update.executeUpdate());
                    }
                }
                var row = Json.newObject().put("orderId", orderId).put("buyerId", buyer)
                        .put("buyerName", buyerNames.get(buyer)).put("createdAt", created.toString()).put("status", status)
                        .put("total", index % 3 != 0 ? "59.85" : "19.95").put("currency", currency);
                if (status.equals("CANCELLED")) row.put("cancellationReason", "OUT_OF_STOCK");
                else row.putNull("cancellationReason");
                var listedItems = row.putArray("items");
                for (JsonNode item : items) listedItems.addObject().put("productId", item.path("productId").asText())
                        .put("productName", productNames.get(item.path("productId").asText()))
                        .put("quantity", item.path("quantity").asInt());
                expected.get(shard.region()).add(row);
            }
        }
    }

    @Then("order pages match the regional orders in both date directions with and without a product filter")
    public void pages() throws Exception {
        for (String region : expected.keySet()) {
            for (String sort : List.of("ASC", "DESC")) {
                for (String filter : List.of("", selected.id())) {
                    assertPages(region, sort, filter.isEmpty() ? "" : "&productId=" + filter,
                            expected(region, sort, filter));
                }
            }
        }
        assertEquals(expected("US", "DESC", "").subList(0, 25), rows(page("region=US")));
        assertEquals(expected("US", "ASC", ""), rows(page("region=US&sort=ASC&limit=100")));
        assertTrue(page("region=US&productId=9223372036854775807").path("items").isEmpty());
        assertTrue(page("region=US&productId=9223372036854775807").path("nextAfter").isNull());
        assertTrue(page("region=US&sort=ASC&afterCreatedAt=2099-01-01T00:00:00Z&afterOrderId=1")
                .path("items").isEmpty());
    }

    @Then("product names filter literal snapshot substrings across globally sorted pages")
    public void productNames() throws Exception {
        // These known fixture sets independently specify which complete orders must match.
        Map<String, Set<String>> matches = new LinkedHashMap<>();
        matches.put("CeDaR", Set.of(selected.id(), otherProductId));
        matches.put("  rAvEl  ", Set.of(selected.id()));
        matches.put("50%_", Set.of(otherProductId));
        matches.put("' OR 1=1 --", Set.of(otherProductId));
        matches.put("%", Set.of(otherProductId));
        matches.put("_", Set.of(otherProductId));
        matches.put("' OR 2=2 --", Set.of());
        matches.put("unmatched product", Set.of());
        matches.put("🛍".repeat(200), Set.of());
        for (String region : expected.keySet()) {
            for (String sort : List.of("ASC", "DESC")) {
                for (var match : matches.entrySet()) {
                    List<JsonNode> want = expected(region, sort, "").stream()
                            .filter(row -> rows(row).stream().anyMatch(item ->
                                    match.getValue().contains(item.path("productId").asText())))
                            .toList();
                    assertPages(region, sort, "&productName=" + encode(match.getKey()), want);
                }
            }
        }
    }

    private void assertPages(String region, String sort, String filter, List<JsonNode> want) throws Exception {
        List<JsonNode> seen = new ArrayList<>();
        String query = "region=" + region + "&sort=" + sort + "&limit=7" + filter;
        String continuation = "";
        for (int pageNumber = 0; ; pageNumber++) {
            assertTrue(pageNumber < 20, "Cursor did not terminate");
            JsonNode page = page(query + continuation);
            assertEquals(region, page.path("region").asText());
            List<JsonNode> rows = rows(page);
            int end = Math.min(seen.size() + 7, want.size());
            assertEquals(want.subList(seen.size(), end), rows, query + continuation);
            assertEquals(page, page(query + continuation), "Refresh is a read without data changes");
            seen.addAll(rows);
            JsonNode after = page.path("nextAfter");
            assertEquals(seen.size() < want.size(), !after.isNull());
            if (after.isNull()) break;
            assertEquals(rows.getLast().path("createdAt"), after.path("createdAt"));
            assertEquals(rows.getLast().path("orderId"), after.path("orderId"));
            continuation = "&afterCreatedAt=" + encode(after.path("createdAt").asText())
                    + "&afterOrderId=" + after.path("orderId").asText();
        }
        assertEquals(want, seen);
        assertEquals(seen.size(), seen.stream().map(row -> row.path("orderId").asText()).distinct().count());
    }

    @Then("order listing rejects invalid and ambiguous query parameters without changing orders")
    public void invalid() throws Exception {
        JsonNode before = page("region=US&limit=100");
        for (String query : List.of("", "region=", "region=us", "region=AFRICA", "region=US&region=US",
                "region=US&unknown=1", "region=US&limit=0", "region=US&limit=101", "region=US&limit=1.0",
                "region=US&limit=2147483648", "region=US&sort=asc", "region=US&sort=", "region=US&sort=ASC&sort=DESC",
                "region=US&productId=", "region=US&productId=0", "region=US&productId=01", "region=US&productId=-1",
                "region=US&productId=%2B1", "region=US&productId=1%0A", "region=US&productId=9223372036854775808",
                "region=US&productId=1&productId=2",
                "region=US&productName=", "region=US&productName=%20%20",
                "region=US&productName=Cedar&productName=Travel",
                "region=US&productId=" + selected.id() + "&productName=Cedar",
                "region=US&productName=Cedar%0AKit", "region=US&productName=Cedar%00Kit",
                "region=US&productName=Cedar%7FKit", "region=US&productName=" + "a".repeat(201),
                "region=US&productName=" + encode("🛍".repeat(201)), "region=US&afterOrderId=1",
                "region=US&afterCreatedAt=2026-01-01T00:00:00Z", "region=US&afterCreatedAt=bad&afterOrderId=1",
                "region=US&afterCreatedAt=2026-01-01T00:00:00Z&afterOrderId=01",
                "region=US&afterCreatedAt=2026-01-01T00:00:00.123456789Z&afterOrderId=1")) {
            var response = exchange("GET", "/api/v1/orders" + (query.isEmpty() ? "" : "?" + query), null, null);
            CatalogApi.assertError(response, 400, "INVALID_REQUEST");
        }
        assertEquals(before, page("region=US&limit=100"));
    }

    @Then("an empty order region returns a terminal page")
    public void empty() throws Exception {
        JsonNode page = page("region=EU");
        assertEquals("EU", page.path("region").asText());
        assertTrue(page.path("items").isEmpty());
        assertTrue(page.path("nextAfter").isNull());
    }

    @Then("a missing US order shard fails the whole listing while other regions remain readable")
    public void unavailable() throws Exception {
        order.restart(Set.of("shard-d"));
        try {
            var response = exchange("GET", "/api/v1/orders?region=US&limit=1", null, null);
            CatalogApi.assertError(response, 503, "ORDER_STORE_UNAVAILABLE");
            assertTrue(response.elapsed().compareTo(Duration.ofSeconds(4)) < 0, response.elapsed().toString());
            assertFalse(Json.parse(response.body()).has("items"));
            assertEquals(expected("EU", "DESC", ""), rows(page("region=EU")));
        } finally {
            order.restart(Set.of());
        }
        assertEquals(expected("US", "DESC", "").subList(0, 25), rows(page("region=US")));
    }

    private List<JsonNode> expected(String region, String sort, String productId) {
        Comparator<JsonNode> comparator = Comparator.comparing((JsonNode row) -> Instant.parse(row.path("createdAt").asText()))
                .thenComparingLong(row -> Long.parseLong(row.path("orderId").asText()));
        if (sort.equals("DESC")) comparator = comparator.reversed();
        return expected.get(region).stream().filter(row -> productId.isEmpty() || rows(row).stream()
                .anyMatch(item -> item.path("productId").asText().equals(productId))).sorted(comparator).toList();
    }

    private CatalogApi.Product product(String key, String region, String currency, String name) throws Exception {
        var request = CatalogApi.sellerRequest("Browser " + key, region);
        var response = catalog.createSeller(key, request);
        assertEquals(201, response.status(), response.text());
        var seller = catalog.remember(key, key, request, response);
        var payload = CatalogApi.itemRequest().put("currency", currency).put("name", name);
        var created = catalog.post(seller.path() + "/products", key, Json.bytes(payload));
        assertEquals(201, created.status(), created.text());
        var result = catalog.remember(key, seller, key, payload, created);
        productNames.put(result.id(), name);
        return result;
    }

    private static ObjectNode buyerRequest(String region, String surname) {
        return Json.object("{\"firstName\":\"Alex\",\"surname\":\"Example\",\"email\":\"alex@example.com\",\"phone\":\"+12025550123\",\"address\":{\"line1\":\"1 Example Road\",\"city\":\"Example City\",\"postalCode\":\"00001\",\"countryCode\":\"US\"}}")
                .put("region", region).put("surname", surname);
    }

    private JsonNode page(String query) throws Exception {
        return body(exchange("GET", "/api/v1/orders?" + query, null, null), 200);
    }

    private RawHttp.Response exchange(String method, String path, String key, JsonNode payload) throws Exception {
        List<RawHttp.Header> headers = new ArrayList<>();
        if (key != null) headers.add(new RawHttp.Header("Idempotency-Key", key));
        if (payload != null) headers.add(new RawHttp.Header("Content-Type", "application/json"));
        var response = RawHttp.exchange(order.port(), method, path, headers,
                payload == null ? new byte[0] : Json.bytes(payload),
                payload == null ? RawHttp.Framing.NONE : RawHttp.Framing.CONTENT_LENGTH);
        var schema = Contracts.ORDER_API.responseSchema(method, path, response.status());
        assertTrue(schema.isPresent(), "Undocumented response for " + method + " " + path);
        assertEquals(List.of(), Contracts.ORDER_API.errors(schema.orElseThrow(), Json.parse(response.body())), response.text());
        return response;
    }

    private static JsonNode body(RawHttp.Response response, int status) {
        assertEquals(status, response.status(), response::text);
        return Json.parse(response.body());
    }

    private static List<JsonNode> rows(JsonNode page) {
        List<JsonNode> rows = new ArrayList<>();
        page.path("items").forEach(rows::add);
        return rows;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
