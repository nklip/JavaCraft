package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Placeholders;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Framing;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Header;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The product API as a client of one scenario sees it: the sellers and products that it created, by alias, and the
 * checks of the response bodies that the product OpenAPI document defines. Each response must have a status that the
 * document defines for its operation and a body that matches the schema of that status.
 */
public final class CatalogApi {
    public static final String SELLERS = "/api/v1/sellers";
    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern ENTITY = Pattern.compile("\\{(seller|product):([^}]+)}");
    private static final Pattern RAW_BYTE = Pattern.compile("\\\\x([0-9a-fA-F]{2})");
    private static final Set<String> SELLER_FIELDS = Set.of("sellerId", "companyName", "region", "profitsEarned");
    private static final Set<String> PRODUCT_FIELDS = Set.of("sellerId", "productId", "name", "description", "price",
            "unitCost", "currency", "initialStock", "stock");

    private final ProductRuntime product;
    private final Map<String, Seller> sellers = new LinkedHashMap<>();
    private final Map<String, Product> products = new LinkedHashMap<>();

    public record Seller(String alias, String key, String companyName, String region, JsonNode body) {
        public String id() {
            return body.get("sellerId").asText();
        }

        public String path() {
            return SELLERS + "/" + id();
        }

        public String keyPath() {
            return SELLERS + "/by-key/" + key + "?region=" + region;
        }
    }

    public record Product(String alias, Seller seller, String key, ObjectNode request, JsonNode body) {
        public String id() {
            return body.get("productId").asText();
        }

        public String path() {
            return seller.path() + "/products/" + id();
        }

        public String keyPath() {
            return seller.path() + "/products/by-key/" + key;
        }
    }

    public CatalogApi(ProductRuntime product) {
        this.product = product;
    }

    public static ObjectNode sellerRequest(String companyName, String region) {
        ObjectNode request = Json.newObject();
        request.put("companyName", companyName);
        request.put("region", region);
        return request;
    }

    /** The product payload of the provider checks of step 3.6. */
    public static ObjectNode itemRequest() {
        return Json.object("{\"name\":\"Verification item\",\"description\":\"Synthetic provider verification product\","
                + "\"price\":\"19.95\",\"unitCost\":\"12.50\",\"currency\":\"USD\",\"initialStock\":2}");
    }

    public Response createSeller(String key, ObjectNode request) throws IOException {
        return post(SELLERS, key, Json.bytes(request));
    }

    public Response post(String path, String key, byte[] body) throws IOException {
        List<Header> headers = new ArrayList<>(List.of(new Header("Content-Type", "application/json")));
        if (key != null) {
            headers.add(new Header("Idempotency-Key", key));
        }
        return exchange("POST", path, headers, body, Framing.CONTENT_LENGTH);
    }

    public Response get(String path) throws IOException {
        return exchange("GET", path, List.of(), new byte[0], Framing.NONE);
    }

    /** A request with exactly these headers and this framing; the response must match the product contract. */
    public Response exchange(String method, String path, List<Header> headers, byte[] body, Framing framing)
            throws IOException {
        return documented(method, path, RawHttp.exchange(product.port(), method, path, headers, body, framing));
    }

    /** Checks that the product OpenAPI document defines the status and the body of the response to a request. */
    public static Response documented(String method, String path, Response response) {
        Optional<String> schema = Contracts.PRODUCT_API.responseSchema(method, path, response.status());
        schema.ifPresent(pointer -> assertEquals(List.of(), Contracts.PRODUCT_API.errors(pointer,
                Json.parse(response.body())), () -> method + " " + path + " returned " + response.status() + " "
                + response.text() + ", which the product OpenAPI document does not define"));
        return response;
    }

    /** Repeats a creation after 503, as the workload clients do, and returns the first other response. */
    public Response postUntilAnswered(String path, String key, byte[] body) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            Response response = post(path, key, body);
            if (response.status() != 503 || attempt == 10) {
                return response;
            }
            Thread.sleep(50);
        }
    }

    public Seller remember(String alias, String key, ObjectNode request, Response response) {
        Seller seller = new Seller(alias, key, request.get("companyName").asText(), request.get("region").asText(),
                Json.parse(response.body()));
        sellers.put(alias, seller);
        return seller;
    }

    public Product remember(String alias, Seller seller, String key, ObjectNode request, Response response) {
        Product created = new Product(alias, seller, key, request, Json.parse(response.body()));
        products.put(alias, created);
        return created;
    }

    public Seller seller(String alias) {
        return require(sellers.get(alias), "seller " + alias);
    }

    public Product product(String alias) {
        return require(products.get(alias), "product " + alias);
    }

    public List<Seller> sellers() {
        return List.copyOf(sellers.values());
    }

    private static <T> T require(T value, String name) {
        assertNotNull(value, "The scenario did not create the " + name);
        return value;
    }

    /** Replaces {seller:alias} and {product:alias} with the issued IDs, and expands {repeat:text:count}. */
    public String expand(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (matcher.find()) {
            String id = "seller".equals(matcher.group(1)) ? seller(matcher.group(2)).id() : product(matcher.group(2)).id();
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(id));
        }
        return Placeholders.repeat(matcher.appendTail(expanded).toString());
    }

    /**
     * The bytes of a body cell: {@code item+{...}} is the item payload with the given members, {@code \xNN} is one raw
     * byte, and other text is sent as UTF-8 without a change.
     */
    public byte[] body(String cell) {
        String text = expand(cell);
        if (text.startsWith("item+")) {
            ObjectNode request = itemRequest();
            request.setAll(Json.object(text.substring("item+".length())));
            return Json.bytes(request);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Matcher matcher = RAW_BYTE.matcher(text);
        int start = 0;
        while (matcher.find()) {
            bytes.writeBytes(text.substring(start, matcher.start()).getBytes(StandardCharsets.UTF_8));
            bytes.write(Integer.parseInt(matcher.group(1), 16));
            start = matcher.end();
        }
        bytes.writeBytes(text.substring(start).getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    public static String encode(String pathSegment) {
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A seller body: exactly the OpenAPI fields, the requested values, a canonical ID and these profits. */
    public static void assertSeller(Response response, int status, String companyName, String region,
                                    Map<String, String> profits) {
        JsonNode body = assertJson(response, status);
        assertEquals(SELLER_FIELDS, fields(body), "Seller fields");
        assertCanonicalId(body.get("sellerId"));
        assertEquals(companyName, body.get("companyName").asText(), "companyName");
        assertEquals(region, body.get("region").asText(), "region");
        JsonNode earned = body.get("profitsEarned");
        assertTrue(earned.isObject(), "profitsEarned must be an object");
        Map<String, String> actual = new LinkedHashMap<>();
        earned.properties().forEach(entry -> {
            assertTrue(entry.getValue().isTextual(), "Profit amounts are strings");
            actual.put(entry.getKey(), entry.getValue().asText());
        });
        assertEquals(profits, actual, "profitsEarned");
    }

    /** A product body: exactly the OpenAPI fields, the requested values, canonical IDs and this stock. */
    public static void assertProduct(Response response, int status, ObjectNode request, String sellerId, int stock) {
        JsonNode body = assertJson(response, status);
        assertEquals(PRODUCT_FIELDS, fields(body), "Product fields");
        assertCanonicalId(body.get("productId"));
        assertEquals(sellerId, body.get("sellerId").asText(), "sellerId");
        for (String field : List.of("name", "description", "price", "unitCost", "currency")) {
            assertTrue(body.get(field).isTextual(), field + " must be a string");
            assertEquals(request.get(field).asText(), body.get(field).asText(), field);
        }
        assertTrue(body.get("initialStock").isInt() && body.get("stock").isInt(), "Stock values are integers");
        assertEquals(request.get("initialStock").intValue(), body.get("initialStock").intValue(), "initialStock");
        assertEquals(stock, body.get("stock").intValue(), "stock");
    }

    /** An error body: only a code and a nonempty message. */
    public static void assertError(Response response, int status, String code) {
        JsonNode body = assertJson(response, status);
        assertEquals(Set.of("code", "message"), fields(body), "Error fields of " + body);
        assertEquals(code, body.get("code").asText(), "Error code");
        assertTrue(body.get("message").isTextual() && !body.get("message").asText().isEmpty(), "Error message");
    }

    public static void assertFaster(Response response, Duration limit) {
        assertTrue(response.elapsed().compareTo(limit) < 0, "The response took " + response.elapsed().toMillis()
                + " ms; the limit is " + limit.toMillis() + " ms");
    }

    private static JsonNode assertJson(Response response, int status) {
        assertEquals(status, response.status(), () -> "HTTP status; body " + response.text());
        assertTrue(response.header("Content-Type").orElse("").startsWith("application/json"),
                () -> "Content-Type " + response.header("Content-Type"));
        JsonNode body = Json.parse(response.body());
        assertTrue(body.isObject(), "The body is a JSON object");
        return body;
    }

    private static void assertCanonicalId(JsonNode id) {
        assertTrue(id.isTextual(), "IDs are decimal strings");
        assertTrue(CANONICAL_ID.matcher(id.asText()).matches(), "Canonical ID " + id);
        assertFalse(id.asText().length() == 19 && id.asText().compareTo("9223372036854775807") > 0, "ID range");
    }

    private static Set<String> fields(JsonNode body) {
        Set<String> names = new HashSet<>();
        body.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
