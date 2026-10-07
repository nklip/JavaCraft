package dev.nklip.javacraft.shardshop.workload.seeder.config;

import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.seeder.CatalogSeeder;
import dev.nklip.javacraft.shardshop.workload.seeder.RetryPolicy;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.net.http.HttpClient;

@Singleton
public class SeederWiring {
    @Produces
    @Singleton
    HttpClient http(SeederConfiguration config) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout()).build();
    }

    @Produces
    @Singleton
    CatalogSeeder seeder(SeederConfiguration config, HttpClient http) {
        JsonHttpClient product = new JsonHttpClient(http, config.productUrl(), config.requestTimeout());
        RetryPolicy retry = new RetryPolicy(config.maxAttempts(), config.retryBackoff(), config.maxRetryBackoff(),
                Thread::sleep);
        return new CatalogSeeder(new SeederDatasets(product, config.catalogVersion()), retry);
    }

    void close(@Disposes HttpClient http) {
        http.shutdownNow();
    }
}
