package dev.nklip.javacraft.shardshop.product;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument.Example;
import dev.nklip.javacraft.shardshop.common.contract.ExampleIds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.invalidVariants;
import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.json;
import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The product OpenAPI document: its structure, its examples and the boundaries of its schemas. */
class ProductApiContractTest {
    private static final ContractDocument API = ContractDocument.openApi(
            Path.of("src/main/resources/contracts/openapi.yaml"));
    private static final String SCHEMAS = "#/components/schemas/";
    private static final String LONG_MAX = "9223372036854775807";
    private static final JsonObject SELLER = new JsonObject().put("companyName", "Example Company").put("region", "EU");
    private static final JsonObject PRODUCT = new JsonObject().put("name", "Product")
            .put("description", "A reusable travel mug.").put("price", "19.95").put("unitCost", "12.45")
            .put("currency", "USD").put("initialStock", 10);
    private static final JsonObject PROFITS = new JsonObject().put("USD", "0.00").put("EUR", "15.00");

    @Test
    void isAValidSelfContainedOpenApiDocument() {
        assertEquals(List.of(), API.structureErrors());
    }

    @Test
    void everyExampleMatchesItsSchema() {
        List<Example> examples = API.examples();
        assertFalse(examples.isEmpty(), "The document has inline examples");
        for (Example example : examples) {
            assertEquals(List.of(), API.errors(example.schema(), example.value()), example.location());
        }
    }

    @Test
    void everyResponseBodyHasAnExample() {
        assertEquals(List.of(), API.responsesWithoutExample());
    }

    @Test
    void idsAreCanonicalSignedLongText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Id",
                List.of("1", "9007199254740993", "9223372036854775806", LONG_MAX),
                List.of(1, "0", "01", "+1", "-1", "1.0", "1e3", "1\n", "1\r\n", "\t1", "１", "9223372036854775808",
                        "9999999999999999999")));
    }

    @Test
    void pricesAreFixedTwoDecimalText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Price",
                List.of("0.00", "19.95", "99999999999999999.99"),
                List.of(0, "-0.00", "+1.00", "01.00", "1", "1.0", "1.000", "1e2", "1.00\n", "100000000000000000.00")));
    }

    @Test
    void namesAndDescriptionsAreNonblankUnicodeScalarText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Name",
                List.of("A", " Café ", "🚀", "A".repeat(200)),
                List.of("", " \t\n", "A".repeat(201), "A\u0000", "\ud800", "A\udfff", "\udc00\ud800")));
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Description",
                List.of("A", " Café \n", "🚀", "A".repeat(2000)),
                Arrays.asList(null, 123, "", " \t\n", "A".repeat(2001), "A\u0000", "\ud800", "A\udfff")));
    }

    @Test
    void stockIsAnExactNonnegativeInt32() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Stock",
                List.of(0, json("2"), json("2.0"), json("2e0"), 2147483647),
                List.of(-1, new BigDecimal("2.5"), new BigDecimal("2.000000000000000001"),
                        new BigDecimal("2147483647.000000001"), "2", true, 2147483648L)));
    }

    @Test
    void profitsAreSignedFixedTwoDecimalTextForEachCurrency() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "ProfitAmount",
                List.of("0.00", "15.00", "-0.01", "-99999999999999999.99", "99999999999999999.99"),
                List.of(0, "-0.00", "+1.00", "01.00", "-01.00", "1", "1.0", "1.000", "1e2", "1.00\n", "1.00\r\n",
                        "100000000000000000.00", "-100000000000000000.00")));
        assertEquals(List.of(), API.unexpected(SCHEMAS + "ProfitsEarned",
                List.of(PROFITS, PROFITS.copy().put("JPY", "-1.00")),
                List.of(new JsonObject(), new JsonObject().put("USD", "0.00"), new JsonObject().put("EUR", "0.00"),
                        new JsonArray(), PROFITS.copy().put("usd", "1.00"), PROFITS.copy().put("EUR", "-0.00"),
                        PROFITS.copy().put("US", "1.00"), PROFITS.copy().put("EUR\n", "1.00"),
                        PROFITS.copy().put("USD", 0))));
        assertEquals(Boolean.TRUE, ((JsonObject) API.node(SCHEMAS + "ProfitsEarned")).getValue("readOnly"),
                "Seller profit balances are read-only");
    }

    @Test
    void creationRequestsHaveExactlyTheirFields() {
        JsonObject sellerFields = new JsonObject().put("sellerId", "1").put("name", "Old seller")
                .put("profitsEarned", new JsonObject().put("USD", "0.00").put("EUR", "0.00"));
        assertEquals(List.of(), API.unexpected(SCHEMAS + "CreateSeller",
                List.of(SELLER, SELLER.copy().put("companyName", " Société Exemple ")),
                concat(invalidVariants(SELLER, sellerFields), Stream.of(123, "", " \t", "A".repeat(201))
                        .map(name -> (Object) SELLER.copy().put("companyName", name)).toList(),
                        List.of(SELLER.copy().put("region", "UNKNOWN")))));
        JsonObject productFields = new JsonObject().put("productId", "1").put("sellerId", "1").put("region", "US")
                .put("stock", 10);
        assertEquals(List.of(), API.unexpected(SCHEMAS + "CreateProduct",
                List.of(PRODUCT, PRODUCT.copy().put("unitCost", "20.00")),
                concat(invalidVariants(PRODUCT, productFields), List.of(PRODUCT.copy().put("description", " "),
                        PRODUCT.copy().put("unitCost", "-0.01"), PRODUCT.copy().put("unitCost",
                                new BigDecimal("12.45"))))));
    }

    @Test
    void responsesHaveExactlyTheirFields() {
        JsonObject seller = SELLER.copy().put("sellerId", "1").put("profitsEarned", PROFITS);
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Seller", List.of(seller),
                List.of(without(seller, "profitsEarned"), seller.copy().put("name", "Old seller"),
                        seller.copy().put("profitsEarned", "15.00"))));
        JsonObject product = PRODUCT.copy().put("sellerId", "1").put("productId", "2").put("stock", 7);
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Product", List.of(product),
                List.of(without(product, "description"), without(product, "unitCost"),
                        without(product, "sellerId"))));
    }

    @Test
    void idempotencyKeysAreOpaqueAsciiText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "IdempotencyKey",
                List.of("a", "dataset-v1.entity.1", "scope:value_1-2.3", "A".repeat(128)),
                List.of("", "A".repeat(129), " a", "a ", "a\n", "a\r\n", "a/b", "é", ".a", 1)));
    }

    @Test
    void creationsRequireOneIdempotencyKeyHeader() {
        for (String operation : List.of("#/paths/~1api~1v1~1sellers/post",
                "#/paths/~1api~1v1~1sellers~1{sellerId}~1products/post")) {
            JsonArray parameters = ((JsonObject) API.node(operation)).getJsonArray("parameters");
            boolean required = false;
            for (int index = 0; index < parameters.size(); index++) {
                JsonObject parameter = (JsonObject) API.node(API.target(operation + "/parameters/" + index));
                required |= "Idempotency-Key".equals(parameter.getString("name"))
                        && "header".equals(parameter.getString("in"))
                        && Boolean.TRUE.equals(parameter.getValue("required"));
            }
            assertTrue(required, operation + " requires the Idempotency-Key header");
        }
    }

    @Test
    void datasetDefinitionsStayInTheWorkloads() {
        assertTrue(API.document().getJsonObject("paths").fieldNames().stream()
                .noneMatch(path -> path.contains("/datasets/")), "Product serves no dataset paths");
    }

    @Test
    void exampleIdsFollowTheServiceIssuedIdRules() {
        List<Object> values = API.examples().stream().map(Example::value).toList();
        assertEquals(List.of(), ExampleIds.violations(values, Set.of("sellerId", "productId")));
    }

    @Test
    void creationExamplesDescribeOneNewSellerAndItsProduct() {
        JsonObject seller = example("#/paths/~1api~1v1~1sellers/post/responses/201");
        JsonObject product = example("#/paths/~1api~1v1~1sellers~1{sellerId}~1products/post/responses/201");
        assertEquals(seller.getString("sellerId"), product.getString("sellerId"),
                "The product creation example uses the seller of the seller creation example");
        assertEquals(new JsonObject().put("USD", "0.00").put("EUR", "0.00"), seller.getJsonObject("profitsEarned"),
                "A new seller starts with zero profits in each currency");
    }

    private static JsonObject example(String response) {
        return ((JsonObject) API.node(response + "/content/application~1json")).getJsonObject("example");
    }

    @SafeVarargs
    private static List<Object> concat(List<?>... lists) {
        return Stream.of(lists).flatMap(List::stream).map(value -> (Object) value).toList();
    }
}
