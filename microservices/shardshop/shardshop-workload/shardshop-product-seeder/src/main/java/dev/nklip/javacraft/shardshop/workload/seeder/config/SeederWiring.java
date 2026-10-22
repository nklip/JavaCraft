package dev.nklip.javacraft.shardshop.workload.seeder.config;

import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets;
import dev.nklip.javacraft.shardshop.workload.seeder.load.CatalogSeeder;
import dev.nklip.javacraft.shardshop.workload.seeder.load.RetryPolicy;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeederRuns;
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
    WorkloadControl control(SeederConfiguration config) {
        WorkloadControl.Settings settings;
        try {
            settings = CatalogSeeder.controlSettings(config.requestRate());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("shardshop.seeder.request-rate must be 0..1000 with at most three decimals");
        }
        if (!config.interactive() && settings.defaultRate().signum() == 0) {
            throw new IllegalArgumentException("shardshop.seeder.request-rate must be positive in the bootstrap Job");
        }
        return new WorkloadControl(settings, WorkloadControl.Phase.IDLE);
    }

    @Produces
    @Singleton
    CatalogSeeder seeder(SeederConfiguration config, HttpClient http, WorkloadControl control) {
        JsonHttpClient product = new JsonHttpClient(http, config.productUrl(), config.requestTimeout());
        RetryPolicy retry = new RetryPolicy(config.maxAttempts(), config.retryBackoff(), config.maxRetryBackoff(),
                Thread::sleep, control);
        return new CatalogSeeder(new SeederDatasets(product, config.catalogVersion()), retry);
    }

    @Produces
    @Singleton
    SeederRuns runs(CatalogSeeder seeder, WorkloadControl control) {
        return new SeederRuns(seeder, control);
    }

    void stop(@Disposes SeederRuns runs) {
        runs.stop();
    }

    void close(@Disposes HttpClient http) {
        http.shutdownNow();
    }
}
