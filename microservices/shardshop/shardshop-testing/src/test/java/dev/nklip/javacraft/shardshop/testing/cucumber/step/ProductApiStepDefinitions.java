package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Placeholders;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Framing;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Header;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Response;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.SqlRows;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.Product;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.Seller;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogLock;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.cucumber.datatable.DataTable;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.SELLERS;
import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.assertError;
import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.assertFaster;
import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.assertProduct;
import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.assertSeller;
import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.itemRequest;
import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.sellerRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The seller and product API of step 3.6 against the shard databases of the scenario. */
public class ProductApiStepDefinitions {
    private static final Map<String, String> ZERO_PROFITS = Map.of("USD", "0.00", "EUR", "0.00");
    private static final int MAX_BODY_BYTES = 32768;

    private final CatalogShards shards;
    private final ProductRuntime product;
    private final CatalogApi api;
    private final CatalogLock lock;
    private final List<Created> created = new ArrayList<>();
    private final List<Socket> openConnections = new ArrayList<>();
    private ExecutorService clients;
    private List<Future<Response>> concurrent = List.of();
    private Socket slowConnection;
    private byte[] slowBody;
    private Response slowResponse;

    /** One creation of the last When step, with the request that a check compares. */
    private record Created(String alias, String key, ObjectNode request, Response response) {
    }

    public ProductApiStepDefinitions(CatalogShards shards, ProductRuntime product, CatalogApi api, CatalogLock lock) {
        this.shards = shards;
        this.product = product;
        this.api = api;
        this.lock = lock;
    }

    @After
    public void closeClients() throws IOException {
        for (Socket connection : openConnections) {
            connection.close();
        }
        if (clients != null) {
            clients.shutdownNow();
        }
    }

    // Sellers

    @When("a client creates these sellers with the key {string}:")
    public void createSellers(String key, DataTable table) throws IOException {
        created.clear();
        for (Map<String, String> row : table.asMaps()) {
            created.add(createSeller(row.get("seller"), row.get("companyName"), row.get("region"), key));
        }
    }

    @Given("the seller {string} {string} in {string} with the key {string}")
    public void seller(String alias, String companyName, String region, String key) throws IOException {
        Created seller = createSeller(alias, companyName, region, key);
        assertSeller(seller.response(), 201, seller.request().get("companyName").asText(), region, ZERO_PROFITS);
    }

    @When("a client creates the seller {string} {string} in {string} with the key {string}")
    public void clientCreatesSeller(String alias, String companyName, String region, String key) throws IOException {
        created.clear();
        created.add(createSeller(alias, companyName, region, key));
    }

    private Created createSeller(String alias, String companyName, String region, String key) throws IOException {
        ObjectNode request = sellerRequest(Placeholders.repeat(companyName), region);
        Response response = api.createSeller(key, request);
        if (response.status() == 201 || response.status() == 200) {
            api.remember(alias, key, request, response);
        }
        return new Created(alias, key, request, response);
    }

    @When("a client creates the US sellers {string} and {string} with keys that select different US shards")
    public void createUsSellers(String first, String second) throws IOException {
        created.clear();
        CatalogShards.Shard firstShard = shards.ofKey("US", first);
        String secondKey = null;
        for (int index = 0; index < 100 && secondKey == null; index++) {
            if (!shards.ofKey("US", second + "-" + index).equals(firstShard)) {
                secondKey = second + "-" + index;
            }
        }
        assertTrue(secondKey != null, "No key selects the other US shard");
        created.add(createSeller(first, "First US company", "US", first));
        created.add(createSeller(second, "Second US company", "US", secondKey));
        for (Created seller : created) {
            assertEquals(201, seller.response().status(), seller.response().text());
        }
    }

    @When("a client creates the seller {string} {string} in {string} with the key {string} in a body of exactly {int} bytes")
    public void createSellerWithBodySize(String alias, String companyName, String region, String key, int size)
            throws IOException {
        ObjectNode request = sellerRequest(companyName, region);
        Response response = api.post(SELLERS, key, padded(request, size));
        if (response.status() == 201) {
            api.remember(alias, key, request, response);
        }
        created.clear();
        created.add(new Created(alias, key, request, response));
    }

    @Then("each/the creation returns 201 with the requested seller, a new seller ID and the profits USD {string} and EUR {string}")
    public void sellersCreated(String usd, String eur) {
        Set<String> otherIds = new HashSet<>();
        for (Seller seller : api.sellers()) {
            if (created.stream().noneMatch(creation -> creation.alias().equals(seller.alias()))) {
                otherIds.add(seller.id());
            }
        }
        for (Created seller : created) {
            assertSeller(seller.response(), 201, seller.request().get("companyName").asText(),
                    seller.request().get("region").asText(), Map.of("USD", usd, "EUR", eur));
            assertTrue(otherIds.add(Json.parse(seller.response().body()).get("sellerId").asText()), "A new seller ID");
        }
    }

    @Then("the sellers {string} have different IDs")
    public void differentSellerIds(String aliases) {
        List<String> ids = List.of(aliases.split(", ")).stream().map(alias -> api.seller(alias).id()).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size(), "Seller IDs " + ids);
    }

    @Then("each seller is stored only on the shard that its key selects, and its ID routes to that shard")
    public void eachSellerPlaced() throws SQLException {
        for (Created seller : created) {
            assertSellerPlaced(api.seller(seller.alias()));
        }
    }

    @Then("the seller {string} is stored only on the shard that its key selects, and its ID routes to that shard")
    public void sellerPlaced(String alias) throws SQLException {
        assertSellerPlaced(api.seller(alias));
    }

    private void assertSellerPlaced(Seller seller) throws SQLException {
        CatalogShards.Shard home = shards.ofKey(seller.region(), seller.key());
        assertEquals(home, shards.ofSeller(seller.id()), "The seller ID and the creation key select different shards");
        for (CatalogShards.Shard shard : shards.all()) {
            assertEquals(shard.equals(home) ? "1" : "0",
                    count(shard, "SELECT count(*) FROM catalog.sellers WHERE seller_id = ?", seller.id()),
                    "Rows of " + seller.alias() + " on " + shard.name());
        }
    }

    @Then("an identical retry of each creation returns 200 with the same seller")
    public void retryEachSeller() throws IOException {
        for (Created seller : created) {
            Response retry = api.createSeller(seller.key(), seller.request());
            assertEquals(200, retry.status(), retry.text());
            assertEquals(api.seller(seller.alias()).body(), Json.parse(retry.body()), "Retried seller");
        }
    }

    @Then("a GET by ID and a GET by creation key return each seller")
    public void readEachSeller() throws IOException {
        for (Created seller : created) {
            readSeller(seller.alias());
        }
    }

    @Then("a GET by ID and a GET by creation key return the seller {string}")
    public void readSeller(String alias) throws IOException {
        Seller seller = api.seller(alias);
        for (String path : List.of(seller.path(), seller.keyPath())) {
            Response response = api.get(path);
            assertEquals(200, response.status(), path + ": " + response.text());
            assertEquals(seller.body(), Json.parse(response.body()), path);
        }
    }

    // Products

    @Given("the product {string} of the seller {string} with the key {string}")
    public void product(String alias, String sellerAlias, String key) throws IOException {
        Created product = createProduct(alias, sellerAlias, key, itemRequest());
        assertProduct(product.response(), 201, product.request(), api.seller(sellerAlias).id(), 2);
    }

    @When("a client creates the product {string} of the seller {string} with the key {string}")
    public void clientCreatesProduct(String alias, String sellerAlias, String key) throws IOException {
        created.clear();
        created.add(createProduct(alias, sellerAlias, key, itemRequest()));
    }

    @When("a client creates the product {string} of the seller {string} with the key {string} and these fields:")
    public void clientCreatesProductWithFields(String alias, String sellerAlias, String key, Map<String, String> fields)
            throws IOException {
        ObjectNode request = itemRequest();
        fields.forEach((field, value) -> {
            if ("initialStock".equals(field)) {
                request.put(field, Integer.parseInt(value));
            } else {
                request.put(field, Placeholders.repeat(value));
            }
        });
        created.clear();
        created.add(createProduct(alias, sellerAlias, key, request));
    }

    private Created createProduct(String alias, String sellerAlias, String key, ObjectNode request) throws IOException {
        Seller seller = api.seller(sellerAlias);
        Response response = api.post(seller.path() + "/products", key, Json.bytes(request));
        if (response.status() == 201 || response.status() == 200) {
            api.remember(alias, seller, key, request, response);
        }
        return new Created(alias, key, request, response);
    }

    @Then("each/the creation returns 201 with the requested product, a new product ID and the stock {int}")
    public void productsCreated(int stock) {
        for (Created product : created) {
            assertProduct(product.response(), 201, product.request(), api.product(product.alias()).seller().id(), stock);
        }
    }

    @Then("the product {string} is stored only on the shard of the seller {string}")
    public void productPlaced(String alias, String sellerAlias) throws SQLException {
        CatalogShards.Shard home = shards.ofSeller(api.seller(sellerAlias).id());
        for (CatalogShards.Shard shard : shards.all()) {
            assertEquals(shard.equals(home) ? "1" : "0",
                    count(shard, "SELECT count(*) FROM catalog.products WHERE product_id = ?", api.product(alias).id()),
                    "Rows of " + alias + " on " + shard.name());
        }
    }

    @Then("a GET by ID and a GET by creation key return the product {string}")
    public void readProduct(String alias) throws IOException {
        Product product = api.product(alias);
        for (String path : List.of(product.path(), product.keyPath())) {
            Response response = api.get(path);
            assertEquals(200, response.status(), path + ": " + response.text());
            assertEquals(product.body(), Json.parse(response.body()), path);
        }
    }

    @Then("the products {string} and {string} have different IDs")
    public void differentProductIds(String first, String second) {
        assertNotEquals(api.product(first).id(), api.product(second).id(), "Parent-scoped product keys collided");
    }

    // Conflicts, missing entities and current state

    @Then("these requests return their error:")
    public void requestsReturnErrors(DataTable table) throws IOException {
        for (Map<String, String> row : table.asMaps()) {
            String path = api.expand(row.get("path"));
            Response response = "GET".equals(row.get("method"))
                    ? api.get(path)
                    : api.post(path, row.get("key"), api.body(row.get("body")));
            assertError(response, Integer.parseInt(row.get("status")), row.get("code"));
        }
    }

    @Then("a GET by ID returns the seller {string} and the product {string} without changes")
    public void unchanged(String sellerAlias, String productAlias) throws IOException {
        assertEquals(api.seller(sellerAlias).body(), Json.parse(api.get(api.seller(sellerAlias).path()).body()));
        assertEquals(api.product(productAlias).body(), Json.parse(api.get(api.product(productAlias).path()).body()));
    }

    @Given("the database sets the stock of the product {string} to {int} and the USD profit of the seller {string} to {string}")
    public void changeCurrentState(String productAlias, int stock, String sellerAlias, String profit) throws SQLException {
        Seller seller = api.seller(sellerAlias);
        try (Connection connection = shards.superuser(shards.ofSeller(seller.id()))) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE catalog.products SET stock = ? WHERE product_id = ?")) {
                statement.setInt(1, stock);
                statement.setLong(2, Long.parseLong(api.product(productAlias).id()));
                assertEquals(1, statement.executeUpdate());
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE catalog.seller_profits SET amount = ?::numeric WHERE seller_id = ? AND currency = 'USD'")) {
                statement.setString(1, profit);
                statement.setLong(2, Long.parseLong(seller.id()));
                assertEquals(1, statement.executeUpdate());
            }
        }
    }

    @When("a client retries the creation of the seller {string}")
    public void retrySeller(String alias) throws IOException, InterruptedException {
        Seller seller = api.seller(alias);
        ObjectNode request = sellerRequest(seller.companyName(), seller.region());
        created.clear();
        created.add(new Created(alias, seller.key(), request, api.postUntilAnswered(SELLERS, seller.key(), Json.bytes(request))));
    }

    @Then("the creation returns 200 with the same seller and the profits USD {string} and EUR {string}")
    public void sellerRecovered(String usd, String eur) {
        Created retry = created.getFirst();
        Seller seller = api.seller(retry.alias());
        assertSeller(retry.response(), 200, seller.companyName(), seller.region(), Map.of("USD", usd, "EUR", eur));
        assertEquals(seller.id(), Json.parse(retry.response().body()).get("sellerId").asText(), "Recovered seller ID");
    }

    @When("a client retries the creation of the product {string} with the initial stock written as {string}")
    public void retryProductWithNumber(String alias, String number) throws IOException {
        Product product = api.product(alias);
        String body = new String(Json.bytes(product.request()), StandardCharsets.UTF_8)
                .replace("\"initialStock\":" + product.request().get("initialStock").intValue(), "\"initialStock\":" + number);
        assertTrue(body.contains("\"initialStock\":" + number), body);
        created.clear();
        created.add(new Created(alias, product.key(), product.request(),
                api.post(product.seller().path() + "/products", product.key(), body.getBytes(StandardCharsets.UTF_8))));
    }

    @Then("the creation returns 200 with the same product and the stock {int}")
    public void productRecovered(int stock) {
        Created retry = created.getFirst();
        Product product = api.product(retry.alias());
        assertProduct(retry.response(), 200, product.request(), product.seller().id(), stock);
        assertEquals(product.id(), Json.parse(retry.response().body()).get("productId").asText(), "Recovered product ID");
    }

    @Then("a retry of the creation of the seller {string} returns 200 with the same seller")
    public void sellerRetry(String alias) throws IOException, InterruptedException {
        Seller seller = api.seller(alias);
        Response retry = api.postUntilAnswered(SELLERS, seller.key(),
                Json.bytes(sellerRequest(seller.companyName(), seller.region())));
        assertEquals(200, retry.status(), retry.text());
        assertEquals(seller.body(), Json.parse(retry.body()), "Retried seller");
    }

    @Then("a retry of the creation of the product {string} returns 200 with the same product")
    public void productRetry(String alias) throws IOException {
        Product product = api.product(alias);
        Response retry = api.post(product.seller().path() + "/products", product.key(), Json.bytes(product.request()));
        assertEquals(200, retry.status(), retry.text());
        assertEquals(product.body(), Json.parse(retry.body()), "Retried product");
    }

    // Concurrency and lost responses

    @When("{int} clients create the seller {string} {string} in {string} with the key {string} at the same time")
    public void concurrentSellers(int count, String alias, String companyName, String region, String key)
            throws InterruptedException, IOException {
        ObjectNode request = sellerRequest(companyName, region);
        List<Response> responses = concurrently(count, () -> api.postUntilAnswered(SELLERS, key, Json.bytes(request)));
        created.clear();
        for (Response response : responses) {
            created.add(new Created(alias, key, request, response));
        }
        api.remember(alias, key, request, responses.getFirst());
    }

    @When("{int} clients create the product {string} of the seller {string} with the key {string} at the same time")
    public void concurrentProducts(int count, String alias, String sellerAlias, String key)
            throws InterruptedException, IOException {
        Seller seller = api.seller(sellerAlias);
        ObjectNode request = itemRequest();
        List<Response> responses = concurrently(count,
                () -> api.postUntilAnswered(seller.path() + "/products", key, Json.bytes(request)));
        created.clear();
        for (Response response : responses) {
            created.add(new Created(alias, key, request, response));
        }
        api.remember(alias, seller, key, request, responses.getFirst());
    }

    @Then("exactly one creation returns 201, and all creations return the same seller/product")
    public void oneCreation() {
        assertEquals(1, created.stream().filter(creation -> creation.response().status() == 201).count(),
                "First creations");
        Set<JsonNode> bodies = new HashSet<>();
        for (Created creation : created) {
            assertTrue(creation.response().status() == 200 || creation.response().status() == 201,
                    creation.response().text());
            bodies.add(Json.parse(creation.response().body()));
        }
        assertEquals(1, bodies.size(), "Concurrent creations returned different entities: " + bodies);
    }

    private List<Response> concurrently(int count, Callable<Response> request) throws InterruptedException, IOException {
        clients = Executors.newFixedThreadPool(count);
        List<Future<Response>> futures = new ArrayList<>();
        for (int client = 0; client < count; client++) {
            futures.add(clients.submit(request));
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(result(future));
        }
        return responses;
    }

    @When("a client sends the creation of the seller {string} {string} in {string} with the key {string} and closes the connection without the response")
    public void dropResponse(String alias, String companyName, String region, String key) throws IOException {
        byte[] body = Json.bytes(sellerRequest(companyName, region));
        try (Socket connection = RawHttp.send(product.port(), "POST", SELLERS, creationHeaders(key), body, body.length)) {
            // The client discards the response: it does not know whether the creation committed.
            connection.shutdownOutput();
        }
        api.remember(alias, key, sellerRequest(companyName, region), unknownOutcome());
    }

    @When("a client sends the creation of the seller {string} {string} in {string} with the key {string} and does not read the response")
    public void unreadResponse(String alias, String companyName, String region, String key) throws IOException {
        byte[] body = Json.bytes(sellerRequest(companyName, region));
        openConnections.add(RawHttp.send(product.port(), "POST", SELLERS, creationHeaders(key), body, body.length));
        api.remember(alias, key, sellerRequest(companyName, region), unknownOutcome());
    }

    /** A placeholder body until a lookup or a retry shows the issued seller. */
    private static Response unknownOutcome() {
        return new Response(0, Map.of(), "{\"sellerId\":\"unknown\"}".getBytes(StandardCharsets.UTF_8), Duration.ZERO);
    }

    @Then("a GET by creation key returns the seller {string}")
    public void lookUpRecoveredSeller(String alias) throws IOException {
        Seller seller = api.seller(alias);
        Response retry = created.getFirst().response();
        assertTrue(retry.status() == 200 || retry.status() == 201, retry.text());
        Response lookup = api.get(seller.keyPath());
        assertEquals(200, lookup.status(), lookup.text());
        assertEquals(Json.parse(retry.body()), Json.parse(lookup.body()), "Lost-response retry and lookup");
        api.remember(alias, seller.key(), sellerRequest(seller.companyName(), seller.region()), lookup);
    }

    @Then("a GET by creation key finds the seller {string} within {int} seconds")
    public void lookUpCommittedSeller(String alias, int seconds) throws IOException, InterruptedException {
        Seller seller = api.seller(alias);
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        Response lookup = api.get(seller.keyPath());
        while (lookup.status() != 200 && System.nanoTime() < deadline) {
            assertTrue(lookup.status() == 404 || lookup.status() == 503, lookup.text());
            Thread.sleep(50);
            lookup = api.get(seller.keyPath());
        }
        assertEquals(200, lookup.status(), "The unread creation never became discoverable: " + lookup.text());
        assertSeller(lookup, 200, seller.companyName(), seller.region(), ZERO_PROFITS);
        api.remember(alias, seller.key(), sellerRequest(seller.companyName(), seller.region()), lookup);
    }

    // Invalid input

    @Then("these seller creation bodies return 400 INVALID_REQUEST:")
    public void invalidSellerBodies(DataTable table) throws IOException {
        for (String body : table.asList().subList(1, table.height())) {
            assertError(api.post(SELLERS, "invalid", api.body(body)), 400, "INVALID_REQUEST");
        }
    }

    @Then("these product creation bodies for the seller {string} return 400 INVALID_REQUEST:")
    public void invalidProductBodies(String alias, DataTable table) throws IOException {
        for (String body : table.asList().subList(1, table.height())) {
            assertError(api.post(api.seller(alias).path() + "/products", "invalid", api.body(body)), 400,
                    "INVALID_REQUEST");
        }
    }

    @Then("these Idempotency-Key headers of a valid seller creation return 400 INVALID_REQUEST:")
    public void invalidKeys(DataTable table) throws IOException {
        byte[] body = Json.bytes(sellerRequest("Valid company", "US"));
        for (String key : table.asList().subList(1, table.height())) {
            List<Header> headers = new ArrayList<>(List.of(new Header("Content-Type", "application/json")));
            switch (key) {
                case "<none>" -> { }
                case "<empty>" -> headers.add(new Header("Idempotency-Key", ""));
                case "<twice>" -> {
                    headers.add(new Header("Idempotency-Key", "duplicate"));
                    headers.add(new Header("Idempotency-Key", "duplicate"));
                }
                default -> headers.add(new Header("Idempotency-Key", Placeholders.repeat(key)));
            }
            assertError(RawHttp.exchange(product.port(), "POST", SELLERS, headers, body, Framing.CONTENT_LENGTH), 400,
                    "INVALID_REQUEST");
        }
    }

    @Then("these Content-Type headers of a valid seller creation return 400 INVALID_REQUEST:")
    public void invalidContentTypes(DataTable table) throws IOException {
        byte[] body = Json.bytes(sellerRequest("Valid company", "US"));
        for (String contentType : table.asList().subList(1, table.height())) {
            List<Header> headers = new ArrayList<>(List.of(new Header("Idempotency-Key", "invalid")));
            if (!"<none>".equals(contentType)) {
                headers.add(new Header("Content-Type", contentType));
            }
            assertError(RawHttp.exchange(product.port(), "POST", SELLERS, headers, body, Framing.CONTENT_LENGTH), 400,
                    "INVALID_REQUEST");
        }
    }

    @Then("these requests return 400 INVALID_REQUEST:")
    public void invalidRequests(DataTable table) throws IOException {
        for (Map<String, String> row : table.asMaps()) {
            String path = api.expand(row.get("path"));
            String body = row.get("body") == null ? "" : row.get("body");
            Response response = switch (body) {
                case "" -> api.get(path);
                case "seller" -> api.post(path, "invalid", Json.bytes(sellerRequest("Valid company", "US")));
                case "<none>" -> RawHttp.exchange(product.port(), "POST", path, creationHeaders("empty-body"),
                        new byte[0], Framing.NONE);
                case "<empty>" -> RawHttp.exchange(product.port(), "POST", path, creationHeaders("empty-body"),
                        new byte[0], Framing.CONTENT_LENGTH);
                case "<empty chunked>" -> RawHttp.exchange(product.port(), "POST", path, creationHeaders("empty-body"),
                        new byte[0], Framing.CHUNKED);
                default -> throw new IllegalArgumentException("Unknown body " + body);
            };
            assertError(response, 400, "INVALID_REQUEST");
        }
    }

    @Then("these seller and product IDs in a path return 400 INVALID_REQUEST:")
    public void invalidIds(DataTable table) throws IOException {
        Seller seller = api.sellers().getFirst();
        for (String id : table.asList().subList(1, table.height())) {
            assertError(api.get(SELLERS + "/" + id), 400, "INVALID_REQUEST");
            assertError(api.get(seller.path() + "/products/" + id), 400, "INVALID_REQUEST");
        }
    }

    @Then("the shards store only the seller {string}")
    public void onlySeller(String alias) throws SQLException {
        int sellers = 0;
        int products = 0;
        for (CatalogShards.Shard shard : shards.all()) {
            sellers += Integer.parseInt(count(shard, "SELECT count(*) FROM catalog.sellers"));
            products += Integer.parseInt(count(shard, "SELECT count(*) FROM catalog.products"));
        }
        assertEquals(List.of(1, 0), List.of(sellers, products), "Stored sellers and products");
        assertEquals("1", count(shards.ofSeller(api.seller(alias).id()),
                "SELECT count(*) FROM catalog.sellers WHERE seller_id = ?", api.seller(alias).id()));
    }

    // Body limits and deadlines

    @Then("these seller creations with a body of more than 32768 bytes return 400 INVALID_REQUEST and store nothing:")
    public void oversizedBodies(DataTable table) throws IOException {
        for (Map<String, String> row : table.asMaps()) {
            String key = row.get("key");
            byte[] body = padded(sellerRequest("Body boundary company", "US"), MAX_BODY_BYTES + 1);
            List<Header> headers = creationHeaders(key);
            Response response = switch (row.get("framing")) {
                case "Content-Length" -> RawHttp.exchange(product.port(), "POST", SELLERS, headers, body,
                        Framing.CONTENT_LENGTH);
                case "chunked" -> RawHttp.exchange(product.port(), "POST", SELLERS, headers, body, Framing.CHUNKED);
                case "Content-Length header only" -> {
                    headers.add(new Header("Content-Length", Integer.toString(MAX_BODY_BYTES + 1)));
                    yield RawHttp.exchange(product.port(), "POST", SELLERS, headers, new byte[0], Framing.NONE);
                }
                default -> throw new IllegalArgumentException("Unknown framing " + row.get("framing"));
            };
            assertError(response, 400, "INVALID_REQUEST");
            assertError(api.get(SELLERS + "/by-key/" + key + "?region=US"), 404, "SELLER_NOT_FOUND");
        }
    }

    @When("a client sends the first byte of the creation body of a US seller with the key {string} and then waits")
    public void slowBody(String key) throws IOException {
        slowBody = Json.bytes(sellerRequest("Incomplete request company", "US"));
        long started = System.nanoTime();
        slowConnection = RawHttp.send(product.port(), "POST", SELLERS, creationHeaders(key), slowBody, 1);
        openConnections.add(slowConnection);
        slowResponse = RawHttp.read(slowConnection, started);
    }

    @Then("product returns 503 CATALOG_UNAVAILABLE in less than {double} seconds")
    public void slowBodyRejected(double seconds) {
        assertError(slowResponse, 503, "CATALOG_UNAVAILABLE");
        assertFaster(slowResponse, Duration.ofMillis((long) (seconds * 1000)));
    }

    @When("the client sends the rest of the body on the same connection")
    public void lateBody() {
        try {
            slowConnection.getOutputStream().write(slowBody, 1, slowBody.length - 1);
            slowConnection.getOutputStream().flush();
        } catch (IOException closed) {
            // Product can also close the expired connection; the client never reconnects with the late body.
        }
    }

    @Then("a GET by the creation key {string} in {string} returns 404 SELLER_NOT_FOUND")
    public void keyNotFound(String key, String region) throws IOException {
        assertError(api.get(SELLERS + "/by-key/" + key + "?region=" + region), 404, "SELLER_NOT_FOUND");
    }

    @Given("a database session holds the creation lock of a US seller key on the shard of the seller {string}")
    public void holdLockOnSellerShard(String alias) throws SQLException {
        CatalogShards.Shard shard = shards.ofSeller(api.seller(alias).id());
        for (int index = 0; index < 100; index++) {
            if (shards.ofKey("US", "locked-" + index).equals(shard)) {
                lock.hold("US", "locked-" + index);
                return;
            }
        }
        fail("No key selects the shard " + shard.name());
    }

    @When("{int} clients create a US seller with that key at the same time")
    public void createWithLockedKey(int count) {
        byte[] body = Json.bytes(sellerRequest("Blocked company", "US"));
        clients = Executors.newFixedThreadPool(count);
        List<Future<Response>> futures = new ArrayList<>();
        for (int client = 0; client < count; client++) {
            futures.add(clients.submit(() -> api.post(SELLERS, lock.key(), body)));
        }
        concurrent = futures;
    }

    @Then("{int} sessions of product wait for that lock")
    public void sessionsWait(int sessions) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        String waiting = "";
        while (System.nanoTime() < deadline) {
            waiting = count(lock.shard(), "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted "
                    + "AND pid IN (SELECT pid FROM pg_stat_activity WHERE usename = 'product_app' AND datname = ?)",
                    lock.shard().database());
            if (Integer.toString(sessions).equals(waiting)) {
                return;
            }
            Thread.sleep(30);
        }
        fail("Sessions of product that wait for the lock: " + waiting);
    }

    @Then("a GET of the seller {string} returns 503 CATALOG_UNAVAILABLE in less than {int} seconds")
    public void sellerUnavailable(String alias, int seconds) throws IOException {
        Response response = api.get(api.seller(alias).path());
        assertError(response, 503, "CATALOG_UNAVAILABLE");
        assertFaster(response, Duration.ofSeconds(seconds));
    }

    @Then("product has at most {int} sessions on that shard")
    public void sessionLimit(int sessions) throws SQLException {
        int open = Integer.parseInt(count(lock.shard(), "SELECT count(*) FROM pg_stat_activity "
                + "WHERE usename = 'product_app' AND datname = ?", lock.shard().database()));
        assertTrue(open <= sessions, "Product sessions on " + lock.shard().name() + ": " + open);
    }

    @Then("both creations return 503 CATALOG_UNAVAILABLE in less than {int} seconds")
    public void blockedCreationsUnavailable(int seconds) {
        for (Future<Response> future : concurrent) {
            Response response = result(future);
            assertError(response, 503, "CATALOG_UNAVAILABLE");
            assertFaster(response, Duration.ofSeconds(seconds));
        }
    }

    @When("the database session releases the lock")
    public void releaseLock() throws SQLException {
        lock.release();
    }

    @Then("a GET by that creation key returns 404 SELLER_NOT_FOUND within {int} seconds")
    public void lockedKeyRecovers(int seconds) throws IOException, InterruptedException {
        String path = SELLERS + "/by-key/" + lock.key() + "?region=" + lock.region();
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        Response response = api.get(path);
        while (response.status() == 503 && System.nanoTime() < deadline) {
            Thread.sleep(200);
            response = api.get(path);
        }
        assertError(response, 404, "SELLER_NOT_FOUND");
    }

    @Then("a retried creation with that key returns 201, and the next retry returns 200 with the same seller")
    public void lockedKeyCreation() throws IOException, InterruptedException {
        byte[] body = Json.bytes(sellerRequest("Blocked company", "US"));
        Response first = api.postUntilAnswered(SELLERS, lock.key(), body);
        assertSeller(first, 201, "Blocked company", "US", ZERO_PROFITS);
        Response second = api.post(SELLERS, lock.key(), body);
        assertEquals(200, second.status(), second.text());
        assertEquals(Json.parse(first.body()), Json.parse(second.body()), "Retry after the timeout");
    }

    @Then("the reads of the seller {string} and the product {string} return 503 {word} in less than {int} seconds")
    public void readsUnavailable(String sellerAlias, String productAlias, String code, int seconds) throws IOException {
        Seller seller = api.seller(sellerAlias);
        Product product = api.product(productAlias);
        for (String path : List.of(seller.path(), seller.keyPath(), product.path(), product.keyPath())) {
            Response response = api.get(path);
            assertError(response, 503, code);
            assertFaster(response, Duration.ofSeconds(seconds));
        }
    }

    private List<Header> creationHeaders(String key) {
        return new ArrayList<>(List.of(new Header("Content-Type", "application/json"), new Header("Idempotency-Key", key)));
    }

    /** The JSON request with trailing spaces up to the given size; JSON permits trailing white space. */
    private static byte[] padded(ObjectNode request, int size) {
        byte[] json = Json.bytes(request);
        byte[] body = new byte[size];
        System.arraycopy(json, 0, body, 0, json.length);
        Arrays.fill(body, json.length, size, (byte) ' ');
        return body;
    }

    private String count(CatalogShards.Shard shard, String sql, String... parameters) throws SQLException {
        try (Connection connection = shards.superuser(shard);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                if (parameters[index].matches("[0-9]+")) {
                    statement.setLong(index + 1, Long.parseLong(parameters[index]));
                } else {
                    statement.setString(index + 1, parameters[index]);
                }
            }
            return SqlRows.single(statement.executeQuery());
        }
    }

    private static <T> T result(Future<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", interrupted);
        } catch (Exception failure) {
            throw new AssertionError("A concurrent request failed", failure);
        }
    }
}
