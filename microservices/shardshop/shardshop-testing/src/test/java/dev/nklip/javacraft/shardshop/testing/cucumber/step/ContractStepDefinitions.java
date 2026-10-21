package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument.Example;
import dev.nklip.javacraft.shardshop.common.contract.ExampleIds;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.StoredCatalog;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.StoredCatalog.Definition;
import io.cucumber.java.en.Then;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ContractStepDefinitions {
    private static final String PRODUCT_CREATED = "#/paths/~1api~1v1~1sellers~1{sellerId}~1products/post/responses/201";
    private static final String BUYERS_HEADER = "firstName\tsurname\temail\tphone\tline1\tcity\tpostalCode\tcountryCode";
    private static final String SCHEMAS = "#/components/schemas/";

    private final JsonObject command = Contracts.message("shardshop-order", "record-order.json");
    private final List<JsonObject> results = List.of(Contracts.message("shardshop-ledger", "ledger-recorded.json"),
            Contracts.message("shardshop-ledger", "ledger-rejected.json"));

    @Then("each snapshot item of the RecordOrder example has the IDs, name, price and currency of the product creation example")
    public void snapshotItemsBuyTheProductExample() {
        JsonObject product = Contracts.responseExample(Contracts.PRODUCT_API, PRODUCT_CREATED);
        for (Object value : command.getJsonObject("snapshot").getJsonArray("items")) {
            JsonObject item = (JsonObject) value;
            assertEquals(List.of(product.getString("sellerId"), product.getString("productId"),
                            product.getString("name"), product.getString("price"), product.getString("currency")),
                    List.of(item.getString("sellerId"), item.getString("productId"), item.getString("productName"),
                            item.getString("unitPrice"), item.getString("currency")),
                    "Snapshot item " + item.getInteger("itemPosition"));
        }
    }

    @Then("each ledger result example has the command, saga, order, buyer and fingerprint of the RecordOrder example")
    public void resultsCorrelateWithTheCommand() {
        for (JsonObject result : results) {
            for (String key : List.of("commandId", "sagaId", "orderId", "buyerId", "requestFingerprint")) {
                assertEquals(command.getString(key), result.getString(key), result.getString("type") + " " + key);
            }
        }
    }

    @Then("each ledger result example uses the result message ID that the RecordOrder example reserved")
    public void resultsUseTheReservedMessageId() {
        for (JsonObject result : results) {
            assertEquals(command.getString("resultMessageId"), result.getString("messageId"),
                    result.getString("type") + " messageId");
        }
    }

    @Then("each ledger result example has the amount and currency of the RecordOrder snapshot")
    public void resultsKeepTheSnapshotAmount() {
        JsonObject snapshot = command.getJsonObject("snapshot");
        for (JsonObject result : results) {
            assertEquals(List.of(snapshot.getString("amount"), snapshot.getString("currency")),
                    List.of(result.getString("amount"), result.getString("currency")), result.getString("type"));
        }
    }

    @Then("each ledger result example was decided at or after the time of the RecordOrder example")
    public void resultsFollowTheCommand() {
        Instant occurredAt = Instant.parse(command.getString("occurredAt"));
        for (JsonObject result : results) {
            assertFalse(Instant.parse(result.getString("decidedAt")).isBefore(occurredAt),
                    result.getString("type") + " is decided after the command occurred");
        }
    }

    @Then("the seller, product and buyer IDs of all contract examples follow the service-issued ID rules")
    public void exampleIdsFollowTheRules() {
        List<Object> values = new ArrayList<>();
        for (ContractDocument api : List.of(Contracts.PRODUCT_API, Contracts.ORDER_API)) {
            api.examples().stream().map(Example::value).forEach(values::add);
        }
        values.add(command);
        values.addAll(results);
        assertEquals(List.of(), ExampleIds.violations(values, Set.of("sellerId", "productId", "buyerId")));
    }

    @Then("the {int} sellers of catalog-v2 match the {string} schema of the product contract")
    public void sellersAreValid(int count, String schema) {
        List<Definition> sellers = StoredCatalog.definitions(true);
        assertEquals(count, sellers.size(), "Sellers of catalog-v2");
        for (Definition seller : sellers) {
            assertValid(Contracts.PRODUCT_API, schema, seller.key(), new JsonObject()
                    .put("companyName", seller.companyName()).put("region", seller.region()).put("countryCode", seller.countryCode()));
        }
    }

    @Then("the {int} products of catalog-v2 match the {string} schema of the product contract")
    public void productsAreValid(int count, String schema) {
        List<Definition> products = StoredCatalog.definitions(false);
        assertEquals(count, products.size(), "Products of catalog-v2");
        for (Definition product : products) {
            assertValid(Contracts.PRODUCT_API, schema, product.key(), new JsonObject().put("name", product.name())
                    .put("description", product.description()).put("price", product.price())
                    .put("unitCost", product.unitCost()).put("currency", product.currency())
                    .put("initialStock", product.initialStock()));
        }
    }

    @Then("the {int} buyers of buyers-v1 match the {string} schema of the order contract")
    public void buyersAreValid(int count, String schema) throws IOException {
        int buyers = 0;
        for (String region : StoredCatalog.REGIONS) {
            List<String> rows = Files.readAllLines(ShardShopFiles.resolve("shardshop-workload/shardshop-datasets/src/"
                    + "main/resources/datasets/buyers-v1/" + region + ".tsv"), StandardCharsets.UTF_8);
            assertEquals(BUYERS_HEADER, rows.getFirst(), "Header of " + region);
            for (int ordinal = 1; ordinal < rows.size(); ordinal++) {
                String[] fields = rows.get(ordinal).split("\t", -1);
                JsonObject address = new JsonObject().put("line1", fields[4]).put("city", fields[5])
                        .put("postalCode", fields[6]).put("countryCode", fields[7]);
                assertValid(Contracts.ORDER_API, schema, "buyers-v1." + region + ".buyer." + ordinal, new JsonObject()
                        .put("firstName", fields[0]).put("surname", fields[1]).put("email", fields[2])
                        .put("phone", fields[3]).put("address", address).put("region", region));
                buyers++;
            }
        }
        assertEquals(count, buyers, "Buyers of buyers-v1");
    }

    private static void assertValid(ContractDocument api, String schema, String key, JsonObject request) {
        assertEquals(List.of(), api.errors(SCHEMAS + schema, request), key);
    }
}
