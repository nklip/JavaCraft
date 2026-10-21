package dev.nklip.javacraft.shardshop.product.catalog;

import dev.nklip.javacraft.shardshop.common.Sha256;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure.Reason.*;
import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.*;

/** ID ownership, placement and immutable creation semantics, independent of HTTP and JDBC. */
public final class CatalogService {
    private final CatalogStore store;
    private final IdGenerator generator;
    private final ShardRouter router;
    private final ShardTopology topology;
    private final boolean replicaReads;
    private final int maxCandidates;

    /** Checked service settings; the wiring creates them before any bean reserves a generator ID. */
    public record Settings(boolean replicaReads, int maxIdCandidates) {
        public Settings {
            if (maxIdCandidates < 1 || maxIdCandidates > 65536) {
                throw new IllegalArgumentException("Catalog ID candidate budget must be in 1..65536");
            }
        }
    }

    public CatalogService(CatalogStore store, IdGenerator generator, ShardRouter router,
                          ShardTopology topology, Settings settings) {
        this.store = store;
        this.generator = generator;
        this.router = router;
        this.topology = topology;
        this.replicaReads = settings.replicaReads();
        this.maxCandidates = settings.maxIdCandidates();
    }

    public Creation<Seller> createSeller(String key, SellerCreation creation) {
        Shard shard = sellerKeyShard(creation.region(), key);
        return store.write(shard, session -> {
            session.lock(lockKey("seller", creation.region(), key));
            var existing = session.sellerByKey(creation.region(), key);
            if (existing.isPresent()) {
                Seller seller = existing.orElseThrow();
                if (!seller.companyName().equals(creation.companyName()) || !seller.region().equals(creation.region())
                        || creation.countryCode() != null && !creation.countryCode().equals(seller.countryCode())) {
                    throw new CatalogFailure(SELLER_CONFLICT);
                }
                return new Creation<>(seller, false);
            }
            final List<String> currencies;
            try {
                currencies = CurrencyPolicy.currencies(creation.region(), creation.countryCode());
            } catch (IllegalArgumentException failure) {
                throw new CatalogFailure(INVALID_REQUEST);
            }
            long id = sellerId(shard);
            session.insertSeller(id, key, creation);
            return new Creation<>(new Seller(
                    id,
                    creation.companyName(),
                    creation.region(),
                    currencies.stream().collect(Collectors.toUnmodifiableMap(currency -> currency, currency -> "0.00")),
                    creation.countryCode()
            ), true);
        });
    }

    public Seller sellerByKey(String region, String key) {
        return store.read(sellerKeyShard(region, key), replicaReads, session ->
                session.sellerByKey(region, key).orElseThrow(() -> new CatalogFailure(SELLER_NOT_FOUND)));
    }

    public Seller seller(long id) {
        return store.read(router.route(id), replicaReads, session ->
                session.seller(id).orElseThrow(() -> new CatalogFailure(SELLER_NOT_FOUND)));
    }

    public Creation<Product> createProduct(long sellerId, String key, ProductCreation creation) {
        return store.write(router.route(sellerId), session -> {
            session.lock(lockKey("product", Long.toString(sellerId), key));
            var existing = session.productByKey(sellerId, key);
            if (existing.isPresent()) {
                Product product = existing.orElseThrow();
                if (!product.creation().equals(creation)) {
                    throw new CatalogFailure(PRODUCT_CONFLICT);
                }
                return new Creation<>(product, false);
            }
            Seller seller = session.seller(sellerId).orElseThrow(() -> new CatalogFailure(MISSING_PARENT));
            if (!CurrencyPolicy.permits(seller.region(), seller.countryCode(), creation.currency())
                    || !validPrice(creation.price(), creation.currency()) || !validPrice(creation.unitCost(), creation.currency())) {
                throw new CatalogFailure(INVALID_REQUEST);
            }
            long id = nextId();
            session.insertProduct(id, sellerId, key, creation);
            return new Creation<>(new Product(sellerId, id, creation, creation.initialStock(), seller.region(), seller.countryCode()), true);
        });
    }

    public Product productByKey(long sellerId, String key) {
        return store.read(router.route(sellerId), replicaReads, session ->
                session.productByKey(sellerId, key).orElseThrow(() -> new CatalogFailure(PRODUCT_NOT_FOUND)));
    }

    public Product product(long sellerId, long productId) {
        return store.read(router.route(sellerId), replicaReads, session ->
                session.product(sellerId, productId).orElseThrow(() -> new CatalogFailure(PRODUCT_NOT_FOUND)));
    }

    public ProductPage products(String region, int limit, long after) {
        if (limit < 1 || limit > 100 || after < 0) {
            throw new CatalogFailure(INVALID_REQUEST);
        }
        List<ListedProduct> candidates = store.products(regionalShards(region), replicaReads, region, limit + 1, after)
                .stream().sorted(Comparator.comparingLong(ListedProduct::productId)).limit(limit + 1L).toList();
        boolean more = candidates.size() > limit;
        List<ListedProduct> items = more ? candidates.subList(0, limit) : candidates;
        return new ProductPage(region, items, more ? items.getLast().productId() : null);
    }

    private static boolean validPrice(String value, String currency) {
        try {
            BigDecimal amount = new BigDecimal(value);
            return amount.signum() >= 0 && CurrencyPolicy.validAmount(amount, currency);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private List<Shard> regionalShards(String region) {
        List<Shard> eligible = topology.shards().stream().filter(shard -> shard.region().equals(region)).toList();
        if (eligible.isEmpty()) {
            throw new CatalogFailure(INVALID_REQUEST);
        }
        return eligible;
    }

    private Shard sellerKeyShard(String region, String key) {
        List<Shard> eligible = regionalShards(region);
        // A fixed key home makes the transaction lock and uniqueness constraint global within a region.
        int index = Sha256.unsignedDigest("seller\0" + region + "\0" + key)
                .mod(BigInteger.valueOf(eligible.size())).intValue();
        return eligible.get(index);
    }

    private long sellerId(Shard target) {
        for (int attempt = 0; attempt < maxCandidates; attempt++) {
            long candidate = nextId();
            if (router.route(candidate).equals(target)) {
                return candidate;
            }
        }
        throw new CatalogFailure(ID_UNAVAILABLE);
    }

    private long nextId() {
        try {
            return generator.nextId();
        } catch (IdGenerationUnavailableException failure) {
            throw new CatalogFailure(ID_UNAVAILABLE);
        }
    }

    private static long lockKey(String collection, String scope, String key) {
        return Sha256.unsignedDigest(collection + "\0" + scope + "\0" + key).longValue();
    }
}
