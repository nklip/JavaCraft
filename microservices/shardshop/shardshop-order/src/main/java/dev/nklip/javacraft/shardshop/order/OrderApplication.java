package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;

@QuarkusMain
public class OrderApplication implements QuarkusApplication {
    private final StockWorker stock;

    public OrderApplication(StockWorker stock) {
        this.stock = stock;
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        stock.start();
        try {
            Quarkus.waitForExit();
        } finally {
            stock.close();
        }
        return 0;
    }
}
