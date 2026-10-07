package dev.nklip.javacraft.shardshop.workload.producer;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset.BuyerDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProducerDatasetsTest {
    private static final Map<String, String> INITIAL_PROFITS = Map.of("USD", "0.00", "EUR", "0.00");
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String SELLER_ID = "9007199254740993";
    private static final String PRODUCT_ID = "9223372036854775807";
    private final HttpClient http = mock(HttpClient.class);
    private final JsonHttpClient product = new JsonHttpClient(http, URI.create("http://product:8080"), Duration.ofSeconds(5));
    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final SellerDefinition seller = catalog.sellers().getFirst();
    private final ProductDefinition fixture = catalog.products().getFirst();
    private final JsonHttpClient order = new JsonHttpClient(http, URI.create("http://order:8081"), Duration.ofSeconds(4));
    private final BuyerDefinition buyer = new BuyerDataset(BuyerDataset.VERSION).buyers().getFirst();
    private final ProducerDatasets datasets = new ProducerDatasets(product, CatalogDataset.VERSION, order, BuyerDataset.VERSION);

    @Test
    void resolvesTheSameDefinitionsAfterRestartUsingOnlyReadRequests() throws Exception {
        assertEquals(catalog.sellers(), datasets.sellers());
        assertEquals(catalog.products(), datasets.products());
        assertThrows(UnsupportedOperationException.class, () -> datasets.products().clear());
        verifyNoInteractions(http);
        reply(sellerResponse());
        ProducerDatasets.SellerDescriptor resolvedSeller = datasets.lookupSeller(seller);
        assertEquals(seller, resolvedSeller.definition());
        assertEquals(SELLER_ID, resolvedSeller.sellerId());
        assertEquals(resolvedSeller, new ProducerDatasets(product, CatalogDataset.VERSION, order, BuyerDataset.VERSION).lookupSeller(seller));
        reply(productResponse());
        ProducerDatasets.ProductDescriptor resolvedProduct = datasets.lookupProduct(fixture, resolvedSeller);
        assertEquals(fixture, resolvedProduct.definition());
        assertEquals(SELLER_ID, resolvedProduct.sellerId());
        assertEquals(PRODUCT_ID, resolvedProduct.productId());
        assertEquals(0, resolvedProduct.stock());
        assertEquals(resolvedProduct,
                new ProducerDatasets(product, CatalogDataset.VERSION, order, BuyerDataset.VERSION).lookupProduct(fixture, resolvedSeller));
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
        ProducerDatasets.SellerDescriptor result = datasets.lookupSeller(seller);
        assertEquals(Map.of("USD", "99999999999999999.99", "EUR", "-0.01", "GBP", "42.00"), result.profitsEarned());
        assertThrows(UnsupportedOperationException.class, () -> result.profitsEarned().put("USD", "1.00"));
        Map<String, String> supplied = new HashMap<>(INITIAL_PROFITS);
        ProducerDatasets.SellerDescriptor snapshot = new ProducerDatasets.SellerDescriptor(seller, SELLER_ID, supplied);
        assertEquals("0.00", supplied.put("USD", "3.00"));
        assertEquals(INITIAL_PROFITS, snapshot.profitsEarned());
        reply(sellerResponse());
        ProducerDatasets.SellerDescriptor replayed = datasets.lookupSeller(seller);
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
                () -> datasets.lookupProduct(fixture, new ProducerDatasets.SellerDescriptor(seller, SELLER_ID, INITIAL_PROFITS)));
    }

    @Test
    void refusesForeignDefinitionsWrongParentsAndInvalidParentIdsBeforeLookup() {
        SellerDefinition unknownSeller = new SellerDefinition("foreign", seller.companyName(), seller.region());
        ProductDefinition unknownProduct = new ProductDefinition("foreign", seller.key(), fixture.name(),
                fixture.description(), fixture.price(), fixture.unitCost(), fixture.currency(), fixture.initialStock());
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(unknownSeller));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(unknownProduct, new ProducerDatasets.SellerDescriptor(seller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, new ProducerDatasets.SellerDescriptor(unknownSeller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, new ProducerDatasets.SellerDescriptor(catalog.sellers().get(1), SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, new ProducerDatasets.SellerDescriptor(seller, "01", INITIAL_PROFITS)));
        verifyNoInteractions(http);
    }
    @Test
    void createsABuyerWithNoLocalIdAndReplaysTheOriginalKeyAndPayload() throws Exception {
        assertEquals(new BuyerDataset(BuyerDataset.VERSION).buyers(), datasets.buyers());
        assertThrows(UnsupportedOperationException.class, () -> datasets.buyers().clear());
        verifyNoInteractions(http);
        reply(buyerResponse());
        ProducerDatasets.CreatedBuyer created = datasets.createBuyer(buyer);
        assertEquals(buyer, created.definition());
        assertEquals(PRODUCT_ID, created.buyerId());
        assertEquals(created, new ProducerDatasets(product, CatalogDataset.VERSION, order, BuyerDataset.VERSION).createBuyer(buyer));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).sendAsync(requests.capture(), any());
        HttpRequest request = requests.getAllValues().getFirst();
        assertEquals("POST", request.method());
        assertEquals(URI.create("http://order:8081/api/v1/buyers"), request.uri());
        assertEquals(Duration.ofSeconds(4), request.timeout().orElseThrow());
        assertEquals(buyer.key(), request.headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals(buyerPayload(), json.readTree(body(request)));
        assertEquals(body(request), body(requests.getAllValues().getLast()));
        assertEquals(request.headers(), requests.getAllValues().getLast().headers());
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyerId", "firstName", "surname", "email", "phone", "address", "region", "extra"})
    void rejectsInvalidOrMismatchedBuyerResponses(String field) {
        reply(buyerResponse().put(field, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> datasets.createBuyer(buyer));
    }

    @ParameterizedTest
    @ValueSource(strings = {"line1", "city", "postalCode", "countryCode", "extra"})
    void rejectsMismatchedOrUnknownAddressFields(String field) {
        ObjectNode response = buyerResponse();
        ((ObjectNode) response.get("address")).put(field, "invalid");
        reply(response);
        assertThrows(IllegalArgumentException.class, () -> datasets.createBuyer(buyer));
    }

    @Test
    void refusesForeignBuyerDefinitionsBeforeSending() {
        assertThrows(IllegalArgumentException.class,
                () -> datasets.createBuyer(new BuyerDefinition("foreign", buyer.firstName(), buyer.surname(), buyer.email(),
                        buyer.phone(), buyer.address(), buyer.region())));
        verifyNoInteractions(http);
    }

    private ObjectNode buyerPayload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("firstName", buyer.firstName())
                .put("surname", buyer.surname()).put("email", buyer.email()).put("phone", buyer.phone())
                .put("region", buyer.region());
        payload.set("address", JsonNodeFactory.instance.objectNode().put("line1", buyer.address().line1())
                .put("city", buyer.address().city()).put("postalCode", buyer.address().postalCode())
                .put("countryCode", buyer.address().countryCode()));
        return payload;
    }

    private ObjectNode buyerResponse() {
        return buyerPayload().put("buyerId", PRODUCT_ID);
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

    private static String body(HttpRequest request) {
        Flow.Subscriber<ByteBuffer> subscriber = mock();
        doAnswer(invocation -> {
            Flow.Subscription subscription = invocation.getArgument(0);
            subscription.request(Long.MAX_VALUE);
            return null;
        }).when(subscriber).onSubscribe(any());
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        ArgumentCaptor<ByteBuffer> bytes = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(subscriber).onNext(bytes.capture());
        verify(subscriber).onComplete();
        return StandardCharsets.UTF_8.decode(bytes.getValue()).toString();
    }
}
