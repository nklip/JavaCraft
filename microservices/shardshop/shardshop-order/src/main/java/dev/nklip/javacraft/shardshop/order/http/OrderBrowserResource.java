package dev.nklip.javacraft.shardshop.order.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.nklip.javacraft.shardshop.order.ordering.OrderListingService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;

/** Public read-only regional order view; no workload dataset or seller-region restriction. */
@Path("/api/v1/orders")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class OrderBrowserResource {
    private final OrderListingService service;
    private final OrderDeadline deadline;
    private final OrderInput input = new OrderInput();

    @Inject
    public OrderBrowserResource(OrderListingService service, OrderDeadline deadline) {
        this.service = service;
        this.deadline = deadline;
    }

    @GET
    public OrderPageResponse orders(@Context UriInfo uri) {
        var query = input.listingQuery(uri);
        deadline.check();
        var page = service.orders(query.region(), query.productId(), query.productName(),
                query.sort(), query.limit(), query.after());
        deadline.check();
        List<OrderSummary> items = page.items().stream().map(order -> new OrderSummary(
                Long.toString(order.orderId()), Long.toString(order.buyerId()), order.buyerName(), order.createdAt().toString(),
                order.status(), order.cancellationReason(), order.total().toPlainString(), order.currency(),
                order.items().stream().map(item -> new ItemSummary(Long.toString(item.productId()), item.productName(), item.quantity()))
                        .toList())).toList();
        CursorResponse next = page.nextAfter() == null ? null : new CursorResponse(
                page.nextAfter().createdAt().toString(), Long.toString(page.nextAfter().orderId()));
        return new OrderPageResponse(page.region(), items, next);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrderPageResponse(String region, List<OrderSummary> items, CursorResponse nextAfter) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrderSummary(String orderId, String buyerId, String buyerName, String createdAt, String status,
                               String cancellationReason, String total, String currency, List<ItemSummary> items) {
    }

    public record ItemSummary(String productId, String productName, int quantity) {
    }

    public record CursorResponse(String createdAt, String orderId) {
    }
}
