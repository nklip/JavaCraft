package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.config.ProfitWorker;
import dev.nklip.javacraft.shardshop.order.messaging.OrderResultConsumer;

@QuarkusMain
public class OrderApplication implements QuarkusApplication {
    private final StockWorker stock;
    private final OrderCdcRelay cdc;
    private final OrderResultConsumer results;
    private final ProfitWorker profit;

    public OrderApplication(StockWorker stock, OrderCdcRelay cdc, OrderResultConsumer results, ProfitWorker profit) {
        this.stock = stock;
        this.cdc = cdc;
        this.results = results;
        this.profit = profit;
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            cdc.start();
            results.start();
            stock.start();
            profit.start();
            Quarkus.waitForExit();
        } finally {
            try {
                results.close();
            } finally {
                try {
                    profit.close();
                } finally {
                    try {
                        stock.close();
                    } finally {
                        cdc.close();
                    }
                }
            }
        }
        return 0;
    }
}
