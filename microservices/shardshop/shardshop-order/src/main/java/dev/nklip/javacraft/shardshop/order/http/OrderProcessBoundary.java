package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.order.config.OrderConfiguration;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Saga exposes its management listener, without accepting business HTTP traffic. */
@ApplicationScoped
public class OrderProcessBoundary {
    private final OrderConfiguration config;

    @Inject
    public OrderProcessBoundary(OrderConfiguration config) {
        this.config = config;
    }

    void register(@Observes Router router) {
        if (config.process() == OrderConfiguration.Process.SAGA) {
            router.route("/api/v1/*").order(-1001).handler(context -> context.response().setStatusCode(404).end());
            router.route("/orders/*").order(-1001).handler(context -> context.response().setStatusCode(404).end());
        }
    }
}
