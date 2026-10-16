package dev.nklip.javacraft.shardshop.order.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Address;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Allocation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Buyer;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.BuyerCreation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Creation;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/api/v1/buyers")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class OrderResource {
    private final OrderService service;
    private final OrderDeadline deadline;
    private final OrderInput input = new OrderInput();

    @Inject
    public OrderResource(OrderService service, OrderDeadline deadline) {
        this.service = service;
        this.deadline = deadline;
    }

    @POST
    public Response createBuyer(byte[] body, @Context HttpHeaders headers, @Context UriInfo uri) {
        input.noQuery(uri);
        String key = input.headerKey(headers);
        BuyerCreation payload = input.buyer(body, headers);
        deadline.check();
        Creation<Buyer> result = service.createBuyer(key, payload);
        return Response.status(result.created() ? 201 : 200).entity(buyerResponse(result.entity())).build();
    }

    @GET
    @Path("/{buyerId}")
    public BuyerResponse buyer(@PathParam("buyerId") String buyerId, @Context UriInfo uri) {
        input.noQuery(uri);
        long id = input.id(buyerId);
        deadline.check();
        return buyerResponse(service.buyer(id));
    }

    @POST
    @Path("/{buyerId}/order-allocations")
    public Response allocateOrder(@PathParam("buyerId") String buyerId, byte[] body,
                                  @Context HttpHeaders headers, @Context UriInfo uri) {
        input.noQuery(uri);
        long id = input.id(buyerId);
        var payload = input.allocation(body, headers);
        deadline.check();
        Creation<Allocation> result = service.allocateOrder(id, payload);
        Allocation allocation = result.entity();
        return Response.status(result.created() ? 201 : 200).entity(new AllocationResponse(
                Long.toString(allocation.buyerId()), Long.toString(allocation.orderId()), allocation.runName(),
                Long.toString(allocation.requestOrdinal()))).build();
    }

    @PUT
    @Path("/{buyerId}/orders/{orderId}")
    public Response putOrder(@PathParam("buyerId") String buyerId, @PathParam("orderId") String orderId,
                             byte[] body, @Context HttpHeaders headers, @Context UriInfo uri) {
        input.noQuery(uri);
        long buyer = input.id(buyerId);
        long order = input.id(orderId);
        var payload = input.order(body, headers);
        deadline.check();
        Order result = service.putOrder(buyer, order, payload);
        Response.ResponseBuilder response = Response.status(result.pending() ? 202 : 200).entity(orderResponse(result));
        if (result.pending()) {
            response.header(HttpHeaders.LOCATION, result.statusUrl());
        }
        return response.build();
    }

    @GET
    @Path("/{buyerId}/orders/{orderId}")
    public OrderResponse order(@PathParam("buyerId") String buyerId, @PathParam("orderId") String orderId,
                                @Context UriInfo uri) {
        input.noQuery(uri);
        long buyer = input.id(buyerId);
        long order = input.id(orderId);
        deadline.check();
        return orderResponse(service.order(buyer, order));
    }

    private BuyerResponse buyerResponse(Buyer buyer) {
        BuyerCreation creation = buyer.creation();
        return new BuyerResponse(Long.toString(buyer.buyerId()), creation.firstName(), creation.surname(),
                creation.email(), creation.phone(), creation.address(), creation.region());
    }

    private OrderResponse orderResponse(Order order) {
        return new OrderResponse(Long.toString(order.buyerId()), Long.toString(order.orderId()), order.status(),
                order.statusUrl(), order.cancellationReason());
    }

    public record BuyerResponse(String buyerId, String firstName, String surname, String email, String phone,
                                Address address, String region) {
    }

    public record AllocationResponse(String buyerId, String orderId, String runName, String requestOrdinal) {
    }

    public record OrderResponse(String buyerId, String orderId, String status, String statusUrl,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String cancellationReason) {
    }
}
