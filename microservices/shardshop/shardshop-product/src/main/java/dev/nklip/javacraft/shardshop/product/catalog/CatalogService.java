package dev.nklip.javacraft.shardshop.product.catalog;

import dev.nklip.javacraft.shardshop.common.Sha256;
import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.math.BigInteger;
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

    public CatalogService(CatalogStore store, IdGenerator generator, ShardRouter router,
                          ShardTopology topology, boolean replicaReads, int maxCandidates) {
        if (maxCandidates < 1 || maxCandidates > 65536) {
            throw new IllegalArgumentException("Catalog ID candidate budget must be in 1..65536");
        }
        this.store = store;
        this.generator = generator;
        this.router = router;
        this.topology = topology;
        this.replicaReads = replicaReads;
        this.maxCandidates = maxCandidates;
    }

    public Creation<Seller> createSeller(String key, SellerCreation creation) {
        Shard shard = sellerKeyShard(creation.region(), key);
        return store.write(shard, session -> {
            session.lock(lockKey("seller", creation.region(), key));
            var existing = session.sellerByKey(creation.region(), key);
            if (existing.isPresent()) {
                Seller seller = existing.orElseThrow();
                if (!seller.companyName().equals(creation.companyName()) || !seller.region().equals(creation.region())) {
                    throw new CatalogFailure(SELLER_CONFLICT);
                }
                return new Creation<>(seller, false);
            }
            long id = sellerId(shard);
            session.insertSeller(id, key, creation);
            return new Creation<>(new Seller(
                    id,
                    creation.companyName(),
                    creation.region(),
                    Map.of("USD", "0.00", "EUR", "0.00")
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
            if (session.seller(sellerId).isEmpty()) {
                throw new CatalogFailure(MISSING_PARENT);
            }
            long id = nextId();
            session.insertProduct(id, sellerId, key, creation);
            return new Creation<>(new Product(sellerId, id, creation, creation.initialStock()), true);
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

    private Shard sellerKeyShard(String region, String key) {
        List<Shard> eligible = topology.shards().stream().filter(shard -> shard.region().equals(region)).toList();
        if (eligible.isEmpty()) {
            throw new CatalogFailure(INVALID_REQUEST);
        }
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
