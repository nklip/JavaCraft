package dev.nklip.javacraft.shardshop.workload.producer.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.producer.plan.OrderPlanner;
import dev.nklip.javacraft.shardshop.workload.producer.plan.PlannedOrder;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Strict HTTP mapping for durable allocation, idempotent order PUT and status reads. */
public final class OrderRequests {
    private final JsonHttpClient http;
    private final IdParser ids = new IdParser();

    public OrderRequests(JsonHttpClient http) {
        this.http = Objects.requireNonNull(http);
    }

    public String allocate(String buyerId, String runName, long ordinal) throws IOException, InterruptedException {
        ids.parse(buyerId);
        OrderPlanner.validateRunName(runName);
        if (ordinal < 0) {
            throw new IllegalArgumentException("Request ordinal must be nonnegative");
        }
        String ordinalText = Long.toString(ordinal);
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("runName", runName)
                .put("requestOrdinal", ordinalText);
        return http.post("/api/v1/buyers/" + buyerId + "/order-allocations", payload, response -> {
            JsonFields.requireFields(response, "buyerId", "orderId", "runName", "requestOrdinal");
            String returnedBuyer = JsonFields.id(response, "buyerId");
            String orderId = JsonFields.id(response, "orderId");
            if (!buyerId.equals(returnedBuyer) || !runName.equals(JsonFields.text(response, "runName"))
                    || !ordinalText.equals(JsonFields.text(response, "requestOrdinal"))) {
                throw new IllegalArgumentException("Allocation response does not match request");
            }
            return orderId;
        });
    }

    public OrderStatus submit(PlannedOrder order) throws IOException, InterruptedException {
        return submitWithReplay(order).status();
    }

    /** The replay flag also identifies an existing pending order, unlike its HTTP status alone. */
    public Submission submitWithReplay(PlannedOrder order) throws IOException, InterruptedException {
        Objects.requireNonNull(order);
        String path = path(order.buyerId(), order.orderId());
        if (!CurrencyPolicy.SUPPORTED_CURRENCIES.contains(order.currency()) || order.items().isEmpty()
                || order.items().size() > 1000) {
            throw new IllegalArgumentException("Invalid order payload");
        }
        Set<String> pairs = new HashSet<>();
        ArrayNode items = JsonNodeFactory.instance.arrayNode();
        for (PlannedOrder.Item item : order.items()) {
            Objects.requireNonNull(item);
            ids.parse(item.sellerId());
            ids.parse(item.productId());
            if (item.quantity() < 1 || !pairs.add(item.sellerId() + ":" + item.productId())) {
                throw new IllegalArgumentException("Invalid order item");
            }
            items.add(JsonNodeFactory.instance.objectNode().put("sellerId", item.sellerId())
                    .put("productId", item.productId()).put("quantity", item.quantity()));
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("currency", order.currency());
        payload.set("items", items);
        try {
            return http.putDetailed(path, payload, (metadata, response) -> {
                int status = metadata.statusCode();
                OrderStatus decoded = decodeStatus(response, order.buyerId(), order.orderId());
                if ((status == 202 && decoded.terminal()) || (status == 200 && !decoded.terminal())) {
                    throw new IllegalArgumentException("Order status does not match HTTP response");
                }
                String replay = metadata.headers().firstValue("Idempotent-Replay").orElse(null);
                if (replay == null) {
                    throw new MissingReplayMetadata();
                }
                if (!"true".equals(replay) && !"false".equals(replay)) {
                    throw new IllegalArgumentException("Missing or invalid order replay metadata");
                }
                boolean recovered = Boolean.parseBoolean(replay);
                if (decoded.terminal() && !recovered) {
                    throw new IllegalArgumentException("A terminal order must be a replay");
                }
                return new Submission(decoded, recovered);
            });
        } catch (MissingReplayMetadata missing) {
            // An older HTTP replica can still answer during rollout. Repeating this PUT is idempotent and keeps
            // the existing attempt/deadline limits, without guessing whether the saved order was recovered.
            throw new IOException("Order replay metadata unavailable", missing);
        }
    }

    private static final class MissingReplayMetadata extends IllegalArgumentException { }

    public record Submission(OrderStatus status, boolean replayed) { }

    public OrderStatus status(String buyerId, String orderId) throws IOException, InterruptedException {
        String path = path(buyerId, orderId);
        return http.get(path, response -> decodeStatus(response, buyerId, orderId));
    }

    private String path(String buyerId, String orderId) {
        ids.parse(buyerId);
        ids.parse(orderId);
        return "/api/v1/buyers/" + buyerId + "/orders/" + orderId;
    }

    private static OrderStatus decodeStatus(JsonNode response, String buyerId, String orderId) {
        if (response == null || !response.isObject()) {
            throw new IllegalArgumentException("Invalid order status response");
        }
        String status = JsonFields.text(response, "status");
        boolean cancelled = "CANCELLED".equals(status);
        if (!Set.of("PENDING_STOCK", "PENDING_LEDGER", "CONFIRMED", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("Unexpected order status");
        }
        if (cancelled) {
            JsonFields.requireFields(response, "buyerId", "orderId", "status", "statusUrl", "cancellationReason");
        } else {
            JsonFields.requireFields(response, "buyerId", "orderId", "status", "statusUrl");
        }
        String returnedBuyer = JsonFields.id(response, "buyerId");
        String returnedOrder = JsonFields.id(response, "orderId");
        String statusUrl = JsonFields.text(response, "statusUrl");
        if (!buyerId.equals(returnedBuyer) || !orderId.equals(returnedOrder)
                || !statusUrl.equals("/api/v1/buyers/" + buyerId + "/orders/" + orderId)) {
            throw new IllegalArgumentException("Order status response does not match request");
        }
        String reason = cancelled ? JsonFields.text(response, "cancellationReason") : null;
        if (cancelled && !Set.of("OUT_OF_STOCK", "UNSUPPORTED_CURRENCY").contains(reason)) {
            throw new IllegalArgumentException("Unexpected cancellation reason");
        }
        return new OrderStatus(returnedBuyer, returnedOrder, status, statusUrl, reason);
    }
}
