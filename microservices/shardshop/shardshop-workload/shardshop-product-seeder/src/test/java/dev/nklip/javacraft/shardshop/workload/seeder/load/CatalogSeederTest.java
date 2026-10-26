package dev.nklip.javacraft.shardshop.workload.seeder.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.RequestBodies;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets.CreatedProduct;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets.CreatedSeller;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
    private final WorkloadControl control = RetryPolicyTest.control("1000");
    private final CatalogSeeder seeder = new CatalogSeeder(new SeederDatasets(
            new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofSeconds(5)), CatalogDataset.VERSION),
            new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(150), pause, control));
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
        List<String> completed = new ArrayList<>();
        assertEquals(1056, seeder.plannedOperations());
        SeedReport first = seeder.seed(() -> completed.add("operation"));
        assertEquals(1056, completed.size(), "One creation and two verifications for each entity");
        assertEquals(List.of(new ControlStatus.TrafficCategory("creation", 352, 0, 0),
                new ControlStatus.TrafficCategory("verification", 704, 0, 0)), control.status().traffic());
        assertEquals("Seeded and verified catalog-v2 [US 50 sellers, 50 products {USD=50}; "
                + "EU 50 sellers, 100 products {EUR=50, GBP=50}; ASIA 51 sellers, 51 products {CNY=32, KRW=4, JPY=14, VND=1}], "
                + "key-to-ID SHA-256 " + first.mappingDigest(), first.summary());
        assertEquals(catalog.sellers(), first.sellers().stream().map(CreatedSeller::definition).toList());
        assertEquals(catalog.products(), first.products().stream().map(CreatedProduct::definition).toList());
        Map<String, String> sellerIds = first.sellers().stream()
                .collect(Collectors.toMap(seller -> seller.definition().key(), CreatedSeller::sellerId));
        first.products().forEach(product -> assertEquals(sellerIds.get(product.definition().sellerKey()), product.sellerId()));
        assertEquals(issuedIds(), ids(first));
        assertEquals(352, new HashSet<>(ids(first).values()).size());
        assertEquals(352, requests.stream().filter(request -> request.startsWith("POST ")).count());
        assertEquals(704, requests.stream().filter(request -> request.startsWith("GET ")).count());
        List<String> firstRequests = List.copyOf(requests);
        requests.clear();

        SeedReport second = seeder.seed();
        assertEquals(first, second);
        assertEquals(first.mappingDigest(), second.mappingDigest());
        assertEquals(firstRequests, requests);
        assertEquals(352, stored.size());
        verifyNoInteractions(pause);
    }

    @Test
    void retriesTransientFailuresAndLostResponsesWithTheSameKeyAndPayload() throws Exception {
        fault("create catalog-v2.US.seller.1", Fault.UNAVAILABLE, Fault.DISCONNECT);
        fault("create catalog-v2.EU.product.3", Fault.LOST_RESPONSE);
        fault("read catalog-v2.ASIA.seller.2", Fault.UNAVAILABLE);
        fault("lookup catalog-v2.US.product.2", Fault.DISCONNECT);
        SeedReport report = seeder.seed();
        assertEquals(352, stored.size());
        assertEquals(issuedIds(), ids(report));
        assertEquals(1061, requests.size());
        List<String> sellerPosts = posts("catalog-v2.US.seller.1");
        assertEquals(3, sellerPosts.size());
        assertEquals(1, new HashSet<>(sellerPosts).size());
        List<String> productPosts = posts("catalog-v2.EU.product.3");
        assertEquals(2, productPosts.size());
        assertEquals(1, new HashSet<>(productPosts).size());
        verify(pause, times(4)).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
        verifyNoMoreInteractions(pause);
    }

    @Test
    void retriesReplicaLagForSellerAndProductReadsByIdAndCreationKey() throws Exception {
        fault("read catalog-v2.US.seller.1", Fault.NOT_FOUND, Fault.NOT_FOUND);
        fault("lookup catalog-v2.EU.seller.1", Fault.NOT_FOUND);
        fault("read catalog-v2.US.product.1", Fault.NOT_FOUND);
        fault("lookup catalog-v2.ASIA.product.1", Fault.NOT_FOUND);

        List<String> completed = new ArrayList<>();
        SeedReport report = seeder.seed(() -> completed.add("operation"));

        assertEquals(issuedIds(), ids(report));
        assertEquals(352, stored.size(), "Replica lag must not recreate acknowledged entities");
        assertEquals(1056, completed.size(), "An operation completes only after its verification succeeds");
        assertEquals(1061, requests.size());
        assertEquals(List.of(new ControlStatus.TrafficCategory("creation", 352, 0, 0),
                new ControlStatus.TrafficCategory("verification", 709, 5, 5)), control.status().traffic());
        verify(pause, times(4)).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
        verifyNoMoreInteractions(pause);
    }

    @Test
    void missingEntityStopsVerificationWhenTheRetryBudgetIsExhausted() throws Exception {
        fault("read catalog-v2.US.seller.1", Fault.NOT_FOUND, Fault.NOT_FOUND, Fault.NOT_FOUND);
        List<String> completed = new ArrayList<>();

        SeedingException failure = assertThrows(SeedingException.class,
                () -> seeder.seed(() -> completed.add("operation")));

        assertEquals("Read seller catalog-v2.US.seller.1 failed on attempt 3: HTTP 404 SELLER_NOT_FOUND",
                failure.getMessage());
        assertEquals(352, completed.size(), "Only creations completed before the missing first verification");
        assertEquals(352, stored.size());
        assertEquals(355, requests.size());
        assertEquals(new ControlStatus.TrafficCategory("verification", 3, 2, 3), control.status().traffic().get(1));
        verify(pause).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
        verifyNoMoreInteractions(pause);
    }

    @Test
    void aStalledReadAfterNotFoundUsesTheRequestDeadlineAndTheSameAttemptBudget() throws Exception {
        fault("read catalog-v2.US.seller.1", Fault.NOT_FOUND, Fault.NOT_FOUND);
        AtomicInteger reads = new AtomicInteger();
        CompletableFuture<HttpResponse<byte[]>> stalled = new CompletableFuture<>();
        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            if (request.method().equals("GET") && reads.incrementAndGet() == 2) {
                return stalled;
            }
            return respond(request);
        }).when(http).sendAsync(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        CatalogSeeder bounded = new CatalogSeeder(new SeederDatasets(
                new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofMillis(20)), CatalogDataset.VERSION),
                new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(150), pause, control));

        SeedingException failure = assertThrows(SeedingException.class, bounded::seed);

        assertEquals("Read seller catalog-v2.US.seller.1 failed on attempt 3: HTTP 404 SELLER_NOT_FOUND",
                failure.getMessage());
        assertTrue(stalled.isCancelled(), "The per-attempt deadline must cancel the stalled HTTP response");
        assertEquals(3, reads.get(), "404 and deadline failures share one finite attempt budget");
        verify(pause).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
        verifyNoMoreInteractions(pause);
    }

    @Test
    void partialRunKeepsIssuedIdsRegionsAndPayloadsForTheNextRun() throws Exception {
        fault("create catalog-v2.EU.seller.10", Fault.UNAVAILABLE, Fault.UNAVAILABLE, Fault.UNAVAILABLE);
        SeedingException failure = assertThrows(SeedingException.class, seeder::seed);
        assertEquals("Create seller catalog-v2.EU.seller.10 failed on attempt 3: HTTP 503 CATALOG_UNAVAILABLE",
                failure.getMessage());
        assertTrue(requests.stream().allMatch(request -> request.startsWith("POST ")));
        Map<String, Stored> partial = Map.copyOf(stored);
        Map<String, String> partialIds = issuedIds();
        // The first 50 US sellers own one product each; the first nine EU sellers own two each.
        assertEquals(59 + 50 + 18, partial.size());

        SeedReport report = seeder.seed();
        assertEquals(352, stored.size());
        partial.forEach((scope, entity) -> assertEquals(entity, stored.get(scope)));
        Map<String, String> ids = ids(report);
        partialIds.forEach((key, id) -> assertEquals(id, ids.get(key)));
        assertEquals("US", stored.get("seller\0US\0catalog-v2.US.seller.50").entity().get("region").textValue());
        assertEquals("EU", stored.get("seller\0EU\0catalog-v2.EU.seller.9").entity().get("region").textValue());
        verify(pause).sleep(Duration.ofMillis(100));
        verify(pause).sleep(Duration.ofMillis(150));
    }

    @Test
    void changedPayloadsAndOtherRejectionsStopWithoutRetries() throws Exception {
        fault("create catalog-v2.US.product.1", Fault.CONFLICT);
        assertEquals("Create product catalog-v2.US.product.1 failed on attempt 1: HTTP 409 PRODUCT_IDEMPOTENCY_CONFLICT",
                assertThrows(SeedingException.class, seeder::seed).getMessage());
        assertEquals(1, stored.size());
        String sellerId = stored.values().iterator().next().entity().get("sellerId").textValue();
        stored.put("product\0" + sellerId + "\0catalog-v2.US.product.1", new Stored("catalog-v2.US.product.1",
                json.createObjectNode().put("name", "Retained other payload"), json.createObjectNode()));
        assertEquals("Create product catalog-v2.US.product.1 failed on attempt 1: HTTP 409 PRODUCT_IDEMPOTENCY_CONFLICT",
                assertThrows(SeedingException.class, seeder::seed).getMessage());
        fault("create catalog-v2.US.seller.1", Fault.CONFLICT);
        assertEquals("Create seller catalog-v2.US.seller.1 failed on attempt 1: HTTP 409 SELLER_IDEMPOTENCY_CONFLICT",
                assertThrows(SeedingException.class, seeder::seed).getMessage());
        verifyNoInteractions(pause);
    }

    @Test
    void rejectsDifferentIdentitiesAndPayloadsWithoutRetries() throws Exception {
        Map<String, String> failures = new LinkedHashMap<>();
        failures.put("read catalog-v2.EU.seller.7", "Read seller catalog-v2.EU.seller.7 returned a different ID than its acknowledged creation");
        failures.put("lookup catalog-v2.EU.seller.1", "Look up seller catalog-v2.EU.seller.1 returned a different ID than its acknowledged creation");
        failures.put("read catalog-v2.US.product.50", "Read product catalog-v2.US.product.50 returned a different ID than its acknowledged creation");
        failures.put("lookup catalog-v2.ASIA.product.1", "Look up product catalog-v2.ASIA.product.1 returned a different ID than its acknowledged creation");
        failures.put("read catalog-v2.EU.product.2", "Read product catalog-v2.EU.product.2 failed on attempt 1: Product response does not match its definition");
        failures.put("lookup catalog-v2.US.seller.3", "Look up seller catalog-v2.US.seller.3 failed on attempt 1: Seller response does not match its definition");
        for (Map.Entry<String, String> expected : failures.entrySet()) {
            Fault fault = expected.getValue().contains("different ID") ? Fault.OTHER_ID : Fault.CHANGED_PAYLOAD;
            fault(expected.getKey(), fault);
            assertEquals(expected.getValue(), assertThrows(SeedingException.class, seeder::seed).getMessage());
        }
        assertEquals(352, stored.size());
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
                        .put("stock", payload.get("initialStock").intValue())
                        .put("countryCode", stored.values().stream()
                                .filter(value -> !value.entity().has("productId")
                                        && value.entity().get("sellerId").textValue().equals(products.group(1)))
                                .findFirst().orElseThrow().entity().get("countryCode").textValue())
                        .put("newOrderEligible", true).putNull("legacyReason");
            } else {
                entity.put("sellerId", Long.toString(nextId));
                ObjectNode profits = json.createObjectNode();
                dev.nklip.javacraft.shardshop.common.CurrencyPolicy.currencies(
                        payload.get("region").textValue(), payload.get("countryCode").textValue())
                        .forEach(currency -> profits.put(currency, "0.00"));
                entity.set("profitsEarned", profits);
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
