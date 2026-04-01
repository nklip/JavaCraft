package dev.nklip.javacraft.elastic.app.service;

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
import dev.nklip.javacraft.elastic.app.config.ElasticsearchConstants;
import org.springframework.stereotype.Service;

/**
 * Handles creation of Elasticsearch indexes with their field mappings.
 * <p>
 * Two groups of indexes are managed:
 * <ul>
 *   <li><b>user-votes</b> – used by VoteController for ingestion and retrieval</li>
 *   <li><b>books / movies / music</b> – used by SearchController for full-text search</li>
 * </ul>
 * Index names for the search group match the lowercased {@code ContentCategory} enum values
 * that are listed in {@code metadata.json}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    static final String INDEX_BOOKS     = "books";
    static final String INDEX_COMPANIES = "companies";
    static final String INDEX_MOVIES    = "movies";
    static final String INDEX_MUSIC     = "music";
    static final String INDEX_PEOPLE    = "people";

    /**
     * Creates the {@code posts} index with typed field mappings.
     * <ul>
     *   <li>{@code postId}   – {@code keyword} (exact-match queries)</li>
     *   <li>{@code createdAt} – {@code date} with ISO-8601 format (sort queries)</li>
     * </ul>
     */
    public IndexCreationResult createPostsIndex() throws IOException {
        log.info("creating index '{}'...", ElasticsearchConstants.INDEX_POSTS);

        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("postId",    Property.of(p -> p.keyword(k -> k)));
        properties.put("author",    Property.of(p -> p.keyword(k -> k)));
        properties.put("createdAt", Property.of(p -> p.date(d -> d.format("strict_date_optional_time"))));
        properties.put("karma",        Property.of(p -> p.long_(l -> l)));
        properties.put("upvotes",      Property.of(p -> p.long_(l -> l)));
        properties.put("hotScore",     Property.of(p -> p.double_(d -> d)));
        properties.put("risingScore",  Property.of(p -> p.double_(d -> d)));
        properties.put("bestScore",    Property.of(p -> p.double_(d -> d)));

        return createIndex(ElasticsearchConstants.INDEX_POSTS, properties);
    }

    private final ElasticsearchClient esClient;

    public record IndexCreationResult(CreateIndexResponse response, boolean created) {
    }

    /**
     * Creates the {@code user-votes} index with typed field mappings.
     * <p>
     * Field types are chosen to match the immutable event document
     * used by {@code UserVote} and the queries in activity services:
     * <ul>
     *   <li>{@code timestamp} – {@code date} with ISO-8601 format (range/sort queries)</li>
     *   <li>{@code userId}, {@code postId}, {@code action}
     *       – {@code keyword} (exact-match term queries and aggregations)</li>
     * </ul>
     */
    public IndexCreationResult createUserVoteIndex() throws IOException {
        log.info("creating index '{}'...", ElasticsearchConstants.INDEX_USER_VOTES);

        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("timestamp", Property.of(p -> p.date(d -> d.format("strict_date_optional_time"))));
        properties.put("userId", Property.of(p -> p.keyword(k -> k)));
        properties.put("postId", Property.of(p -> p.keyword(k -> k)));
        properties.put("action", Property.of(p -> p.keyword(k -> k)));

        return createIndex(ElasticsearchConstants.INDEX_USER_VOTES, properties);
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

    /**
     * Creates the {@code companies} index with mixed field mappings.
     * <p>
     * Text fields (full-text search, match {@code metadata.json}):
     * {@code ceo}, {@code country}, {@code description}, {@code headquarters},
     * {@code industry}, {@code name}, {@code sector}, {@code website}.
     * <p>
     * Numeric fields (range/aggregation only, excluded from search):
     * {@code capitalization}(long), {@code employees}(long),
     * {@code founded}(integer), {@code rank}(integer).
     */
    public IndexCreationResult createCompaniesIndex() throws IOException {
        log.info("creating search index '{}'...", INDEX_COMPANIES);

        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("capitalization", Property.of(p -> p.long_(l -> l)));
        properties.put("ceo",            Property.of(p -> p.text(t -> t)));
        properties.put("country",        Property.of(p -> p.text(t -> t)));
        properties.put("description",    Property.of(p -> p.text(t -> t)));
        properties.put("employees",      Property.of(p -> p.long_(l -> l)));
        properties.put("founded",        Property.of(p -> p.integer(i -> i)));
        properties.put("headquarters",   Property.of(p -> p.text(t -> t)));
        properties.put("industry",       Property.of(p -> p.text(t -> t)));
        properties.put("name",           Property.of(p -> p.text(t -> t)));
        properties.put("rank",           Property.of(p -> p.integer(i -> i)));
        properties.put("sector",         Property.of(p -> p.text(t -> t)));
        properties.put("website",        Property.of(p -> p.text(t -> t)));

        return createIndex(INDEX_COMPANIES, properties);
    }

    /**
     * Creates the {@code people} index with mixed field mappings.
     * <p>
     * Text fields (full-text search, match {@code metadata.json}):
     * {@code name}, {@code reasons_for_being_famous}, {@code surname}.
     * <p>
     * Numeric fields (range/aggregation only, excluded from search):
     * {@code age}(integer), {@code ranking}(integer).
     * <p>
     * Date-like string fields (stored as text due to non-uniform formats):
     * {@code date_of_birth}, {@code date_of_death}.
     */
    public IndexCreationResult createPeopleIndex() throws IOException {
        log.info("creating search index '{}'...", INDEX_PEOPLE);

        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("age",                    Property.of(p -> p.integer(i -> i)));
        properties.put("date_of_birth",          Property.of(p -> p.text(t -> t)));
        properties.put("date_of_death",          Property.of(p -> p.text(t -> t)));
        properties.put("name",                   Property.of(p -> p.text(t -> t)));
        properties.put("ranking",                Property.of(p -> p.integer(i -> i)));
        properties.put("reasons_for_being_famous", Property.of(p -> p.text(t -> t)));
        properties.put("surname",                Property.of(p -> p.text(t -> t)));

        return createIndex(INDEX_PEOPLE, properties);
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
