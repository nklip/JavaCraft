package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import org.springframework.stereotype.Service;

/**
 * Handles creation of Elasticsearch indexes with their field mappings.
 * <p>
 * Two groups of indexes are managed:
 * <ul>
 *   <li><b>user-activity</b> – used by UserActivityController for ingestion and retrieval</li>
 *   <li><b>books / movies / music</b> – used by SearchController for full-text search</li>
 * </ul>
 * Index names for the search group match the lowercased {@code SeekType} enum values
 * that are listed in {@code metadata.json}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    static final String INDEX_BOOKS = "books";
    static final String INDEX_MOVIES = "movies";
    static final String INDEX_MUSIC = "music";

    private final ElasticsearchClient esClient;

    public record IndexCreationResult(CreateIndexResponse response, boolean created) {
    }

    /**
     * Creates the {@code user-activity} index with typed field mappings.
     * <p>
     * Field types are chosen to match the immutable event document
     * used by {@code UserActivity} and the queries in activity services:
     * <ul>
     *   <li>{@code timestamp} – {@code date} with ISO-8601 format (range/sort queries)</li>
     *   <li>{@code userId}, {@code postId}, {@code action}
     *       – {@code keyword} (exact-match term queries and aggregations)</li>
     * </ul>
     */
    public IndexCreationResult createUserActivityIndex() throws IOException {
        log.info("creating index '{}'...", Constants.INDEX_USER_ACTIVITY);

        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("timestamp", Property.of(p -> p.date(d -> d.format("strict_date_optional_time"))));
        properties.put("userId", Property.of(p -> p.keyword(k -> k)));
        properties.put("postId", Property.of(p -> p.keyword(k -> k)));
        properties.put("action", Property.of(p -> p.keyword(k -> k)));

        return createIndex(Constants.INDEX_USER_ACTIVITY, properties);
    }

    /**
     * Creates the {@code books} index.
     * Fields match the search fields configured in {@code metadata.json}:
     * {@code name}, {@code author}, {@code synopsis}.
     */
    public IndexCreationResult createBooksIndex() throws IOException {
        return createSearchIndex(INDEX_BOOKS, "name", "author", "synopsis");
    }

    /**
     * Creates the {@code movies} index.
     * Fields match the search fields configured in {@code metadata.json}:
     * {@code name}, {@code director}, {@code synopsis}.
     */
    public IndexCreationResult createMoviesIndex() throws IOException {
        return createSearchIndex(INDEX_MOVIES, "name", "director", "synopsis");
    }

    /**
     * Creates the {@code music} index.
     * Fields match the search fields configured in {@code metadata.json}:
     * {@code band}, {@code album}, {@code name}, {@code lyrics}.
     */
    public IndexCreationResult createMusicIndex() throws IOException {
        return createSearchIndex(INDEX_MUSIC, "band", "album", "name", "lyrics");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a search index where every field is mapped as {@code text}
     * to support full-text queries (wildcard, fuzzy, interval, span).
     */
    private IndexCreationResult createSearchIndex(String indexName, String... textFields) throws IOException {
        log.info("creating search index '{}'...", indexName);

        Map<String, Property> properties = new LinkedHashMap<>();
        for (String field : textFields) {
            properties.put(field, Property.of(p -> p.text(t -> t)));
        }

        return createIndex(indexName, properties);
    }

    private IndexCreationResult createIndex(String indexName, Map<String, Property> properties) throws IOException {
        ElasticsearchIndicesClient indicesClient = esClient.indices();
        ExistsRequest existsRequest = new ExistsRequest.Builder()
                .index(indexName)
                .build();
        BooleanResponse existsResponse = indicesClient.exists(existsRequest);
        if (existsResponse.value()) {
            log.info("index '{}' already exists, nothing to create", indexName);
            CreateIndexResponse noOpResponse = CreateIndexResponse.of(builder -> builder
                    .index(indexName)
                    .acknowledged(true)
                    .shardsAcknowledged(true)
            );
            return new IndexCreationResult(noOpResponse, false);
        }

        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(indexName)
                .mappings(m -> m.properties(properties))
        );
        CreateIndexResponse createdResponse = indicesClient.create(request);
        log.info("index '{}' created (acknowledged={})", indexName, createdResponse.acknowledged());
        return new IndexCreationResult(createdResponse, true);
    }
}
