package dev.nklip.javacraft.shardshop.order.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Address;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.AllocationRequest;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.BuyerCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderItem;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict wire validation, completed before the service can perform IO. */
final class OrderInput {
    private static final String EMAIL = "(?=[^@]{1,64}@)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
            + "(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
            + "(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}";

    BuyerCreation buyer(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "firstName", "surname", "email", "phone", "address", "region");
            JsonNode address = node.get("address");
            JsonFields.requireFields(address, "line1", "city", "postalCode", "countryCode");
            String email = JsonFields.matchingText(node, "email", EMAIL);
            if (email.length() > 254) {
                throw invalid();
            }
            String region = JsonFields.text(node, "region");
            if (!Set.of("US", "EU", "ASIA").contains(region)) {
                throw invalid();
            }
            return new BuyerCreation(JsonFields.nonblankText(node, "firstName", 200),
                    JsonFields.nonblankText(node, "surname", 200), email,
                    JsonFields.matchingText(node, "phone", "\\+[0-9]{7,15}"),
                    new Address(JsonFields.nonblankText(address, "line1", 200),
                            JsonFields.nonblankText(address, "city", 100),
                            JsonFields.nonblankText(address, "postalCode", 20),
                            JsonFields.matchingText(address, "countryCode", "[A-Z]{2}")),
                    region);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    AllocationRequest allocation(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "runName", "requestOrdinal");
            String ordinal = JsonFields.text(node, "requestOrdinal");
            return new AllocationRequest(JsonFields.nonblankText(node, "runName", 200),
                    "0".equals(ordinal) ? 0 : id(ordinal));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    OrderCreation order(byte[] body, HttpHeaders headers) {
        JsonNode node = document(body, headers);
        try {
            JsonFields.requireFields(node, "currency", "items");
            String currency = JsonFields.matchingText(node, "currency", "[A-Z]{3}");
            JsonNode items = node.get("items");
            if (items == null || !items.isArray() || items.isEmpty() || items.size() > 1000) {
                throw invalid();
            }
            List<OrderItem> result = new ArrayList<>();
            Set<Pair> pairs = new HashSet<>();
            for (JsonNode item : items) {
                JsonFields.requireFields(item, "sellerId", "productId", "quantity");
                long seller = id(JsonFields.text(item, "sellerId"));
                long product = id(JsonFields.text(item, "productId"));
                int quantity = JsonFields.nonnegativeInteger(item, "quantity");
                if (quantity == 0 || !pairs.add(new Pair(seller, product))) {
                    throw invalid();
                }
                result.add(new OrderItem(seller, product, quantity));
            }
            return new OrderCreation(currency, result);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    long id(String text) {
        try {
            return new IdParser().parse(text);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    String headerKey(HttpHeaders headers) {
        List<String> values = headers.getRequestHeader("Idempotency-Key");
        if (values == null || values.size() != 1 || !values.getFirst().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw invalid();
        }
        return values.getFirst();
    }

    void noQuery(UriInfo uri) {
        if (!uri.getQueryParameters().isEmpty()) {
            throw invalid();
        }
    }

    void requireJsonMediaType(List<String> types) {
        try {
            JsonRequests.requireJsonMediaType(types);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private JsonNode document(byte[] body, HttpHeaders headers) {
        try {
            JsonRequests.requireJsonMediaType(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE));
            return JsonRequests.parse(body);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private OrderFailure invalid() {
        return new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
    }

    private record Pair(long sellerId, long productId) {
    }
}
