package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;

@QuarkusMain
public class OrderApplication implements QuarkusApplication {
    private final StockWorker stock;
    private final OrderCdcRelay cdc;

    public OrderApplication(StockWorker stock, OrderCdcRelay cdc) {
        this.stock = stock;
        this.cdc = cdc;
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            cdc.start();
            stock.start();
            Quarkus.waitForExit();
        } finally {
            stock.close();
            cdc.close();
        }
        return 0;
    }
}
