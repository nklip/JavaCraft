package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.QuarkusApplication;
import dev.nklip.javacraft.shardshop.order.config.OrderConfiguration;
import dev.nklip.javacraft.shardshop.order.config.OrderOperationalMetrics;
import org.jboss.logging.Logger;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.config.ProfitWorker;
import dev.nklip.javacraft.shardshop.order.config.ReconciliationWorker;
import dev.nklip.javacraft.shardshop.order.messaging.OrderResultConsumer;
import dev.nklip.javacraft.shardshop.order.cdc.TransportCleanupWorker;

@QuarkusMain
public class OrderApplication implements QuarkusApplication {
    private final OrderConfiguration config;
    private final StockWorker stock;
    private final OrderCdcRelay cdc;
    private final OrderResultConsumer results;
    private final ProfitWorker profit;
    private final ReconciliationWorker reconciliation;
    private final TransportCleanupWorker cleanup;
    private final OrderOperationalMetrics metrics;

    public OrderApplication(StockWorker stock, OrderCdcRelay cdc, OrderResultConsumer results, ProfitWorker profit,
                            ReconciliationWorker reconciliation, TransportCleanupWorker cleanup,
                            OrderOperationalMetrics metrics, OrderConfiguration config) {
        this.config = config;
        this.stock = stock;
        this.cdc = cdc;
        this.results = results;
        this.profit = profit;
        this.reconciliation = reconciliation;
        this.cleanup = cleanup;
        this.metrics = metrics;
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            if (config.process() == OrderConfiguration.Process.SAGA) {
                cdc.start();
                results.start();
                stock.start();
                profit.start();
                reconciliation.start();
                cleanup.start();
                metrics.startSnapshots();
            }
            Logger.getLogger(OrderApplication.class).infof("ShardShop order process ready: %s",
                    config.process().name().toLowerCase(java.util.Locale.ROOT));
            Quarkus.waitForExit();
        } finally {
            try {
                cleanup.close();
            } finally {
                try {
                    reconciliation.close();
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
            }
        }
        return 0;
    }
}
