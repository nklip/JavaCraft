package dev.nklip.javacraft.shardshop.workload.reader.load;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A loopback product API with service-issued IDs for catalog-v1. It counts lookups, reads and the TCP connections
 * that sent them, identified by the client address and port.
 */
final class FakeProductServer implements AutoCloseable {
    private static final Pattern SELLER_KEY = Pattern.compile("/api/v1/sellers/by-key/([^/]+)");
    private static final Pattern PRODUCT_KEY = Pattern.compile("/api/v1/sellers/([0-9]+)/products/by-key/([^/]+)");
    private static final Pattern PRODUCT = Pattern.compile("/api/v1/sellers/([0-9]+)/products/([0-9]+)");

    final AtomicInteger sellerLookups = new AtomicInteger();
    final AtomicInteger productLookups = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();
    final Set<InetSocketAddress> connections = ConcurrentHashMap.newKeySet();
    final Set<String> missingKeys = ConcurrentHashMap.newKeySet();
    volatile long lookupDelayMillis;
    volatile long readDelayMillis;

    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final Map<String, SellerDefinition> sellersByKey = new HashMap<>();
    private final Map<String, ProductDefinition> productsByKey = new HashMap<>();
    private final Map<String, String> ids = new HashMap<>();
    private final Map<String, ProductDefinition> productsByIssuedIds = new HashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpServer server;

    FakeProductServer() throws IOException {
        long next = 9_007_199_254_740_993L;
        for (SellerDefinition seller : catalog.sellers()) {
            sellersByKey.put(seller.key(), seller);
            ids.put(seller.key(), Long.toString(next++));
        }
        for (ProductDefinition product : catalog.products()) {
            productsByKey.put(product.key(), product);
            ids.put(product.key(), Long.toString(next++));
            productsByIssuedIds.put(issued(product.key()), product);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sellers/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The issued seller ID of a seller key, or "sellerId/productId" of a product key. */
    String issued(String key) {
        ProductDefinition product = productsByKey.get(key);
        return product == null ? ids.get(key) : ids.get(product.sellerKey()) + "/" + ids.get(key);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        connections.add(exchange.getRemoteAddress());
        String path = exchange.getRequestURI().getPath();
        ObjectNode body = null;
        Matcher matcher;
        if ((matcher = SELLER_KEY.matcher(path)).matches()) {
            sellerLookups.incrementAndGet();
            pause(lookupDelayMillis);
            SellerDefinition seller = sellersByKey.get(matcher.group(1));
            if (seller != null && !missingKeys.contains(seller.key())
                    && ("region=" + seller.region()).equals(exchange.getRequestURI().getQuery())) {
                body = seller(seller);
            }
        } else if ((matcher = PRODUCT_KEY.matcher(path)).matches()) {
            productLookups.incrementAndGet();
            pause(lookupDelayMillis);
            ProductDefinition product = productsByKey.get(matcher.group(2));
            if (product != null && !missingKeys.contains(product.key())
                    && matcher.group(1).equals(ids.get(product.sellerKey()))) {
                body = product(product);
            }
        } else if ((matcher = PRODUCT.matcher(path)).matches()) {
            reads.incrementAndGet();
            pause(readDelayMillis);
            ProductDefinition product = productsByIssuedIds.get(matcher.group(1) + "/" + matcher.group(2));
            body = product == null ? null : product(product);
        }
        byte[] response = (body == null ? JsonNodeFactory.instance.objectNode().put("code", "PRODUCT_NOT_FOUND")
                .put("message", "Not found.") : body).toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = exchange.getResponseBody()) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, response.length);
            output.write(response);
        } catch (IOException closedByClient) {
            // The reader closed this connection after its deadline.
        }
    }

    private ObjectNode seller(SellerDefinition seller) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("sellerId", ids.get(seller.key()))
                .put("companyName", seller.companyName()).put("region", seller.region());
        body.set("profitsEarned", JsonNodeFactory.instance.objectNode().put("USD", "0.00").put("EUR", "0.00"));
        return body;
    }

    private ObjectNode product(ProductDefinition product) {
        return JsonNodeFactory.instance.objectNode().put("sellerId", ids.get(product.sellerKey()))
                .put("productId", ids.get(product.key())).put("name", product.name())
                .put("description", product.description()).put("price", product.price())
                .put("unitCost", product.unitCost()).put("currency", product.currency())
                .put("initialStock", product.initialStock()).put("stock", product.initialStock());
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException closing) {
            Thread.currentThread().interrupt();
        }
    }
}
