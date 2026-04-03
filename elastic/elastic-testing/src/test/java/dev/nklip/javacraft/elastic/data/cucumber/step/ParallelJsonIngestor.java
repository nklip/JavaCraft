package dev.nklip.javacraft.elastic.data.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Ingests JSON fixture documents into an Elasticsearch index in parallel without changing the
 * resulting index state compared to the old serial implementation.
 *
 * <p>The important detail is that JSON datasets in these tests are not guaranteed to have unique
 * generated ids. Some fixtures, such as music and people, intentionally collapse multiple source
 * rows onto the same Elasticsearch document id. A naive "one task per row" strategy would make
 * those rows race each other and produce version-conflict retries or non-deterministic last-write
 * ordering.
 *
 * <p>To keep the behavior stable, the ingestor first groups input entities by the id produced by
 * {@code idFactory}. Each group then becomes a unit of work:
 *
 * <ul>
 *     <li>different ids are safe to process in parallel, because they update different ES documents</li>
 *     <li>the same id is processed serially, in original encounter order, so repeated upserts keep
 *     the same semantics as the previous single-threaded loop</li>
 * </ul>
 *
 * <p>This gives us most of the throughput benefit for large datasets like {@code books.json}
 * while preserving deterministic behavior for duplicate-id datasets.
 */
@Slf4j
final class ParallelJsonIngestor {

    private ParallelJsonIngestor() {
    }

    /**
     * Performs parallel ingestion for a JSON fixture file.
     *
     * <p>Workflow:
     *
     * <ol>
     *     <li>Group entities by their generated Elasticsearch document id.</li>
     *     <li>Create a bounded worker pool sized to the smaller of:
     *         number of unique ids and available processors.</li>
     *     <li>Submit one task per generated id.</li>
     *     <li>Wait for all tasks and rethrow any checked IO failures as IO failures so the
     *         surrounding Cucumber step still fails in a predictable way.</li>
     * </ol>
     *
     * <p>The returned count is the number of source rows processed, not the number of distinct
     * Elasticsearch documents that remain in the index afterwards.
     */
    static int ingest(
            ElasticsearchClient esClient,
            String index,
            List<LinkedHashMap<String, Object>> entities,
            Function<LinkedHashMap<String, Object>, String> idFactory) throws IOException {
        if (entities.isEmpty()) {
            return 0;
        }

        List<DocumentsById> documentsByIds = groupByGeneratedId(entities, idFactory);
        int threads = Math.min(documentsByIds.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));

        int ingestedDocuments = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> futures = documentsByIds.stream()
                    .map(documentsById -> pool.submit(() -> ingestDocumentsById(esClient, index, documentsById)))
                    .toList();
            for (Future<Integer> future : futures) {
                try {
                    ingestedDocuments += future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("JSON ingestion interrupted for index '%s'".formatted(index), e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (cause instanceof UncheckedIOException ioException) {
                        throw ioException.getCause();
                    }
                    throw new IllegalStateException("JSON ingestion failed for index '%s'".formatted(index), cause);
                }
            }
        }

        log.info("ingested {} documents into '{}' using {} worker threads across {} generated ids",
                ingestedDocuments, index, threads, documentsByIds.size());
        return ingestedDocuments;
    }

    /**
     * Processes all rows that share the same generated document id.
     *
     * <p>These rows are intentionally kept serial. If they were executed concurrently, two
     * updates against the same Elasticsearch document could contend and trigger retries or
     * reorder the effective "last row wins" outcome.
     */
    private static int ingestDocumentsById(
            ElasticsearchClient esClient,
            String index,
            DocumentsById documentsById) throws IOException {
        int ingestedDocuments = 0;
        for (LinkedHashMap<String, Object> entity : documentsById.entities()) {
            UpdateRequest<Object, Object> updateRequest = new UpdateRequest.Builder<>()
                    .index(index)
                    .id(documentsById.id())
                    .doc(entity)
                    .upsert(entity)
                    .build();

            UpdateResponse<Object> updateResponse = esClient.update(updateRequest, Object.class);
            ingestedDocuments++;
            log.debug("document with id='{}' was ingested into '{}' with result '{}'",
                    documentsById.id(), index, updateResponse.result());
        }
        return ingestedDocuments;
    }

    /**
     * Builds stable work items keyed by generated id.
     *
     * <p>A {@link LinkedHashMap} is used on purpose:
     *
     * <ul>
     *     <li>keys keep the order of first appearance in the JSON file</li>
     *     <li>rows inside each key keep their original file order</li>
     * </ul>
     *
     * <p>That makes the ingestion easier to reason about and keeps duplicate-id behavior aligned
     * with the former serial implementation.
     */
    static List<DocumentsById> groupByGeneratedId(
            List<LinkedHashMap<String, Object>> entities,
            Function<LinkedHashMap<String, Object>, String> idFactory) {
        Map<String, List<LinkedHashMap<String, Object>>> entitiesById = new LinkedHashMap<>();
        for (LinkedHashMap<String, Object> entity : entities) {
            String id = idFactory.apply(entity);
            entitiesById.computeIfAbsent(id, unused -> new ArrayList<>()).add(entity);
        }

        List<DocumentsById> documentsByIds = new ArrayList<>();
        for (Map.Entry<String, List<LinkedHashMap<String, Object>>> entry : entitiesById.entrySet()) {
            documentsByIds.add(new DocumentsById(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return List.copyOf(documentsByIds);
    }

    /**
     * Immutable unit of work for one generated Elasticsearch id.
     *
     * @param id       generated document id used in the update request
     * @param entities all source rows that should be applied to that id, in original order
     */
    record DocumentsById(String id, List<LinkedHashMap<String, Object>> entities) {
    }

}
