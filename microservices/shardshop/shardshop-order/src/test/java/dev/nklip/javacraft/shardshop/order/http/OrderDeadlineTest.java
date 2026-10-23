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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final OrderDeadline deadline = new OrderDeadline(vertx, context, MemorySize.of(262144), new OrderWriteMetrics());

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

    @Test
    void expirationStatusCheckAllowsCompletionWhileTransportIsLocked() throws Exception {
        var transport = new TransportLock();
        when(response.ended()).thenAnswer(ignored -> {
            transport.enter();
            return false;
        });

        assertCallbackCanCompleteDuringTransportOperation(transport,
                () -> timer.getValue().handle(17L),
                () -> end.getValue().handle(Future.succeededFuture()));
    }

    @Test
    void expirationResponseEndAllowsCompletionWhileTransportIsLocked() throws Exception {
        var transport = new TransportLock();
        when(response.end(anyString())).thenAnswer(ignored -> {
            transport.enter();
            return Future.succeededFuture();
        });

        assertCallbackCanCompleteDuringTransportOperation(transport,
                () -> timer.getValue().handle(17L),
                () -> end.getValue().handle(Future.succeededFuture()));
    }

    @Test
    void expirationConnectionCloseAllowsCompletionWhileTransportIsLocked() throws Exception {
        deadline.aroundWriteTo(mock(WriterInterceptorContext.class));
        var transport = new TransportLock();
        when(connection.close()).thenAnswer(ignored -> {
            transport.enter();
            return Future.succeededFuture();
        });

        assertCallbackCanCompleteDuringTransportOperation(transport,
                () -> timer.getValue().handle(17L),
                () -> end.getValue().handle(Future.succeededFuture()));
    }

    @Test
    void writerStatusCheckAllowsCompletionWhileTransportIsLocked() throws Exception {
        var writer = mock(WriterInterceptorContext.class);
        var transport = new TransportLock();
        when(response.ended()).thenAnswer(ignored -> {
            transport.enter();
            return false;
        });

        assertCallbackCanCompleteDuringTransportOperation(transport,
                () -> deadline.aroundWriteTo(writer),
                () -> end.getValue().handle(Future.succeededFuture()));

        verify(writer, never()).proceed();
    }

    @Test
    void timerCancellationAllowsExpirationWhileTransportIsLocked() throws Exception {
        var transport = new TransportLock();
        when(vertx.cancelTimer(17L)).thenAnswer(ignored -> {
            transport.enter();
            return true;
        });

        assertCallbackCanCompleteDuringTransportOperation(transport,
                () -> end.getValue().handle(Future.succeededFuture()),
                () -> timer.getValue().handle(17L));

        verify(response, never()).setStatusCode(503);
    }

    @Test
    void finishedRequestsSuppressTheirLateWriter() throws IOException {
        end.getValue().handle(Future.succeededFuture());
        var writer = mock(WriterInterceptorContext.class);

        deadline.aroundWriteTo(writer);

        verify(writer, never()).proceed();
    }

    private void assertCallbackCanCompleteDuringTransportOperation(TransportLock transport,
                                                                   TransportOperation operation,
                                                                   Runnable callback) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit((Callable<Void>) () -> {
                transport.lock.lock();
                try {
                    transport.held.countDown();
                    assertTrue(transport.entered.await(5, TimeUnit.SECONDS), "transport operation was not reached");
                    callback.run();
                } finally {
                    transport.lock.unlock();
                }
                return null;
            });
            var caller = executor.submit((Callable<Void>) () -> {
                assertTrue(transport.held.await(5, TimeUnit.SECONDS), "transport lock was not acquired");
                operation.run();
                return null;
            });

            holder.get(10, TimeUnit.SECONDS);
            caller.get(10, TimeUnit.SECONDS);
            assertFalse(transport.timedOut.get(), "transport operation held the deadline monitor while waiting for the transport lock");
        }
    }

    @FunctionalInterface
    private interface TransportOperation {
        void run() throws IOException;
    }

    private static final class TransportLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final AtomicBoolean timedOut = new AtomicBoolean();

        private void enter() throws InterruptedException {
            entered.countDown();
            if (!lock.tryLock(2, TimeUnit.SECONDS)) {
                timedOut.set(true);
                return;
            }
            lock.unlock();
        }
    }
}
