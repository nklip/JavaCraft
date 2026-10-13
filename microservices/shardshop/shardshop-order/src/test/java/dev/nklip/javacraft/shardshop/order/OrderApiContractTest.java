package dev.nklip.javacraft.shardshop.order;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument.Example;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.invalidVariants;
import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.json;
import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order OpenAPI document: its structure, its examples and the boundaries of its schemas. */
class OrderApiContractTest {
    static final ContractDocument API = ContractDocument.openApi(Path.of("src/main/resources/contracts/openapi.yaml"));
    private static final String SCHEMAS = "#/components/schemas/";
    private static final String LONG_MAX = "9223372036854775807";
    private static final String OVER_LONG_MAX = "9223372036854775808";
    private static final JsonObject ADDRESS = new JsonObject().put("line1", "1 Example Road").put("city", "Example City")
            .put("postalCode", "000001").put("countryCode", "US");
    private static final JsonObject BUYER = new JsonObject().put("firstName", "Jane").put("surname", "Smith")
            .put("email", "jane.smith@example.com").put("phone", "+12025550123").put("address", ADDRESS)
            .put("region", "US");

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
                List.of(1, "0", "01", "+1", "-1", "1.0", "1e3", "1\n", "1\r\n", "\t1", "１", OVER_LONG_MAX,
                        "9999999999999999999")));
    }

    @Test
    void namesAreNonblankUnicodeScalarText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Name",
                List.of("A", " Café ", "🚀", "A".repeat(200)),
                List.of("", " \t\n", "A".repeat(201), "A\u0000", "\ud800", "A\udfff", "\udc00\ud800")));
    }

    @Test
    void quantitiesArePositiveExactInt32() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "OrderItem/properties/quantity",
                List.of(1, json("2"), json("2.0"), json("2e0"), 2147483647),
                List.of(0, new BigDecimal("2.5"), new BigDecimal("2.000000000000000001"),
                        new BigDecimal("2147483647.000000001"), "2", true, 2147483648L)));
    }

    @Test
    void requestOrdinalsAreCanonicalNonnegativeText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "RequestOrdinal",
                List.of("0", "1", LONG_MAX),
                List.of(0, "00", "-1", "1.0", "1e0", "1\n", OVER_LONG_MAX)));
    }

    @Test
    void buyerCreationsHaveExactlyTheirPersonFields() {
        JsonObject forbidden = new JsonObject().put("buyerId", "1").put("name", "Old buyer").put("extra", 1);
        assertEquals(List.of(), API.unexpected(SCHEMAS + "BuyerCreation",
                List.of(BUYER, BUYER.copy().put("firstName", " María ").put("surname", "O'Connor")),
                concat(invalidVariants(BUYER, forbidden), Stream.of("firstName", "surname")
                                .flatMap(field -> Stream.of(123, "", " \t", "A".repeat(201), "A\u0000")
                                        .map(value -> (Object) BUYER.copy().put(field, value))).toList(),
                        List.of(BUYER.copy().put("region", "UNKNOWN")))));
        JsonObject buyer = BUYER.copy().put("buyerId", "1");
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Buyer", List.of(buyer),
                concat(buyer.fieldNames().stream().map(field -> (Object) without(buyer, field)).toList(),
                        List.of(buyer.copy().put("name", "Old buyer")))));
    }

    @Test
    void emailsAreAsciiDotAtomAddresses() {
        String longest = "a".repeat(64) + "@" + String.join(".", "a".repeat(63), "b".repeat(63), "c".repeat(61));
        assertEquals(254, longest.length(), "The boundary uses exactly 254 characters");
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Email",
                List.of("jane.smith@example.com", "jane.smith@example.net", "a+b@example.org", "O'Connor@example.org",
                        "a".repeat(64) + "@example.org", longest),
                Arrays.asList(null, 1, "", "a", "a@", "@example.org", "a@example", "a b@example.org",
                        "a..b@example.org", ".a@example.org", "a.@example.org", "a@-example.org", "a@example-.org",
                        "a@exam_ple.org", "é@example.org", "a@exämple.org", "a@example.org\n", "a@example.org\r\n",
                        "a".repeat(65) + "@example.org", longest + "c")));
    }

    @Test
    void phonesArePlusAndSevenToFifteenDigits() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Phone",
                List.of("+1234567", "+12025550123", "+123456789012345"),
                Arrays.asList(null, 1234567, "1234567", "+123456", "+1234567890123456", "+1 2025550123",
                        "+1-202-555-0123", "+１２３４５６７", "+1234567\n",
                        "+1234567\r\n")));
    }

    @Test
    void addressesHaveNonblankBoundedTextAndACountryCode() {
        JsonObject longest = new JsonObject().put("line1", "A".repeat(200)).put("city", "A".repeat(100))
                .put("postalCode", "A".repeat(20)).put("countryCode", "GB");
        List<Object> invalid = concat(
                ADDRESS.fieldNames().stream().map(field -> (Object) without(ADDRESS, field)).toList(),
                List.of(ADDRESS.copy().put("extra", "no")),
                Stream.of("line1", "city", "postalCode").flatMap(field -> Stream.of(null, 123, "", " \t", "A\u0000",
                        "\ud800").map(value -> (Object) ADDRESS.copy().put(field, value))).toList(),
                List.of(ADDRESS.copy().put("line1", "A".repeat(201)), ADDRESS.copy().put("city", "A".repeat(101)),
                        ADDRESS.copy().put("postalCode", "A".repeat(21))),
                Stream.of(null, "us", "USA", "U", "U1", "US\n")
                        .map(value -> (Object) ADDRESS.copy().put("countryCode", value)).toList());
        assertEquals(List.of(), API.unexpected(SCHEMAS + "Address", List.of(ADDRESS, longest), invalid));
    }

    @Test
    void idempotencyKeysAreOpaqueAsciiText() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "IdempotencyKey",
                List.of("a", "dataset-v1.entity.1", "scope:value_1-2.3", "A".repeat(128)),
                List.of("", "A".repeat(129), " a", "a ", "a\n", "a\r\n", "a/b", "é", ".a", 1)));
    }

    @Test
    void statusUrlsNameCanonicalBuyerAndOrderIds() {
        assertEquals(List.of(), API.unexpected(SCHEMAS + "OrderStatus/properties/statusUrl",
                List.of("/api/v1/buyers/1/orders/" + LONG_MAX, "/api/v1/buyers/" + LONG_MAX + "/orders/1"),
                List.of("/api/v1/buyers/" + OVER_LONG_MAX + "/orders/1", "/api/v1/buyers/1/orders/" + OVER_LONG_MAX,
                        "/api/v1/buyers/01/orders/1", "/api/v1/buyers/1/orders/1\n")));
    }

    @Test
    void buyerCreationRequiresOneIdempotencyKeyHeader() {
        String operation = "#/paths/~1api~1v1~1buyers/post";
        JsonArray parameters = ((JsonObject) API.node(operation)).getJsonArray("parameters");
        boolean required = false;
        for (int index = 0; index < parameters.size(); index++) {
            JsonObject parameter = (JsonObject) API.node(API.target(operation + "/parameters/" + index));
            required |= "Idempotency-Key".equals(parameter.getString("name"))
                    && "header".equals(parameter.getString("in"))
                    && Boolean.TRUE.equals(parameter.getValue("required"));
        }
        assertTrue(required, "Buyer creation requires the Idempotency-Key header");
    }

    @Test
    void datasetDefinitionsStayInTheWorkloads() {
        assertTrue(API.document().getJsonObject("paths").fieldNames().stream()
                .noneMatch(path -> path.contains("/datasets/")), "Order serves no dataset paths");
    }

    @Test
    void theAcceptedOrderExampleBelongsToTheCreatedBuyer() {
        JsonObject buyer = example("#/components/responses/Buyer");
        JsonObject accepted = example("#/paths/~1api~1v1~1buyers~1{buyerId}~1orders~1{orderId}/put/responses/202");
        assertEquals(buyer.getString("buyerId"), accepted.getString("buyerId"),
                "The order example uses the buyer of the buyer creation example");
    }

    static JsonObject example(String response) {
        return ((JsonObject) API.node(response + "/content/application~1json")).getJsonObject("example");
    }

    @SafeVarargs
    private static List<Object> concat(List<?>... lists) {
        return Stream.of(lists).flatMap(List::stream).map(value -> (Object) value).toList();
    }
}
