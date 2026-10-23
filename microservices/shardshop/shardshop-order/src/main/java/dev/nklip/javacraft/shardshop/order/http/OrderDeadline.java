package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

/** Includes body reception and worker dispatch in the ten-second HTTP deadline. */
@Provider
@ApplicationScoped
public class OrderDeadline implements WriterInterceptor {
    static final String STATE_KEY = OrderDeadline.class.getName();
    private final Vertx vertx;
    private final RoutingContext routingContext;
    private final long maximumBodyBytes;
    private final OrderInput input = new OrderInput();
    private final OrderWriteMetrics metrics;

    @Inject
    public OrderDeadline(Vertx vertx, RoutingContext routingContext,
                         @ConfigProperty(name = "quarkus.http.limits.max-body-size") MemorySize maximumBodySize,
                         OrderWriteMetrics metrics) {
        this.metrics = metrics;
        this.vertx = vertx;
        this.routingContext = routingContext;
        this.maximumBodyBytes = maximumBodySize.asLongValue();
    }

    void register(@Observes Router router) {
        router.route("/api/v1/*").order(-1000).handler(this::start);
        BodyHandler body = BodyHandler.create(false).setBodyLimit(maximumBodyBytes).setMergeFormAttributes(false);
        router.route("/api/v1/*").order(-999).handler(context -> {
            if (!hasBody(context)) {
                context.next();
                return;
            }
            try {
                input.requireJsonMediaType(context.request().headers().getAll("Content-Type"));
            } catch (OrderFailure failure) {
                invalidBody(context);
                return;
            }
            if (context.request().isEnded()) {
                context.next();
            } else {
                body.handle(context);
            }
        });
        router.route("/api/v1/*").order(-998).handler(context -> {
            if (completed(context)) {
                return;
            }
            // BodyHandler may consume an empty stream without allocating a buffer. REST would reread that stream.
            if (hasBody(context) && context.body().isEmpty()) {
                invalidBody(context);
            } else {
                context.next();
            }
        });
        router.route("/api/v1/*").order(-997).failureHandler(this::rejectOversized);
    }

    private boolean hasBody(RoutingContext context) {
        return context.request().method() == HttpMethod.POST || context.request().method() == HttpMethod.PUT;
    }

    private void rejectOversized(RoutingContext context) {
        if (completed(context)) {
            return;
        }
        if (context.statusCode() != 413) {
            context.next();
            return;
        }
        invalidBody(context);
    }

    private boolean completed(RoutingContext context) {
        State state = context.get(STATE_KEY);
        return state != null && state.expired || context.response().ended() || context.response().closed();
    }

    private void invalidBody(RoutingContext context) {
        if (!context.response().ended() && !context.response().closed()) {
            context.response().setStatusCode(400).putHeader("Content-Type", "application/json")
                    .putHeader("Connection", "close")
                    .end("{\"code\":\"INVALID_REQUEST\",\"message\":\"Request does not match the input contract.\"}")
                    .onComplete(ignored -> context.request().connection().close());
        }
    }

    void start(RoutingContext context) {
        State state = new State();
        long started = System.nanoTime();
        OrderWriteMetrics.Window measurement = context.request().method() == null ? null
                : metrics.operation(context.request().method().name(), context.normalizedPath());
        context.put(STATE_KEY, state);
        long timer = vertx.setTimer(10000, ignored -> expire(context, state));
        context.addEndHandler(ignored -> {
            synchronized (state) {
                state.finished = true;
            }
            vertx.cancelTimer(timer);
            if (measurement != null) {
                measurement.record(System.nanoTime() - started, context.response().getStatusCode());
            }
        });
        context.next();
    }

    void check() {
        State state = routingContext.get(STATE_KEY);
        if (state != null && state.expired || routingContext.response().ended() || routingContext.response().closed()) {
            throw new OrderFailure(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE);
        }
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        State state = routingContext.get(STATE_KEY);
        if (state == null) {
            context.proceed();
            return;
        }
        if (routingContext.response().ended() || routingContext.response().closed()) {
            return;
        }
        synchronized (state) {
            if (state.expired || state.finished) {
                return;
            }
            // Only arbitrate ownership here: Vert.x can invoke the end handler under its connection lock.
            state.responseStarted = true;
        }
        try {
            context.proceed();
        } catch (IOException | RuntimeException failure) {
            if (!state.expired || !routingContext.response().ended() && !routingContext.response().closed()) {
                throw failure;
            }
        }
    }

    private void expire(RoutingContext context, State state) {
        boolean responseStarted;
        synchronized (state) {
            if (state.finished || state.expired) {
                return;
            }
            state.expired = true;
            responseStarted = state.responseStarted;
        }
        // Even response status reads can take the connection lock; never nest it with state.
        if (context.response().ended() || context.response().closed()) {
            return;
        }
        if (responseStarted || context.response().headWritten()) {
            context.request().connection().close();
            return;
        }
        String code = "ORDER_STORE_UNAVAILABLE";
        context.response().setStatusCode(503).putHeader("Content-Type", "application/json")
                .end("{\"code\":\"" + code + "\",\"message\":\"Order request exceeded its deadline.\"}");
    }

    static final class State {
        private volatile boolean expired;
        private boolean finished;
        private boolean responseStarted;
    }
}
