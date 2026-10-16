package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderDeadlineTest {
    private final Vertx vertx = mock(Vertx.class);
    private final RoutingContext context = mock(RoutingContext.class);
    private final HttpServerRequest request = mock(HttpServerRequest.class);
    private final HttpServerResponse response = mock(HttpServerResponse.class);
    private final HttpConnection connection = mock(HttpConnection.class);
    private final ArgumentCaptor<Handler<Long>> timer = ArgumentCaptor.captor();
    private final ArgumentCaptor<Handler<AsyncResult<Void>>> end = ArgumentCaptor.captor();
    private final OrderDeadline deadline = new OrderDeadline(vertx, context, MemorySize.of(262144));

    @BeforeEach
    void prepareRequest() {
        when(context.request()).thenReturn(request);
        when(request.connection()).thenReturn(connection);
        when(context.response()).thenReturn(response);
        when(response.setStatusCode(503)).thenReturn(response);
        when(response.putHeader("Content-Type", "application/json")).thenReturn(response);
        when(response.end(anyString())).thenReturn(Future.succeededFuture());
        when(vertx.setTimer(eq(10000L), any())).thenReturn(17L);
        when(context.put(eq(OrderDeadline.STATE_KEY), any())).thenAnswer(invocation -> {
            when(context.get(OrderDeadline.STATE_KEY)).thenReturn(invocation.getArgument(1));
            return context;
        });
        deadline.start(context);
        verify(vertx).setTimer(eq(10000L), timer.capture());
        verify(context).addEndHandler(end.capture());
    }

    @Test
    void expiresQueuedRequestsAndSuppressesTheirLateSuccess() throws IOException {
        timer.getValue().handle(17L);
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                assertThrows(OrderFailure.class, deadline::check).reason());
        verify(response).end("{\"code\":\"ORDER_STORE_UNAVAILABLE\",\"message\":\"Order request exceeded its deadline.\"}");
        var writer = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(writer);
        verify(writer, never()).proceed();
    }

    @Test
    void finishedRequestsCancelTimeoutWithoutOverwritingResponse() {
        end.getValue().handle(Future.succeededFuture());
        timer.getValue().handle(17L);
        verify(vertx).cancelTimer(17L);
        verify(response, never()).setStatusCode(503);
    }

    @Test
    void closesAnAlreadyStartedResponseWhenItsDeadlineExpires() throws IOException {
        var writer = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(writer);
        verify(writer).proceed();
        timer.getValue().handle(17L);
        verify(connection).close();
        verify(response, never()).setStatusCode(503);
    }
}
