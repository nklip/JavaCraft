package dev.nklip.javacraft.shardshop.workload.producer;

import dev.nklip.javacraft.shardshop.workload.producer.load.OrderProducer;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

import java.util.Objects;

@QuarkusMain
public class OrderProducerApplication implements QuarkusApplication {
    private final OrderProducer producer;
    private final Runnable waitForExit;
    private final Runnable exitAfterFailure;

    @Inject
    public OrderProducerApplication(OrderProducer producer) {
        this(producer, Quarkus::waitForExit, () -> Quarkus.asyncExit(1));
    }

    OrderProducerApplication(OrderProducer producer, Runnable waitForExit, Runnable exitAfterFailure) {
        this.producer = Objects.requireNonNull(producer);
        this.waitForExit = Objects.requireNonNull(waitForExit);
        this.exitAfterFailure = Objects.requireNonNull(exitAfterFailure);
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            producer.start(exitAfterFailure);
            waitForExit.run();
        } finally {
            producer.stop();
        }
        return producer.failed() ? 1 : 0;
    }
}
