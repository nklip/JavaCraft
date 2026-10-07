package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.config.CatalogConfiguration;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

class CatalogDeadlineTest {
    private final Vertx vertx = mock(Vertx.class);
    private final RoutingContext context = mock(RoutingContext.class);
    private final HttpServerRequest request = mock(HttpServerRequest.class);
    private final HttpConnection connection = mock(HttpConnection.class);
    private final HttpServerResponse response = mock(HttpServerResponse.class);
    private final CatalogConfiguration configuration = mock(CatalogConfiguration.class);
    private final ArgumentCaptor<Handler<Long>> timer = ArgumentCaptor.captor();
    private final ArgumentCaptor<Handler<AsyncResult<Void>>> end = ArgumentCaptor.captor();

    @BeforeEach
    void prepareRequest() {
        when(configuration.readProfile()).thenReturn(CatalogConfiguration.ReadProfile.PRIMARY);
        when(context.request()).thenReturn(request);
        when(request.connection()).thenReturn(connection);
        when(connection.close()).thenReturn(Future.succeededFuture());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(context.response()).thenReturn(response);
        when(response.setStatusCode(503)).thenReturn(response);
        when(response.putHeader("Content-Type", "application/json")).thenReturn(response);
        when(response.end(anyString())).thenReturn(Future.succeededFuture());
        when(vertx.setTimer(eq(4000L), any())).thenReturn(17L);
        when(context.put(eq(CatalogDeadline.STATE_KEY), any())).thenAnswer(invocation -> {
            when(context.get(CatalogDeadline.STATE_KEY)).thenReturn(invocation.getArgument(1));
            return context;
        });
    }

    @Test
    void registersBeforeBodyReceptionAndWorkerDispatch() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        CatalogDeadline deadline = deadline();
        deadline.register(router);
        ArgumentCaptor<Handler<RoutingContext>> handler = ArgumentCaptor.captor();
        verify(route, org.mockito.Mockito.times(3)).handler(handler.capture());
        handler.getAllValues().getFirst().handle(context);
        verify(context).next();
        assertNotNull(context.get(CatalogDeadline.STATE_KEY));
        assertDoesNotThrow(deadline::check);
    }

    @Test
    void oversizedBodiesHaveAContractErrorBeforeRestHandlesThem() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        deadline().register(router);
        ArgumentCaptor<Handler<RoutingContext>> failure = ArgumentCaptor.captor();
        verify(route).failureHandler(failure.capture());
        when(context.statusCode()).thenReturn(413);
        when(response.setStatusCode(400)).thenReturn(response);
        when(response.putHeader("Connection", "close")).thenReturn(response);
        failure.getValue().handle(context);
        verify(response).end("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request does not match the input contract.\"}");
        verify(connection).close();
        verify(context, never()).next();
    }

    @Test
    void collectsOnlyPostsWithAValidJsonMediaType() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        BodyHandler body = mock(BodyHandler.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        when(body.setBodyLimit(anyLong())).thenReturn(body);
        when(body.setMergeFormAttributes(false)).thenReturn(body);
        try (var factory = mockStatic(BodyHandler.class)) {
            factory.when(() -> BodyHandler.create(false)).thenReturn(body);
            deadline().register(router);
        }
        ArgumentCaptor<Handler<RoutingContext>> handlers = ArgumentCaptor.captor();
        verify(route, org.mockito.Mockito.times(3)).handler(handlers.capture());
        Handler<RoutingContext> collect = handlers.getAllValues().get(1);
        collect.handle(context);
        verify(context).next();
        verify(body, never()).handle(context);
        when(request.method()).thenReturn(HttpMethod.POST);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("Content-Type", "application/json; charset=UTF-8");
        when(request.headers()).thenReturn(headers);
        when(request.isEnded()).thenReturn(true);
        collect.handle(context);
        verify(context, org.mockito.Mockito.times(2)).next();
        verify(body, never()).handle(context);
        when(request.isEnded()).thenReturn(false);
        collect.handle(context);
        verify(body).handle(context);
        headers.set("Content-Type", "multipart/form-data; boundary=unused");
        when(response.setStatusCode(400)).thenReturn(response);
        when(response.putHeader("Connection", "close")).thenReturn(response);
        collect.handle(context);
        verify(response).end("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request does not match the input contract.\"}");
        verify(body).handle(context);
        verify(connection).close();
    }

    @Test
    void consumedEmptyPostsAreRejectedWithoutReadingTheStreamAgain() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        RequestBody body = mock(RequestBody.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        deadline().register(router);
        ArgumentCaptor<Handler<RoutingContext>> handlers = ArgumentCaptor.captor();
        verify(route, org.mockito.Mockito.times(3)).handler(handlers.capture());
        Handler<RoutingContext> validate = handlers.getAllValues().getLast();
        when(request.method()).thenReturn(HttpMethod.POST);
        when(context.body()).thenReturn(body);
        when(body.isEmpty()).thenReturn(true);
        when(response.setStatusCode(400)).thenReturn(response);
        when(response.putHeader("Connection", "close")).thenReturn(response);
        validate.handle(context);
        verify(response).end("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request does not match the input contract.\"}");
        verify(context, never()).next();
        when(body.isEmpty()).thenReturn(false);
        validate.handle(context);
        verify(context).next();
        when(request.method()).thenReturn(HttpMethod.GET);
        validate.handle(context);
        verify(context, org.mockito.Mockito.times(2)).next();
    }

    @Test
    void lateBodyCompletionAndDisconnectedUploadsDoNotReachRestOrItsErrorHandler() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        deadline().register(router);
        ArgumentCaptor<Handler<RoutingContext>> handlers = ArgumentCaptor.captor();
        ArgumentCaptor<Handler<RoutingContext>> failure = ArgumentCaptor.captor();
        verify(route, org.mockito.Mockito.times(3)).handler(handlers.capture());
        verify(route).failureHandler(failure.capture());
        when(response.ended()).thenReturn(true);
        handlers.getAllValues().getLast().handle(context);
        failure.getValue().handle(context);
        when(response.ended()).thenReturn(false);
        when(response.closed()).thenReturn(true);
        handlers.getAllValues().getLast().handle(context);
        failure.getValue().handle(context);
        verify(context, never()).next();
        verify(response, never()).setStatusCode(anyInt());
    }

    @Test
    void expiredUploadsStopBeforeTheAsynchronousResponseHasEnded() {
        CatalogDeadline deadline = start();
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        deadline.register(router);
        ArgumentCaptor<Handler<RoutingContext>> handlers = ArgumentCaptor.captor();
        ArgumentCaptor<Handler<RoutingContext>> failure = ArgumentCaptor.captor();
        verify(route, org.mockito.Mockito.times(3)).handler(handlers.capture());
        verify(route).failureHandler(failure.capture());
        timer.getValue().handle(17L);
        handlers.getAllValues().getLast().handle(context);
        failure.getValue().handle(context);
        verify(context).next();
        verify(response).setStatusCode(503);
        verify(response, never()).setStatusCode(400);
    }

    @Test
    void oversizedFailureDoesNotReplaceCompletedResponsesOrOtherFailures() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        when(router.route("/api/v1/*")).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        deadline().register(router);
        ArgumentCaptor<Handler<RoutingContext>> failure = ArgumentCaptor.captor();
        verify(route).failureHandler(failure.capture());
        when(context.statusCode()).thenReturn(500);
        failure.getValue().handle(context);
        verify(context).next();
        when(context.statusCode()).thenReturn(413);
        when(response.ended()).thenReturn(true);
        failure.getValue().handle(context);
        when(response.ended()).thenReturn(false);
        when(response.closed()).thenReturn(true);
        failure.getValue().handle(context);
        verify(response, never()).setStatusCode(400);
    }

    @ParameterizedTest
    @CsvSource({"PRIMARY,GET,UNAVAILABLE,CATALOG_UNAVAILABLE", "REPLICA,GET,REPLICA_UNAVAILABLE,READ_REPLICA_UNAVAILABLE",
            "REPLICA,POST,UNAVAILABLE,CATALOG_UNAVAILABLE", "PRIMARY,POST,UNAVAILABLE,CATALOG_UNAVAILABLE"})
    void expiresWithTheSelectedStoreErrorAndSuppressesLateSerialization(CatalogConfiguration.ReadProfile profile,
                                                                       String method, CatalogFailure.Reason reason,
                                                                       String code) throws IOException {
        when(configuration.readProfile()).thenReturn(profile);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));
        CatalogDeadline deadline = start();
        assertDoesNotThrow(deadline::check);
        timer.getValue().handle(17L);
        assertEquals(reason, assertThrows(CatalogFailure.class, deadline::check).reason());
        verify(response).end("{\"code\":\"" + code + "\",\"message\":\"Catalog request exceeded its deadline.\"}");
        WriterInterceptorContext writer = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(writer);
        verify(writer, never()).proceed();
    }

    @Test
    void cancelsFinishedRequestsAndDoesNotTimeThemOut() {
        start();
        end.getValue().handle(Future.succeededFuture());
        verify(vertx).cancelTimer(17L);
        timer.getValue().handle(17L);
        verify(response, never()).setStatusCode(503);
    }

    @Test
    void handlesAlreadyEndedAndDisconnectedResponsesWithoutNewWork() {
        CatalogDeadline deadline = start();
        when(response.ended()).thenReturn(true);
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, assertThrows(CatalogFailure.class, deadline::check).reason());
        timer.getValue().handle(17L);
        verify(response, never()).setStatusCode(503);
        when(response.ended()).thenReturn(false);
        when(response.closed()).thenReturn(true);
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, assertThrows(CatalogFailure.class, deadline::check).reason());
        timer.getValue().handle(17L);
        verify(response, never()).setStatusCode(503);
    }

    @Test
    void serializesActiveRequestsAndSkipsDisconnectedOnes() throws IOException {
        CatalogDeadline deadline = start();
        WriterInterceptorContext active = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(active);
        verify(active).proceed();
        when(response.ended()).thenReturn(true);
        WriterInterceptorContext ended = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(ended);
        verify(ended, never()).proceed();
        when(response.ended()).thenReturn(false);
        when(response.closed()).thenReturn(true);
        WriterInterceptorContext disconnected = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(disconnected);
        verify(disconnected, never()).proceed();
    }

    @Test
    void leavesResponsesOutsideCatalogRoutesUntouched() throws IOException {
        CatalogDeadline deadline = deadline();
        assertDoesNotThrow(deadline::check);
        WriterInterceptorContext writer = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(writer);
        verify(writer).proceed();
    }

    @Test
    void blockedSerializationCannotBlockTheDeadlineTimer() throws Exception {
        CatalogDeadline deadline = start();
        WriterInterceptorContext writer = mock(WriterInterceptorContext.class);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(ignored -> {
            writing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IOException("Response closed during output");
        }).when(writer).proceed();
        doAnswer(ignored -> {
            when(response.closed()).thenReturn(true);
            return Future.succeededFuture();
        }).when(connection).close();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                deadline.aroundWriteTo(writer);
                return null;
            });
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> timer.getValue().handle(17L));
                verify(connection).close();
                verify(response, never()).setStatusCode(503);
            } finally {
                release.countDown();
            }
            assertNull(output.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closesHeadersStartedOutsideTheSerializerInsteadOfRewritingStatus() {
        start();
        when(response.headWritten()).thenReturn(true);
        timer.getValue().handle(17L);
        verify(connection).close();
        verify(response, never()).setStatusCode(503);
    }

    @Test
    void preservesSerializationFailuresBeforeTimeout() throws IOException {
        CatalogDeadline deadline = start();
        WriterInterceptorContext writer = mock(WriterInterceptorContext.class);
        IOException failure = new IOException("Serialization failed");
        doThrow(failure).when(writer).proceed();
        assertSame(failure, assertThrows(IOException.class, () -> deadline.aroundWriteTo(writer)));
    }

    @Test
    void onlySuppressesAWriteFailureAfterTimeoutHasClosedTheResponse() throws IOException {
        CatalogDeadline deadline = start();
        WriterInterceptorContext writer = mock(WriterInterceptorContext.class);
        IllegalStateException failure = new IllegalStateException("Output failed");
        doAnswer(ignored -> {
            timer.getValue().handle(17L);
            throw failure;
        }).when(writer).proceed();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> deadline.aroundWriteTo(writer)));

        when(response.ended()).thenReturn(true);
        WriterInterceptorContext ended = mock(WriterInterceptorContext.class);
        deadline.aroundWriteTo(ended);
        verify(ended, never()).proceed();
    }

    @Test
    void suppressesLateWriteFailureWhenTheTimedOutResponseIsEnded() throws IOException {
        CatalogDeadline deadline = start();
        WriterInterceptorContext writer = mock(WriterInterceptorContext.class);
        doAnswer(ignored -> {
            timer.getValue().handle(17L);
            when(response.ended()).thenReturn(true);
            throw new IllegalStateException("Output already ended");
        }).when(writer).proceed();
        assertDoesNotThrow(() -> deadline.aroundWriteTo(writer));
        verify(connection).close();
    }

    private CatalogDeadline start() {
        CatalogDeadline deadline = deadline();
        deadline.start(context);
        verify(vertx).setTimer(eq(4000L), timer.capture());
        verify(context).addEndHandler(end.capture());
        return deadline;
    }

    private CatalogDeadline deadline() {
        return new CatalogDeadline(vertx, configuration, context, MemorySize.of(32768));
    }
}
