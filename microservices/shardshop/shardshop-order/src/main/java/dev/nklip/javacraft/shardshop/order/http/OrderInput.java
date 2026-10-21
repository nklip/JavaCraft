package dev.nklip.javacraft.shardshop.order.http;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderListingService;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Address;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.AllocationRequest;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.BuyerCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderItem;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderCursor;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.SortDirection;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.OffsetDateTime;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

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

    ListingQuery listingQuery(UriInfo uri) {
        requireValidQueryEncoding(uri.getRequestUri().getRawQuery());
        MultivaluedMap<String, String> query = uri.getQueryParameters();
        if (!Set.of("region", "productId", "productName", "sort", "limit", "afterCreatedAt", "afterOrderId")
                .containsAll(query.keySet())) {
            throw invalid();
        }
        String region = singleQuery(query, "region", true);
        if (!Set.of("US", "EU", "ASIA").contains(region)) {
            throw invalid();
        }
        String product = singleQuery(query, "productId", false);
        String productName = OrderListingService.normalizeProductName(singleQuery(query, "productName", false));
        if (product != null && productName != null) {
            throw invalid();
        }
        String sort = singleQuery(query, "sort", false);
        String limit = singleQuery(query, "limit", false);
        String createdAt = singleQuery(query, "afterCreatedAt", false);
        String orderId = singleQuery(query, "afterOrderId", false);
        try {
            int pageSize = 25;
            if (limit != null) {
                if (!limit.matches("[1-9][0-9]{0,2}")) {
                    throw invalid();
                }
                pageSize = Integer.parseInt(limit);
                if (pageSize > 100) {
                    throw invalid();
                }
            }
            if ((createdAt == null) != (orderId == null)) {
                throw invalid();
            }
            OrderCursor after = null;
            if (createdAt != null) {
                if (!createdAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")) {
                    throw invalid();
                }
                OffsetDateTime timestamp = OffsetDateTime.parse(createdAt);
                if (timestamp.getYear() < 1) {
                    throw invalid();
                }
                after = new OrderCursor(timestamp.toInstant(), id(orderId));
            }
            return new ListingQuery(region, product == null ? null : id(product), productName,
                    sort == null ? SortDirection.DESC : SortDirection.valueOf(sort), pageSize, after);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw invalid();
        }
    }

    private void requireValidQueryEncoding(String rawQuery) {
        if (rawQuery == null) {
            return;
        }
        byte[] bytes = new byte[rawQuery.length()];
        for (int offset = 0; offset < rawQuery.length(); ) {
            if (rawQuery.charAt(offset) != '%') {
                offset++;
                continue;
            }
            int count = 0;
            while (offset < rawQuery.length() && rawQuery.charAt(offset) == '%') {
                if (offset + 2 >= rawQuery.length()) {
                    throw invalid();
                }
                int high = Character.digit(rawQuery.charAt(offset + 1), 16);
                int low = Character.digit(rawQuery.charAt(offset + 2), 16);
                if (high < 0 || low < 0) {
                    throw invalid();
                }
                bytes[count++] = (byte) (high * 16 + low);
                offset += 3;
            }
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count));
            } catch (CharacterCodingException failure) {
                throw invalid();
            }
        }
    }

    private String singleQuery(MultivaluedMap<String, String> query, String name, boolean required) {
        List<String> values = query.get(name);
        if (values == null && !required) {
            return null;
        }
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw invalid();
        }
        return values.getFirst();
    }

    record ListingQuery(String region, Long productId, String productName, SortDirection sort, int limit, OrderCursor after) {
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
