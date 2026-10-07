package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.config.CatalogConfiguration;
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

/** Includes body reception and worker dispatch in the four-second HTTP deadline. */
@Provider
@ApplicationScoped
public class CatalogDeadline implements WriterInterceptor {
    static final String STATE_KEY = CatalogDeadline.class.getName();
    private final Vertx vertx;
    private final boolean replicaReads;
    private final RoutingContext routingContext;
    private final long maximumBodyBytes;
    private final CatalogInput input = new CatalogInput();

    @Inject
    public CatalogDeadline(Vertx vertx, CatalogConfiguration configuration, RoutingContext routingContext,
                           @ConfigProperty(name = "quarkus.http.limits.max-body-size") MemorySize maximumBodySize) {
        this.vertx = vertx;
        this.replicaReads = configuration.readProfile() == CatalogConfiguration.ReadProfile.REPLICA;
        this.routingContext = routingContext;
        this.maximumBodyBytes = maximumBodySize.asLongValue();
    }

    void register(@Observes Router router) {
        router.route("/api/v1/*").order(-1000).handler(this::start);
        BodyHandler body = BodyHandler.create(false).setBodyLimit(maximumBodyBytes).setMergeFormAttributes(false);
        router.route("/api/v1/*").order(-999).handler(context -> {
            if (context.request().method() != HttpMethod.POST) {
                context.next();
                return;
            }
            try {
                input.requireJsonMediaType(context.request().headers().getAll("Content-Type"));
            } catch (CatalogFailure failure) {
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
            if (context.request().method() == HttpMethod.POST && context.body().isEmpty()) {
                invalidBody(context);
            } else {
                context.next();
            }
        });
        router.route("/api/v1/*").order(-997).failureHandler(this::rejectOversized);
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
        State state = new State(context.request().method() == HttpMethod.GET && replicaReads);
        context.put(STATE_KEY, state);
        long timer = vertx.setTimer(4000, ignored -> expire(context, state));
        context.addEndHandler(ignored -> {
            synchronized (state) {
                state.finished = true;
                vertx.cancelTimer(timer);
            }
        });
        context.next();
    }

    void check() {
        State state = routingContext.get(STATE_KEY);
        if (state != null && state.expired || routingContext.response().ended() || routingContext.response().closed()) {
            throw new CatalogFailure(replicaReads && routingContext.request().method() == HttpMethod.GET
                    ? CatalogFailure.Reason.REPLICA_UNAVAILABLE : CatalogFailure.Reason.UNAVAILABLE);
        }
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        State state = routingContext.get(STATE_KEY);
        if (state == null) {
            context.proceed();
            return;
        }
        synchronized (state) {
            if (state.expired || routingContext.response().ended() || routingContext.response().closed()) {
                return;
            }
            // Claim the response before serialization; never hold this monitor across output IO.
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
        synchronized (state) {
            if (state.finished || context.response().ended() || context.response().closed()) {
                return;
            }
            state.expired = true;
            if (state.responseStarted || context.response().headWritten()) {
                context.request().connection().close();
                return;
            }
            String code = state.replica ? "READ_REPLICA_UNAVAILABLE" : "CATALOG_UNAVAILABLE";
            context.response().setStatusCode(503).putHeader("Content-Type", "application/json")
                    .end("{\"code\":\"" + code + "\",\"message\":\"Catalog request exceeded its deadline.\"}");
        }
    }

    static final class State {
        private final boolean replica;
        private volatile boolean expired;
        private boolean finished;
        private boolean responseStarted;

        State(boolean replica) {
            this.replica = replica;
        }
    }
}
