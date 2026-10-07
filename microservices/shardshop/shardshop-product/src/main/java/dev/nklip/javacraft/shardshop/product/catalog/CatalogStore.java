package dev.nklip.javacraft.shardshop.product.catalog;

import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.util.Optional;
import java.util.function.Function;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.*;

/** One bounded database phase; writes commit atomically only after work succeeds. */
public interface CatalogStore {
    <T> T read(Shard shard, boolean replica, Function<Session, T> work);

    <T> T write(Shard shard, Function<Session, T> work);

    interface Session {
        void lock(long key);

        Optional<Seller> sellerByKey(String region, String key);

        Optional<Seller> seller(long id);

        Optional<Product> productByKey(long sellerId, String key);

        Optional<Product> product(long sellerId, long productId);

        void insertSeller(long id, String key, SellerCreation creation);

        void insertProduct(long id, long sellerId, String key, ProductCreation creation);
    }
}
