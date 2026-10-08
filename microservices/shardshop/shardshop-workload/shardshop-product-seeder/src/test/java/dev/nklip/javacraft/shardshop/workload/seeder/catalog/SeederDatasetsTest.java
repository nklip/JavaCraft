package dev.nklip.javacraft.shardshop.workload.seeder.catalog;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeederDatasetsTest {
    private static final Map<String, String> INITIAL_PROFITS = Map.of("USD", "0.00", "EUR", "0.00");
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String SELLER_ID = "9007199254740993";
    private static final String PRODUCT_ID = "9223372036854775807";
    private final HttpClient http = mock(HttpClient.class);
    private final JsonHttpClient product = new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofSeconds(5));
    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final SellerDefinition seller = catalog.sellers().getFirst();
    private final ProductDefinition fixture = catalog.products().getFirst();
    private final SeederDatasets datasets = new SeederDatasets(product, CatalogDataset.VERSION);

    @Test
    void ownsOnlyDefinitionsAndCreatesWithServiceIssuedIds() throws Exception {
        assertEquals(catalog.sellers(), datasets.sellers());
        assertEquals(catalog.products(), datasets.products());
        assertThrows(UnsupportedOperationException.class, () -> datasets.sellers().clear());
        assertThrows(UnsupportedOperationException.class, () -> datasets.products().clear());
        verifyNoInteractions(http);
        reply(sellerResponse());
        SeederDatasets.CreatedSeller createdSeller = datasets.createSeller(seller);
        assertEquals(seller, createdSeller.definition());
        assertEquals(SELLER_ID, createdSeller.sellerId());
        assertEquals(createdSeller, new SeederDatasets(product, CatalogDataset.VERSION).createSeller(seller));
        reply(productResponse());
        SeederDatasets.CreatedProduct createdProduct = datasets.createProduct(fixture, createdSeller);
        assertEquals(fixture, createdProduct.definition());
        assertEquals(SELLER_ID, createdProduct.sellerId());
        assertEquals(PRODUCT_ID, createdProduct.productId());
        assertEquals(0, createdProduct.stock());
        assertEquals(createdProduct, new SeederDatasets(product, CatalogDataset.VERSION).createProduct(fixture, createdSeller));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(4)).sendAsync(requests.capture(), any());
        HttpRequest sellerRequest = requests.getAllValues().getFirst();
        assertEquals("POST", sellerRequest.method());
        assertEquals(URI.create("http://product:8080/api/v1/sellers"), sellerRequest.uri());
        assertEquals(seller.key(), sellerRequest.headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals(sellerPayload(), json.readTree(RequestBodies.of(sellerRequest)));
        assertEquals(RequestBodies.of(sellerRequest), RequestBodies.of(requests.getAllValues().get(1)));
        assertEquals(sellerRequest.headers(), requests.getAllValues().get(1).headers());
        HttpRequest productRequest = requests.getAllValues().get(2);
        assertEquals("POST", productRequest.method());
        assertEquals(URI.create("http://product:8080/api/v1/sellers/" + SELLER_ID + "/products"), productRequest.uri());
        assertEquals(fixture.key(), productRequest.headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals(productPayload(), json.readTree(RequestBodies.of(productRequest)));
        assertEquals(RequestBodies.of(productRequest), RequestBodies.of(requests.getAllValues().getLast()));
        assertEquals(productRequest.headers(), requests.getAllValues().getLast().headers());
    }

    @Test
    void readsAndLooksUpAcknowledgedEntitiesWithoutSendingBodies() throws Exception {
        assertEquals(CatalogDataset.VERSION, datasets.catalogVersion());
        assertEquals(catalog.productsBySeller().get(seller.key()), datasets.productsOf(seller));
        assertEquals(List.of("USD", "EUR"), datasets.productsOf(seller).stream().map(ProductDefinition::currency).toList());
        SeederDatasets.CreatedSeller createdSeller = new SeederDatasets.CreatedSeller(seller, SELLER_ID, INITIAL_PROFITS);
        reply(sellerResponse());
        assertEquals(createdSeller, datasets.readSeller(createdSeller));
        assertEquals(createdSeller, datasets.lookupSeller(seller));
        SeederDatasets.CreatedProduct createdProduct = new SeederDatasets.CreatedProduct(fixture, SELLER_ID, PRODUCT_ID, 0);
        reply(productResponse());
        assertEquals(createdProduct, datasets.readProduct(createdProduct));
        assertEquals(createdProduct, datasets.lookupProduct(fixture, createdSeller));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(4)).sendAsync(requests.capture(), any());
        assertEquals(List.of("http://product:8080/api/v1/sellers/" + SELLER_ID,
                        "http://product:8080/api/v1/sellers/by-key/" + seller.key() + "?region=US",
                        "http://product:8080/api/v1/sellers/" + SELLER_ID + "/products/" + PRODUCT_ID,
                        "http://product:8080/api/v1/sellers/" + SELLER_ID + "/products/by-key/" + fixture.key()),
                requests.getAllValues().stream().map(request -> request.uri().toString()).toList());
        for (HttpRequest request : requests.getAllValues()) {
            assertEquals("GET", request.method());
            assertTrue(request.bodyPublisher().isEmpty());
            assertTrue(request.headers().firstValue("Idempotency-Key").isEmpty());
        }
    }

    @Test
    void rejectsReadResponsesForAnotherAcknowledgedIdentity() {
        reply(productResponse().put("sellerId", "1"));
        SeederDatasets.CreatedProduct createdProduct = new SeederDatasets.CreatedProduct(fixture, SELLER_ID, PRODUCT_ID, 0);
        assertThrows(IllegalArgumentException.class, () -> datasets.readProduct(createdProduct));
        reply(sellerResponse().put("region", "EU"));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(seller));
    }

    @Test
    void refusesForeignReadsAndInvalidIdsBeforeSending() {
        SellerDefinition unknownSeller = new SellerDefinition("foreign", seller.companyName(), seller.region());
        ProductDefinition unknownProduct = new ProductDefinition("foreign", seller.key(), fixture.name(),
                fixture.description(), fixture.price(), fixture.unitCost(), fixture.currency(), fixture.initialStock());
        assertThrows(IllegalArgumentException.class, () -> datasets.productsOf(unknownSeller));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(unknownSeller));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readSeller(new SeederDatasets.CreatedSeller(unknownSeller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readSeller(new SeederDatasets.CreatedSeller(seller, "0", INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readProduct(new SeederDatasets.CreatedProduct(unknownProduct, SELLER_ID, PRODUCT_ID, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readProduct(new SeederDatasets.CreatedProduct(fixture, " " + SELLER_ID, PRODUCT_ID, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readProduct(new SeederDatasets.CreatedProduct(fixture, SELLER_ID, "9223372036854775808", 0)));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupProduct(fixture,
                new SeederDatasets.CreatedSeller(catalog.sellers().get(1), SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupProduct(fixture,
                new SeederDatasets.CreatedSeller(seller, "-1", INITIAL_PROFITS)));
        verifyNoInteractions(http);
    }

    @Test
    void preservesDynamicProfitsAndSnapshotsTheReturnedMap() throws Exception {
        ObjectNode response = sellerResponse();
        response.set("profitsEarned", JsonNodeFactory.instance.objectNode()
                .put("USD", "99999999999999999.99").put("EUR", "-0.01").put("GBP", "42.00"));
        reply(response);
        SeederDatasets.CreatedSeller result = datasets.createSeller(seller);
        assertEquals(Map.of("USD", "99999999999999999.99", "EUR", "-0.01", "GBP", "42.00"), result.profitsEarned());
        assertThrows(UnsupportedOperationException.class, () -> result.profitsEarned().put("USD", "1.00"));
        Map<String, String> supplied = new HashMap<>(INITIAL_PROFITS);
        SeederDatasets.CreatedSeller snapshot = new SeederDatasets.CreatedSeller(seller, SELLER_ID, supplied);
        assertEquals("0.00", supplied.put("USD", "3.00"));
        assertEquals(INITIAL_PROFITS, snapshot.profitsEarned());
        reply(sellerResponse());
        SeederDatasets.CreatedSeller replayed = datasets.createSeller(seller);
        assertEquals(result.sellerId(), replayed.sellerId());
        assertEquals(result.definition(), replayed.definition());
        assertEquals(INITIAL_PROFITS, replayed.profitsEarned());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"USD\":\"0.00\"}", "{\"USD\":\"-0.00\",\"EUR\":\"0.00\"}",
            "{\"USD\":\"0.00\",\"EUR\":\"0.00\",\"usd\":\"0.00\"}"})
    void rejectsInvalidProfitSnapshots(String profits) throws Exception {
        ObjectNode response = sellerResponse();
        response.set("profitsEarned", json.readTree(profits));
        reply(response);
        assertThrows(IllegalArgumentException.class, () -> datasets.createSeller(seller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sellerId", "companyName", "region", "profitsEarned", "extra"})
    void rejectsInvalidOrMismatchedSellerResponses(String field) {
        reply(sellerResponse().put(field, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> datasets.createSeller(seller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sellerId", "productId", "name", "description", "price", "unitCost", "currency", "initialStock", "stock", "extra"})
    void rejectsInvalidOrMismatchedProductResponses(String field) {
        ObjectNode response = productResponse();
        if (field.equals("sellerId")) {
            response.put(field, "1");
        } else if (field.equals("initialStock") || field.equals("stock")) {
            response.put(field, fixture.initialStock() + 1);
        } else {
            response.put(field, "invalid");
        }
        reply(response);
        assertThrows(IllegalArgumentException.class,
                () -> datasets.createProduct(fixture, new SeederDatasets.CreatedSeller(seller, SELLER_ID, INITIAL_PROFITS)));
    }

    @Test
    void refusesForeignDefinitionsWrongParentsAndInvalidParentIdsBeforeSending() {
        SellerDefinition unknownSeller = new SellerDefinition("foreign", seller.companyName(), seller.region());
        ProductDefinition unknownProduct = new ProductDefinition("foreign", seller.key(), fixture.name(),
                fixture.description(), fixture.price(), fixture.unitCost(), fixture.currency(), fixture.initialStock());
        assertThrows(IllegalArgumentException.class, () -> datasets.createSeller(unknownSeller));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.createProduct(unknownProduct, new SeederDatasets.CreatedSeller(seller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.createProduct(fixture, new SeederDatasets.CreatedSeller(unknownSeller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.createProduct(fixture, new SeederDatasets.CreatedSeller(catalog.sellers().get(1), SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.createProduct(fixture, new SeederDatasets.CreatedSeller(seller, "01", INITIAL_PROFITS)));
        verifyNoInteractions(http);
    }
    private ObjectNode sellerPayload() {
        return JsonNodeFactory.instance.objectNode().put("companyName", seller.companyName()).put("region", seller.region());
    }

    private ObjectNode sellerResponse() {
        ObjectNode response = sellerPayload().put("sellerId", SELLER_ID);
        response.set("profitsEarned", JsonNodeFactory.instance.objectNode().put("USD", "0.00").put("EUR", "0.00"));
        return response;
    }

    private ObjectNode productPayload() {
        return JsonNodeFactory.instance.objectNode().put("name", fixture.name()).put("description", fixture.description())
                .put("price", fixture.price()).put("unitCost", fixture.unitCost()).put("currency", fixture.currency())
                .put("initialStock", fixture.initialStock());
    }

    private ObjectNode productResponse() {
        return productPayload().put("sellerId", SELLER_ID).put("productId", PRODUCT_ID).put("stock", 0);
    }

    private void reply(ObjectNode body) {
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body.toString().getBytes(StandardCharsets.UTF_8));
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(response));
    }
}
