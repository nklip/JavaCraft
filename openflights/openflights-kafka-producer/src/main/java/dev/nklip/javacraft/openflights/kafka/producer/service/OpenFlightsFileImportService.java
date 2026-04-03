package dev.nklip.javacraft.openflights.kafka.producer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import dev.nklip.javacraft.openflights.kafka.producer.model.OpenFlightsImportResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Orchestrates reading OpenFlights source files and publishing their records to Kafka.
 *
 * <p>Why this service exists:
 *
 * <p>The OpenFlights producer has a few clearly separate responsibilities:
 * - the data layer knows how to read and parse the raw {@code .dat} files
 * - the Kafka producer knows how to publish already-typed records to the correct topics
 * - something still has to coordinate the import use case itself
 *
 * <p>This class is that coordinator. It sits between the source-file reader and the Kafka publishing adapter and turns
 * "import the countries dataset" or "import the routes dataset" into a concrete workflow:
 * - read all records for one dataset
 * - publish them through the typed Kafka producer
 * - return a small result describing what was submitted
 *
 * <p>Main responsibilities of this service:
 *
 * <p>1. expose one import method per OpenFlights dataset
 * <p>2. delegate reading to {@link OpenFlightsDataReader}
 * <p>3. delegate per-record publication to {@link KafkaMessageProducer}
 * <p>4. parallelize publication in a bounded way for large datasets
 * <p>5. report how many records were submitted for a given import request
 *
 * <p>Why there is one import method per dataset:
 *
 * <p>The producer HTTP layer exposes one endpoint per source dataset, and Kafka uses one topic per entity type. Keeping
 * one import method per dataset preserves that clarity all the way through the service layer. Each method says exactly
 * what it imports and which typed sender it uses.
 *
 * <p>Why this logic belongs in a service instead of the controller:
 *
 * <p>Controllers should map HTTP requests and responses. They should not know how OpenFlights files are read, how
 * records are chunked, or how import parallelism is applied. Those are application-level business/workflow concerns, so
 * they belong here.
 *
 * <p>Why this logic does not belong in {@link OpenFlightsDataReader}:
 *
 * <p>The reader's responsibility is source-file access and parsing, not transport. If the reader also published to
 * Kafka, the data module and producer module concerns would become mixed together. This service keeps the boundary
 * clean: reading stays separate from publishing.
 *
 * <p>Why this logic does not belong in {@link KafkaMessageProducer}:
 *
 * <p>{@code KafkaMessageProducer} is intentionally a narrow adapter around Kafka publication. It should know how to
 * send records, not how to load entire datasets from disk or how to orchestrate an import workflow. This service keeps
 * those higher-level import decisions out of the Kafka adapter.
 *
 * <p>Why publication is parallelized:
 *
 * <p>Some OpenFlights datasets, especially routes, contain many records. Publishing them sequentially would make bulk
 * imports unnecessarily slow. This service therefore splits the dataset into chunks and submits those chunks to a
 * dedicated executor so multiple records can be published concurrently.
 *
 * <p>Why the parallelism is bounded:
 *
 * <p>The goal is to improve throughput without flooding the machine or creating an unbounded number of tasks. The
 * service therefore:
 * - caps workers to the available processors
 * - avoids creating more workers than records
 * - groups records into chunks instead of scheduling one task per record
 *
 * <p>That design keeps the import reasonably efficient while avoiding excessive scheduling overhead.
 *
 * <p>Why the service waits for all tasks to finish:
 *
 * <p>The import endpoints report submitted record counts for a completed import operation, not for a fire-and-forget
 * background launch. Joining all publication tasks here means that when an import method returns, the dataset has been
 * fully handed off to the Kafka producer logic for all records in that file.
 *
 * <p>In short, this class is the producer-side file-import orchestrator: it coordinates reading one OpenFlights source
 * dataset, publishing its typed records through Kafka, and doing that work with bounded parallelism suitable for large
 * imports.
 */
@Service
@RequiredArgsConstructor
public class OpenFlightsFileImportService {

    private final OpenFlightsDataReader dataReader;
    private final KafkaMessageProducer kafkaMessageProducer;

    @Qualifier("openFlightsImportExecutor")
    private final Executor importExecutor;

    public OpenFlightsImportResult importCountries() {
        List<Country> countries = dataReader.readCountries();
        publishInParallel(countries, kafkaMessageProducer::sendCountry);
        return new OpenFlightsImportResult("countries", countries.size());
    }

    public OpenFlightsImportResult importAirlines() {
        List<Airline> airlines = dataReader.readAirlines();
        publishInParallel(airlines, kafkaMessageProducer::sendAirline);
        return new OpenFlightsImportResult("airlines", airlines.size());
    }

    public OpenFlightsImportResult importAirports() {
        List<Airport> airports = dataReader.readAirports();
        publishInParallel(airports, kafkaMessageProducer::sendAirport);
        return new OpenFlightsImportResult("airports", airports.size());
    }

    public OpenFlightsImportResult importPlanes() {
        List<Plane> planes = dataReader.readPlanes();
        publishInParallel(planes, kafkaMessageProducer::sendPlane);
        return new OpenFlightsImportResult("planes", planes.size());
    }

    public OpenFlightsImportResult importRoutes() {
        List<Route> routes = dataReader.readRoutes();
        publishInParallel(routes, kafkaMessageProducer::sendRoute);
        return new OpenFlightsImportResult("routes", routes.size());
    }

    private <T> void publishInParallel(List<T> records, Consumer<T> sender) {
        if (records.isEmpty()) {
            return;
        }

        int workers = Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), records.size());
        List<List<T>> chunks = splitIntoChunks(records, workers);
        CompletableFuture<?>[] tasks = chunks.stream()
                .map(chunk -> CompletableFuture.runAsync(() -> chunk.forEach(sender), importExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(tasks).join();
    }

    private <T> List<List<T>> splitIntoChunks(List<T> records, int chunks) {
        int chunkSize = Math.max(1, (int) Math.ceil((double) records.size() / chunks));
        List<List<T>> partitions = new ArrayList<>();
        for (int start = 0; start < records.size(); start += chunkSize) {
            int end = Math.min(records.size(), start + chunkSize);
            partitions.add(records.subList(start, end));
        }
        return partitions;
    }
}
