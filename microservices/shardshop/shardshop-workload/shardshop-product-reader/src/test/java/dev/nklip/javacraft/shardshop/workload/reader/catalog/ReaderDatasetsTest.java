package dev.nklip.javacraft.shardshop.workload.reader.catalog;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets.ProductDescriptor;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets.SellerDescriptor;
import dev.nklip.javacraft.shardshop.workload.reader.http.Read;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReaderDatasetsTest {
    private static final Map<String, String> INITIAL_PROFITS = Map.of("USD", "0.00", "EUR", "0.00");
    private static final String SELLER_ID = "9007199254740993";
    private static final String PRODUCT_ID = "9223372036854775807";
    private final JsonMapper json = JsonMapper.builder().build();
    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final SellerDefinition seller = catalog.sellers().getFirst();
    private final ProductDefinition fixture = catalog.products().getFirst();
    private final ReaderDatasets datasets = new ReaderDatasets(CatalogDataset.VERSION);
    private final SellerDescriptor resolvedSeller = new SellerDescriptor(seller, SELLER_ID, INITIAL_PROFITS);

    @Test
    void resolvesTheSameDefinitionsByCreationKeyAndKeepsTheIssuedIds() {
        assertEquals(CatalogDataset.VERSION, datasets.catalogVersion());
        assertEquals(catalog.sellers(), datasets.sellers());
        assertEquals(catalog.products(), datasets.products());
        assertThrows(UnsupportedOperationException.class, () -> datasets.products().clear());
        Read<SellerDescriptor> sellerLookup = datasets.lookupSeller(seller);
        assertEquals("/api/v1/sellers/by-key/" + seller.key() + "?region=" + seller.region(), sellerLookup.path());
        SellerDescriptor sellerResult = sellerLookup.decoder().apply(sellerResponse());
        assertEquals(resolvedSeller, sellerResult);
        assertEquals(sellerResult,
                new ReaderDatasets(CatalogDataset.VERSION).lookupSeller(seller).decoder().apply(sellerResponse()));
        Read<ProductDescriptor> productLookup = datasets.lookupProduct(fixture, sellerResult);
        assertEquals("/api/v1/sellers/" + SELLER_ID + "/products/by-key/" + fixture.key(), productLookup.path());
        assertEquals(new ProductDescriptor(fixture, SELLER_ID, PRODUCT_ID, 0),
                productLookup.decoder().apply(productResponse()));
        // A lookup accepts any issued product ID; only product generates IDs.
        assertEquals("1", productLookup.decoder().apply(productResponse().put("productId", "1")).productId());
    }

    @Test
    void readsAResolvedProductByItsIssuedIdsAndRequiresTheSameIdsAndPayload() {
        Read<ProductDescriptor> read = datasets.readProduct(new ProductDescriptor(fixture, SELLER_ID, PRODUCT_ID, 0));
        assertEquals("/api/v1/sellers/" + SELLER_ID + "/products/" + PRODUCT_ID, read.path());
        assertEquals(new ProductDescriptor(fixture, SELLER_ID, PRODUCT_ID, 7),
                read.decoder().apply(productResponse().put("stock", 7)));
        assertThrows(IllegalArgumentException.class,
                () -> read.decoder().apply(productResponse().put("productId", SELLER_ID)));
        assertThrows(IllegalArgumentException.class, () -> read.decoder().apply(productResponse().put("sellerId", "1")));
        assertThrows(IllegalArgumentException.class, () -> read.decoder().apply(productResponse().put("price", "1.00")));
        assertThrows(NullPointerException.class, () -> new Read<>(null, node -> node));
        assertThrows(NullPointerException.class, () -> new Read<>("/path", null));
    }

    @Test
    void preservesDynamicProfitsAndSnapshotsTheReturnedMap() {
        ObjectNode response = sellerResponse();
        response.set("profitsEarned", JsonNodeFactory.instance.objectNode()
                .put("USD", "99999999999999999.99").put("EUR", "-0.01").put("GBP", "42.00"));
        SellerDescriptor result = datasets.lookupSeller(seller).decoder().apply(response);
        assertEquals(Map.of("USD", "99999999999999999.99", "EUR", "-0.01", "GBP", "42.00"), result.profitsEarned());
        assertThrows(UnsupportedOperationException.class, () -> result.profitsEarned().put("USD", "1.00"));
        Map<String, String> supplied = new HashMap<>(INITIAL_PROFITS);
        SellerDescriptor snapshot = new SellerDescriptor(seller, SELLER_ID, supplied);
        assertEquals("0.00", supplied.put("USD", "3.00"));
        assertEquals(INITIAL_PROFITS, snapshot.profitsEarned());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"USD\":\"0.00\"}", "{\"USD\":\"-0.00\",\"EUR\":\"0.00\"}",
            "{\"USD\":\"0.00\",\"EUR\":\"0.00\",\"usd\":\"0.00\"}"})
    void rejectsInvalidProfitSnapshots(String profits) throws Exception {
        ObjectNode response = sellerResponse();
        response.set("profitsEarned", json.readTree(profits));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(seller).decoder().apply(response));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sellerId", "companyName", "region", "profitsEarned", "extra"})
    void rejectsInvalidOrMismatchedSellerResponses(String field) {
        ObjectNode response = sellerResponse().put(field, "invalid");
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(seller).decoder().apply(response));
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
        assertThrows(IllegalArgumentException.class,
                () -> datasets.lookupProduct(fixture, resolvedSeller).decoder().apply(response));
        assertThrows(IllegalArgumentException.class, () -> datasets.readProduct(
                new ProductDescriptor(fixture, SELLER_ID, PRODUCT_ID, 0)).decoder().apply(response));
    }

    @Test
    void refusesForeignDefinitionsWrongParentsAndInvalidIdsBeforeAnyRequest() {
        SellerDefinition unknownSeller = new SellerDefinition("foreign", seller.companyName(), seller.region());
        ProductDefinition unknownProduct = new ProductDefinition("foreign", seller.key(), fixture.name(),
                fixture.description(), fixture.price(), fixture.unitCost(), fixture.currency(), fixture.initialStock());
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupSeller(unknownSeller));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupProduct(unknownProduct, resolvedSeller));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupProduct(fixture,
                new SellerDescriptor(unknownSeller, SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupProduct(fixture,
                new SellerDescriptor(catalog.sellers().get(1), SELLER_ID, INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class, () -> datasets.lookupProduct(fixture,
                new SellerDescriptor(seller, "01", INITIAL_PROFITS)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readProduct(new ProductDescriptor(unknownProduct, SELLER_ID, PRODUCT_ID, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readProduct(new ProductDescriptor(fixture, "01", PRODUCT_ID, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> datasets.readProduct(new ProductDescriptor(fixture, SELLER_ID, "0", 0)));
    }

    private ObjectNode sellerResponse() {
        ObjectNode response = JsonNodeFactory.instance.objectNode().put("companyName", seller.companyName())
                .put("region", seller.region()).put("sellerId", SELLER_ID);
        response.set("profitsEarned", JsonNodeFactory.instance.objectNode().put("USD", "0.00").put("EUR", "0.00"));
        return response;
    }

    private ObjectNode productResponse() {
        return JsonNodeFactory.instance.objectNode().put("name", fixture.name()).put("description", fixture.description())
                .put("price", fixture.price()).put("unitCost", fixture.unitCost()).put("currency", fixture.currency())
                .put("initialStock", fixture.initialStock()).put("sellerId", SELLER_ID).put("productId", PRODUCT_ID)
                .put("stock", 0);
    }
}
