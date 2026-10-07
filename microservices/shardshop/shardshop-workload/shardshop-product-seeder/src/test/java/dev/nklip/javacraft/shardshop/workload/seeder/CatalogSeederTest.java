package dev.nklip.javacraft.shardshop.workload.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedProduct;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedSeller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CatalogSeederTest {
    private static final Pattern SELLER = Pattern.compile("/api/v1/sellers/([0-9]+)");
    private static final Pattern SELLER_KEY = Pattern.compile("/api/v1/sellers/by-key/([^/]+)");
    private static final Pattern PRODUCTS = Pattern.compile("/api/v1/sellers/([0-9]+)/products");
    private static final Pattern PRODUCT = Pattern.compile("/api/v1/sellers/([0-9]+)/products/([0-9]+)");
    private static final Pattern PRODUCT_KEY = Pattern.compile("/api/v1/sellers/([0-9]+)/products/by-key/([^/]+)");

    private enum Fault { UNAVAILABLE, DISCONNECT, LOST_RESPONSE, NOT_FOUND, CONFLICT, OTHER_ID, CHANGED_PAYLOAD }

    /** One committed entity: its creation key, the first creation payload and the stored response. */
    private record Stored(String key, JsonNode payload, ObjectNode entity) { }

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = mock(HttpClient.class);
    private final RetryPolicy.Pause pause = mock();
    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final CatalogSeeder seeder = new CatalogSeeder(new SeederDatasets(
            new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofSeconds(5)), CatalogDataset.VERSION),
            new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(150), pause));
    // The answered HttpClient keeps creation scopes like product: region/key for sellers, sellerId/key for products.
    private final Map<String, Stored> stored = new LinkedHashMap<>();
    private final Map<String, Deque<Fault>> faults = new HashMap<>();
    private final List<String> requests = new ArrayList<>();
    private long nextId = 9_007_199_254_740_993L;

    @BeforeEach
    void answerLikeProduct() {
        when(http.sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenAnswer(invocation -> respond(invocation.getArgument(0)));
    }

    @Test
    void seedsEveryRegionalEntityAndRecoversTheSameIdsOnARepeatedRun() throws Exception {
        SeedReport first = seeder.seed();
        assertEquals("Seeded and verified catalog-v1 [US 50 sellers, 100 products {USD=50, EUR=50}; "
                + "EU 50 sellers, 100 products {USD=50, EUR=50}; ASIA 50 sellers, 100 products {USD=50, EUR=50}], "
                + "key-to-ID SHA-256 " + first.mappingDigest(), first.summary());
        assertEquals(catalog.sellers(), first.sellers().stream().map(CreatedSeller::definition).toList());
        assertEquals(catalog.products(), first.products().stream().map(CreatedProduct::definition).toList());
        Map<String, String> sellerIds = first.sellers().stream()
                .collect(Collectors.toMap(seller -> seller.definition().key(), CreatedSeller::sellerId));
        first.products().forEach(product -> assertEquals(sellerIds.get(product.definition().sellerKey()), product.sellerId()));
        assertEquals(issuedIds(), ids(first));
        assertEquals(450, new HashSet<>(ids(first).values()).size());
        assertEquals(450, requests.stream().filter(request -> request.startsWith("POST ")).count());
        assertEquals(900, requests.stream().filter(request -> request.startsWith("GET ")).count());
        List<String> firstRequests = List.copyOf(requests);
        requests.clear();

        SeedReport second = seeder.seed();
        assertEquals(first, second);
        assertEquals(first.mappingDigest(), second.mappingDigest());
        assertEquals(firstRequests, requests);
        assertEquals(450, stored.size());
        verifyNoInteractions(pause);
    }

    @Test
    void retriesTransientFailuresAndLostResponsesWithTheSameKeyAndPayload() throws Exception {
        fault("create catalog-v1.US.seller.1", Fault.UNAVAILABLE, Fault.DISCONNECT);
        fault("create catalog-v1.EU.product.3", Fault.LOST_RESPONSE);
        fault("read catalog-v1.ASIA.seller.2", Fault.UNAVAILABLE);
        fault("lookup catalog-v1.US.product.2", Fault.DISCONNECT);
        SeedReport report = seeder.seed();
        assertEquals(450, stored.size());
        assertEquals(issuedIds(), ids(report));
        assertEquals(1355, requests.size());
        List<String> sellerPosts = posts("catalog-v1.US.seller.1");
        assertEquals(3, sellerPosts.size());
        assertEquals(1, new HashSet<>(sellerPosts).size());
        List<String> productPosts = posts("catalog-v1.EU.product.3");
        assertEquals(2, productPosts.size());
        assertEquals(1, new HashSet<>(productPosts).size());
        verify(pause, times(4)).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
        verifyNoMoreInteractions(pause);
    }

    @Test
    void partialRunKeepsIssuedIdsRegionsAndPayloadsForTheNextRun() throws Exception {
        fault("create catalog-v1.EU.seller.10", Fault.UNAVAILABLE, Fault.UNAVAILABLE, Fault.UNAVAILABLE);
        SeedingException failure = assertThrows(SeedingException.class, seeder::seed);
        assertEquals("Create seller catalog-v1.EU.seller.10 failed on attempt 3: HTTP 503 CATALOG_UNAVAILABLE",
                failure.getMessage());
        assertTrue(requests.stream().allMatch(request -> request.startsWith("POST ")));
        Map<String, Stored> partial = Map.copyOf(stored);
        Map<String, String> partialIds = issuedIds();
        assertEquals(59 + 118, partial.size());

        SeedReport report = seeder.seed();
        assertEquals(450, stored.size());
        partial.forEach((scope, entity) -> assertEquals(entity, stored.get(scope)));
        Map<String, String> ids = ids(report);
        partialIds.forEach((key, id) -> assertEquals(id, ids.get(key)));
        assertEquals("US", stored.get("seller\0US\0catalog-v1.US.seller.50").entity().get("region").textValue());
        assertEquals("EU", stored.get("seller\0EU\0catalog-v1.EU.seller.9").entity().get("region").textValue());
        verify(pause).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
    }

    @Test
    void changedPayloadsAndOtherRejectionsStopWithoutRetries() throws Exception {
        fault("create catalog-v1.US.product.1", Fault.CONFLICT);
        assertEquals("Create product catalog-v1.US.product.1 failed on attempt 1: HTTP 409 PRODUCT_IDEMPOTENCY_CONFLICT",
                assertThrows(SeedingException.class, seeder::seed).getMessage());
        assertEquals(1, stored.size());
        String sellerId = stored.values().iterator().next().entity().get("sellerId").textValue();
        stored.put("product\0" + sellerId + "\0catalog-v1.US.product.1", new Stored("catalog-v1.US.product.1",
                json.createObjectNode().put("name", "Retained other payload"), json.createObjectNode()));
        assertEquals("Create product catalog-v1.US.product.1 failed on attempt 1: HTTP 409 PRODUCT_IDEMPOTENCY_CONFLICT",
                assertThrows(SeedingException.class, seeder::seed).getMessage());
        fault("create catalog-v1.US.seller.1", Fault.CONFLICT);
        assertEquals("Create seller catalog-v1.US.seller.1 failed on attempt 1: HTTP 409 SELLER_IDEMPOTENCY_CONFLICT",
                assertThrows(SeedingException.class, seeder::seed).getMessage());
        verifyNoInteractions(pause);
    }

    @Test
    void completesOnlyAfterPrimaryReadsConfirmEveryAcknowledgedEntity() throws Exception {
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put("read catalog-v1.ASIA.seller.50", "Read seller catalog-v1.ASIA.seller.50 failed on attempt 1: HTTP 404 SELLER_NOT_FOUND");
        failures.put("read catalog-v1.EU.seller.7", "Read seller catalog-v1.EU.seller.7 returned a different ID than its acknowledged creation");
        failures.put("lookup catalog-v1.EU.seller.1", "Look up seller catalog-v1.EU.seller.1 returned a different ID than its acknowledged creation");
        failures.put("read catalog-v1.US.product.100", "Read product catalog-v1.US.product.100 returned a different ID than its acknowledged creation");
        failures.put("lookup catalog-v1.ASIA.product.1", "Look up product catalog-v1.ASIA.product.1 returned a different ID than its acknowledged creation");
        failures.put("lookup catalog-v1.US.product.1", "Look up product catalog-v1.US.product.1 failed on attempt 1: HTTP 404 PRODUCT_NOT_FOUND");
        failures.put("read catalog-v1.EU.product.2", "Read product catalog-v1.EU.product.2 failed on attempt 1: Product response does not match its definition");
        failures.put("lookup catalog-v1.US.seller.3", "Look up seller catalog-v1.US.seller.3 failed on attempt 1: Seller response does not match its definition");
        for (Map.Entry<String, String> expected : failures.entrySet()) {
            Fault fault = expected.getValue().contains("HTTP 404") ? Fault.NOT_FOUND
                    : expected.getValue().contains("different ID") ? Fault.OTHER_ID : Fault.CHANGED_PAYLOAD;
            fault(expected.getKey(), fault);
            assertEquals(expected.getValue(), assertThrows(SeedingException.class, seeder::seed).getMessage());
        }
        assertEquals(450, stored.size());
        assertEquals(issuedIds(), ids(seeder.seed()));
        verifyNoInteractions(pause);
    }

    private void fault(String operation, Fault... pending) {
        faults.put(operation, new ArrayDeque<>(Arrays.asList(pending)));
    }

    private List<String> posts(String key) {
        return requests.stream().filter(request -> request.startsWith("POST ") && request.contains(" " + key + " ")).toList();
    }

    private Map<String, String> issuedIds() {
        Map<String, String> ids = new HashMap<>();
        stored.values().forEach(value -> ids.put(value.key(), identity(value.entity())));
        return ids;
    }

    private static Map<String, String> ids(SeedReport report) {
        Map<String, String> ids = new HashMap<>();
        report.sellers().forEach(seller -> ids.put(seller.definition().key(), seller.sellerId()));
        report.products().forEach(product -> ids.put(product.definition().key(),
                product.sellerId() + "/" + product.productId()));
        return ids;
    }

    private static String identity(ObjectNode entity) {
        String sellerId = entity.get("sellerId").textValue();
        return entity.has("productId") ? sellerId + "/" + entity.get("productId").textValue() : sellerId;
    }

    private CompletableFuture<HttpResponse<byte[]>> respond(HttpRequest request) throws IOException {
        String path = request.uri().getRawPath();
        String key = request.headers().firstValue("Idempotency-Key").orElse("-");
        String body = request.method().equals("POST") ? RequestBodies.of(request) : "-";
        requests.add(request.method() + " " + request.uri() + " " + key + " " + body);
        if (request.method().equals("POST")) {
            return create(path, key, json.readTree(body));
        }
        Matcher sellerKey = SELLER_KEY.matcher(path);
        Matcher productKey = PRODUCT_KEY.matcher(path);
        Matcher product = PRODUCT.matcher(path);
        Matcher seller = SELLER.matcher(path);
        Stored found;
        String operation;
        if (sellerKey.matches()) {
            found = stored.get("seller\0" + request.uri().getRawQuery().substring("region=".length()) + "\0" + sellerKey.group(1));
            operation = "lookup ";
        } else if (productKey.matches()) {
            found = stored.get("product\0" + productKey.group(1) + "\0" + productKey.group(2));
            operation = "lookup ";
        } else if (product.matches()) {
            found = stored.values().stream().filter(value -> path.equals("/api/v1/sellers/" + identity(value.entity())
                    .replace("/", "/products/"))).findFirst().orElse(null);
            operation = "read ";
        } else {
            assertTrue(seller.matches());
            found = stored.values().stream().filter(value -> path.equals("/api/v1/sellers/" + identity(value.entity())))
                    .findFirst().orElse(null);
            operation = "read ";
        }
        boolean products = path.contains("/products/");
        Fault fault = found == null ? Fault.NOT_FOUND : next(operation + found.key());
        if (fault == null) {
            return reply(200, found.entity());
        }
        return switch (fault) {
            case UNAVAILABLE -> error(503, "CATALOG_UNAVAILABLE");
            case DISCONNECT -> CompletableFuture.failedFuture(new ConnectException("refused"));
            case NOT_FOUND -> error(404, products ? "PRODUCT_NOT_FOUND" : "SELLER_NOT_FOUND");
            case OTHER_ID -> reply(200, found.entity().deepCopy().put(products ? "productId" : "sellerId", "1"));
            default -> reply(200, found.entity().deepCopy().put(products ? "description" : "companyName", "Changed"));
        };
    }

    private CompletableFuture<HttpResponse<byte[]>> create(String path, String key, JsonNode payload) {
        Matcher products = PRODUCTS.matcher(path);
        String scope = products.matches() ? "product\0" + products.group(1) + "\0" + key
                : "seller\0" + payload.get("region").textValue() + "\0" + key;
        String conflict = products.matches() ? "PRODUCT_IDEMPOTENCY_CONFLICT" : "SELLER_IDEMPOTENCY_CONFLICT";
        Fault fault = next("create " + key);
        if (fault == Fault.UNAVAILABLE) {
            return error(503, "CATALOG_UNAVAILABLE");
        }
        if (fault == Fault.DISCONNECT) {
            return CompletableFuture.failedFuture(new ConnectException("refused"));
        }
        Stored existing = stored.get(scope);
        if (fault == Fault.CONFLICT || existing != null && !existing.payload().equals(payload)) {
            return error(409, conflict);
        }
        if (existing == null) {
            ObjectNode entity = payload.deepCopy();
            if (products.matches()) {
                entity.put("sellerId", products.group(1)).put("productId", Long.toString(nextId))
                        .put("stock", payload.get("initialStock").intValue());
            } else {
                entity.put("sellerId", Long.toString(nextId))
                        .set("profitsEarned", json.createObjectNode().put("USD", "0.00").put("EUR", "0.00"));
            }
            nextId += 7919;
            stored.put(scope, new Stored(key, payload, entity));
        }
        if (fault == Fault.LOST_RESPONSE) {
            return CompletableFuture.failedFuture(new IOException("connection reset after commit"));
        }
        return reply(existing == null ? 201 : 200, stored.get(scope).entity());
    }

    private Fault next(String operation) {
        Deque<Fault> pending = faults.get(operation);
        return pending == null ? null : pending.poll();
    }

    private CompletableFuture<HttpResponse<byte[]>> error(int status, String code) {
        return reply(status, json.createObjectNode().put("code", code).put("message", "Recorded test failure"));
    }

    private static CompletableFuture<HttpResponse<byte[]>> reply(int status, JsonNode body) {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.toString().getBytes(StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(response);
    }
}
