package dev.nklip.javacraft.shardshop.workload.reader;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReaderDatasetsTest {
    private static final Map<String, String> INITIAL_PROFITS = Map.of("USD", "0.00", "EUR", "0.00");
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String SELLER_ID = "9007199254740993";
    private static final String PRODUCT_ID = "9223372036854775807";
    private final HttpClient http = mock(HttpClient.class);
    private final JsonHttpClient product = new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofSeconds(5));
    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final SellerDefinition seller = catalog.sellers().getFirst();
    private final ProductDefinition fixture = catalog.products().getFirst();
    private final ReaderDatasets datasets = new ReaderDatasets(product, CatalogDataset.VERSION);

    @Test
    void resolvesTheSameDefinitionsAfterRestartUsingOnlyReadRequests() throws Exception {
        assertEquals(catalog.sellers(), datasets.sellers());
        assertEquals(catalog.products(), datasets.products());
        assertThrows(UnsupportedOperationException.class, () -> datasets.products().clear());
        verifyNoInteractions(http);
        reply(sellerResponse());
        ReaderDatasets.SellerDescriptor resolvedSeller = datasets.lookupSeller(seller);
        assertEquals(seller, resolvedSeller.definition());
        assertEquals(SELLER_ID, resolvedSeller.sellerId());
        assertEquals(resolvedSeller, new ReaderDatasets(product, CatalogDataset.VERSION).lookupSeller(seller));
        reply(productResponse());
        ReaderDatasets.ProductDescriptor resolvedProduct = datasets.lookupProduct(fixture, resolvedSeller);
        assertEquals(fixture, resolvedProduct.definition());
        assertEquals(SELLER_ID, resolvedProduct.sellerId());
        assertEquals(PRODUCT_ID, resolvedProduct.productId());
        assertEquals(0, resolvedProduct.stock());
        assertEquals(resolvedProduct,
                new ReaderDatasets(product, CatalogDataset.VERSION).lookupProduct(fixture, resolvedSeller));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(4)).sendAsync(requests.capture(), any());
        HttpRequest sellerRequest = requests.getAllValues().getFirst();
        assertEquals("GET", sellerRequest.method());
        assertEquals(URI.create("http://product:8080/api/v1/sellers/by-key/" + seller.key() + "?region=" + seller.region()),
                sellerRequest.uri());
        assertEquals(sellerRequest.uri(), requests.getAllValues().get(1).uri());
        assertFalse(sellerRequest.bodyPublisher().isPresent());
        HttpRequest productRequest = requests.getAllValues().get(2);
        assertEquals("GET", productRequest.method());
        assertEquals(URI.create("http://product:8080/api/v1/sellers/" + SELLER_ID + "/products/by-key/" + fixture.key()),
                productRequest.uri());
        assertEquals(productRequest.uri(), requests.getAllValues().getLast().uri());
        assertFalse(productRequest.bodyPublisher().isPresent());
    }

    @Test
    void preservesDynamicProfitsAndSnapshotsTheReturnedMap() throws Exception {
        ObjectNode response = sellerResponse();
        response.set("profitsEarned", JsonNodeFactory.instance.objectNode()
                .put("USD", "99999999999999999.99").put("EUR", "-0.01").put("GBP", "42.00"));
        reply(response);
        ReaderDatasets.SellerDescriptor result = datasets.lookupSeller(seller);
        assertEquals(Map.of("USD", "99999999999999999.99", "EUR", "-0.01", "GBP", "42.00"), result.profitsEarned());
        assertThrows(UnsupportedOperationException.class, () -> result.profitsEarned().put("USD", "1.00"));
        Map<String, String> supplied = new HashMap<>(INITIAL_PROFITS);
        ReaderDatasets.SellerDescriptor snapshot = new ReaderDatasets.SellerDescriptor(seller, SELLER_ID, supplied);
        assertEquals("0.00", supplied.put("USD", "3.00"));
        assertEquals(INITIAL_PROFITS, snapshot.profitsEarned());
        reply(sellerResponse());
        ReaderDatasets.SellerDescriptor replayed = datasets.lookupSeller(seller);
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
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(seller));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sellerId", "companyName", "region", "profitsEarned", "extra"})
    void rejectsInvalidOrMismatchedSellerResponses(String field) {
        reply(sellerResponse().put(field, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(seller));
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
                () -> datasets.lookupProduct(fixture, new ReaderDatasets.SellerDescriptor(seller, SELLER_ID, INITIAL_PROFITS)));
    }

    @Test
    void refusesForeignDefinitionsWrongParentsAndInvalidParentIdsBeforeLookup() {
        SellerDefinition unknownSeller = new SellerDefinition("foreign", seller.companyName(), seller.region());
        ProductDefinition unknownProduct = new ProductDefinition("foreign", seller.key(), fixture.name(),
                fixture.description(), fixture.price(), fixture.unitCost(), fixture.currency(), fixture.initialStock());
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(unknownSeller));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(unknownProduct, new ReaderDatasets.SellerDescriptor(seller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, new ReaderDatasets.SellerDescriptor(unknownSeller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, new ReaderDatasets.SellerDescriptor(catalog.sellers().get(1), SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, new ReaderDatasets.SellerDescriptor(seller, "01", INITIAL_PROFITS)));
        verifyNoInteractions(http);
    }
    @Test
    void readsUsingExactIssuedIdsAndRejectsInvalidIds() {
        HttpRequest request = datasets.readProduct(SELLER_ID, PRODUCT_ID);
        assertEquals("GET", request.method());
        assertEquals(URI.create("http://product:8080/api/v1/sellers/" + SELLER_ID + "/products/" + PRODUCT_ID), request.uri());
        assertEquals(Duration.ofSeconds(5), request.timeout().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> datasets.readProduct("01", PRODUCT_ID));
        assertThrows(IllegalArgumentException.class, () -> datasets.readProduct(SELLER_ID, "0"));
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
