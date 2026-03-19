package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
import co.elastic.clients.elasticsearch.core.msearch.MultisearchBody;
import co.elastic.clients.elasticsearch.core.msearch.MultisearchHeader;
import co.elastic.clients.elasticsearch.core.msearch.RequestItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.ContentSearchRequest;
import my.javacraft.elastic.model.ContentCategory;
import my.javacraft.elastic.model.ContentCategoryMetadata;
import my.javacraft.elastic.service.query.FuzzyFactory;
import my.javacraft.elastic.service.query.IntervalFactory;
import my.javacraft.elastic.service.query.SpanFactory;
import my.javacraft.elastic.service.query.WildcardFactory;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    // Floating point number used to decrease or increase the relevance scores of the query.
    // Boost values are relative to the default value of 1.0.
    // A boost value between 0 and 1.0 decreases the relevance score.
    // A value greater than 1.0 increases the relevance score.
    public static final Float NEUTRAL_VALUE = 1f;

    private static final String SYNOPSIS = "synopsis";

    private final ElasticsearchClient esClient;
    private final MetadataService metadataService;
    private final WildcardFactory wildcardFactory;
    private final FuzzyFactory fuzzyFactory;
    private final IntervalFactory intervalFactory;
    private final SpanFactory spanFactory;

    /**
     * The wildcard query is an expensive query due to the nature of how it was implemented.
     * Few other expensive queries are the range, prefix, fuzzy, regex, and join queries as well as others.
     */
    public List<Object> wildcardSearch(ContentSearchRequest contentSearchRequest) throws IOException, ElasticsearchException {
        Query wildcardQuery = wildcardFactory.createQuery(SYNOPSIS, contentSearchRequest.getPattern());

        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(contentSearchRequest.getType())
                .query(wildcardQuery)
                .build();
        //SearchRequest searchRequest = SearchRequest.of(r -> r.query(q -> q.bool(b -> b.must(wildcardQuery))));

        SearchResponse<Object> searchResponse = esClient.search(searchRequest, Object.class);

        return searchResponse.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
    }

    /**
     * The fuzzy query is an expensive query due to the nature of how it was implemented.
     * Few other expensive queries are the range, prefix, fuzzy, regex, and join queries as well as others.
     */
    public List<Object> fuzzySearch(ContentSearchRequest contentSearchRequest) throws IOException, ElasticsearchException {
        Query fuzzyQuery = fuzzyFactory.createQuery(SYNOPSIS, contentSearchRequest.getPattern());

        SearchRequest searchRequest = SearchRequest.of(r -> r
                .query(q -> q
                        .bool(b -> b
                                .must(fuzzyQuery)
                        )
                )
        );

        SearchResponse<Object> searchResponse = esClient.search(searchRequest, Object.class);

        return searchResponse.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
    }

    public List<Object> intervalSearch(ContentSearchRequest contentSearchRequest) throws IOException, ElasticsearchException {
        Query intervalsQuery = intervalFactory.createQuery(SYNOPSIS, contentSearchRequest.getPattern());

        SearchRequest searchRequest = SearchRequest.of(sr -> sr
                .query(intervalsQuery)
        );

        SearchResponse<Object> searchResponse = esClient.search(searchRequest, Object.class);

        return searchResponse.hits().hits().stream().map(Hit::source).collect(Collectors.toList());

    }

    public List<Object> spanSearch(ContentSearchRequest contentSearchRequest) throws IOException, ElasticsearchException {
        Query spanQuery = spanFactory.createQuery(SYNOPSIS, contentSearchRequest.getPattern());

        SearchRequest searchRequest = SearchRequest.of(r -> r
                .query(q -> q
                        .bool(b -> b
                                .must(spanQuery)
                        )
                )
        );

        SearchResponse<Object> searchResponse = esClient.search(searchRequest, Object.class);

        return searchResponse.hits().hits().stream().map(Hit::source).collect(Collectors.toList());
    }

    public List<Document> search(ContentSearchRequest contentSearchRequest) throws IOException, ElasticsearchException {
        List<RequestItem> requestItems = createRequestItems(contentSearchRequest);

        // executing several searches with a single API request.
        MsearchRequest msearchRequest = new MsearchRequest.Builder().searches(requestItems).build();

        // filtering results
        List<MultiSearchResponseItem<Map>> searchResponses =
                esClient.msearch(msearchRequest, Map.class)
                        .responses();

        List<List<Document>> results = searchResponses
                .stream()
                .filter(MultiSearchResponseItem::isResult)
                .map(response -> response
                        .result()
                        .hits()
                        .hits()
                        .stream()
                        .filter(hit -> hit.id() != null)
                        .filter(hit -> hit.source() != null)
                        .map(hit -> Document.from(hit.source()))
                        .toList()
                )
                .toList();

        List<Document> searchResults = new ArrayList<>();
        results.forEach(searchResults::addAll);
        return searchResults;
    }

    private List<RequestItem> createRequestItems(ContentSearchRequest contentSearchRequest) {
        List<RequestItem> requestItems = new ArrayList<>();
        
        // get search fields for each type
        Set<ContentCategoryMetadata> contentCategoryMetadataList = metadataService.getContentCategoryMetadata();

        // filter type we look
        Set<ContentCategoryMetadata> contentCategoryToUseInQuery = contentCategoryMetadataList
                .stream()
                .filter(s -> s.getContentCategory().equals(ContentCategory.valueByName(contentSearchRequest.getType())))
                .findFirst()
                .map(Collections::singleton)
                .orElse(Collections.emptySet());

        if (contentCategoryToUseInQuery.isEmpty() || ContentCategory.valueByName(contentSearchRequest.getType()) == ContentCategory.ALL) {
            contentCategoryToUseInQuery = contentCategoryMetadataList;
        }
        
        // generate queries
        for (ContentCategoryMetadata ccMetadata : contentCategoryToUseInQuery) {
            addToRequestItems(contentSearchRequest, ccMetadata, requestItems);
        }

        return requestItems;
    }

    private void addToRequestItems(ContentSearchRequest contentSearchRequest, ContentCategoryMetadata contentCategoryMetadata, List<RequestItem> requestItems) {
        List<BoolQuery> boolTypeQueries = new ArrayList<>();
        List<String> searchFields = contentCategoryMetadata.getSearchFields();
        // N fields -> N wildcard queries
        searchFields.forEach(field -> {
            Query query = wildcardFactory.createQuery(field, contentSearchRequest.getPattern());

            boolTypeQueries.add(new BoolQuery.Builder()
                    .boost(NEUTRAL_VALUE)
                    .must(query)
                    .build()
            );
        });

        // N wildcard queries -> N request items
        boolTypeQueries.forEach(boolQuery -> {
            requestItems.add(
                    new RequestItem.Builder()
                            .header(new MultisearchHeader.Builder()
                                    .index(contentCategoryMetadata.getContentCategory().toString().toLowerCase())
                                    .build()
                            )
                            .body(new MultisearchBody.Builder()
                                    .query(boolQuery._toQuery())
                                    .build()
                            )
                            .build()
            );
        });
    }

}
