package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import io.vertx.core.json.JsonObject;

import java.nio.file.Path;

/**
 * The contracts of product, order and ledger, read once from the source tree. The scenarios load no application
 * classes, thus they read the same files that each owning module checks in its unit tests. A ContractDocument is
 * safe for concurrent clients.
 */
public final class Contracts {
    public static final ContractDocument PRODUCT_API = ContractDocument.openApi(file("shardshop-product",
            "openapi.yaml"));
    public static final ContractDocument ORDER_API = ContractDocument.openApi(file("shardshop-order", "openapi.yaml"));
    public static final ContractDocument RECORD_ORDER = ContractDocument.messageSchema(file("shardshop-order",
            "record-order.schema.json"));
    public static final ContractDocument LEDGER_RESULT = ContractDocument.messageSchema(file("shardshop-ledger",
            "ledger-result.schema.json"));

    private Contracts() {
    }

    /** A message example of a module, for example {@code record-order.json} of shardshop-order. */
    public static JsonObject message(String module, String example) {
        return (JsonObject) ContractDocument.read(file(module, "examples/" + example));
    }

    /** The single example of the JSON body of a response, for example {@code #/components/responses/Buyer}. */
    public static JsonObject responseExample(ContractDocument api, String response) {
        return ((JsonObject) api.node(api.target(response) + "/content/application~1json")).getJsonObject("example");
    }

    private static Path file(String module, String name) {
        return ShardShopFiles.resolve(module + "/src/main/resources/contracts/" + name);
    }
}
