package my.javacraft.elastic.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import my.javacraft.elastic.api.model.ClientType;
import my.javacraft.elastic.api.model.ContentCategory;
import my.javacraft.elastic.api.model.ContentCategoryMetadata;
import my.javacraft.elastic.api.model.ContentSearchRequest;
import my.javacraft.elastic.app.service.query.FuzzyFactory;
import my.javacraft.elastic.app.service.query.IntervalFactory;
import my.javacraft.elastic.app.service.query.SpanFactory;
import my.javacraft.elastic.app.service.query.WildcardFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock ElasticsearchClient  esClient;
    @Mock MetadataService      metadataService;
    @Mock WildcardFactory      wildcardFactory;
    @Mock FuzzyFactory         fuzzyFactory;
    @Mock IntervalFactory      intervalFactory;
    @Mock SpanFactory          spanFactory;

    SearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new SearchService(esClient, metadataService, wildcardFactory, fuzzyFactory, intervalFactory, spanFactory);
    }

    // --- wildcardSearch field resolution ---

    @Test
    void testWildcardSearch_usesMetadataFieldsForMatchedCategory() throws IOException {
        ContentCategoryMetadata metadata = new ContentCategoryMetadata(
                ContentCategory.COMPANIES, List.of("ceo", "country", "name"));
        when(metadataService.getContentCategoryMetadata()).thenReturn(Set.of(metadata));
        when(wildcardFactory.createQuery(any(), any())).thenReturn(mock(Query.class));
        stubEmptySearchResponse();

        searchService.wildcardSearch(request("companies", "Cupertino"));

        verify(wildcardFactory).createQuery(eq("ceo"),     eq("Cupertino"));
        verify(wildcardFactory).createQuery(eq("country"), eq("Cupertino"));
        verify(wildcardFactory).createQuery(eq("name"),    eq("Cupertino"));
        verify(wildcardFactory, times(3)).createQuery(any(), eq("Cupertino"));
    }

    @Test
    void testWildcardSearch_noQueryBuiltWhenCategoryNotInMetadata() throws IOException {
        when(metadataService.getContentCategoryMetadata()).thenReturn(Collections.emptySet());
        stubEmptySearchResponse();

        searchService.wildcardSearch(request("unknown", "test"));

        verify(wildcardFactory, never()).createQuery(any(), any());
    }

    // --- fuzzySearch field resolution ---

    @Test
    void testFuzzySearch_usesMetadataFieldsForMatchedCategory() throws IOException {
        ContentCategoryMetadata metadata = new ContentCategoryMetadata(
                ContentCategory.BOOKS, List.of("synopsis", "title"));
        when(metadataService.getContentCategoryMetadata()).thenReturn(Set.of(metadata));
        when(fuzzyFactory.createQuery(any(), any())).thenReturn(mock(Query.class));
        stubEmptySearchResponse();

        searchService.fuzzySearch(request("books", "frankenstein"));

        verify(fuzzyFactory).createQuery(eq("synopsis"), eq("frankenstein"));
        verify(fuzzyFactory).createQuery(eq("title"),    eq("frankenstein"));
        verify(fuzzyFactory, times(2)).createQuery(any(), eq("frankenstein"));
    }

    @Test
    void testFuzzySearch_noQueryBuiltWhenCategoryNotInMetadata() throws IOException {
        when(metadataService.getContentCategoryMetadata()).thenReturn(Collections.emptySet());
        stubEmptySearchResponse();

        searchService.fuzzySearch(request("unknown", "test"));

        verify(fuzzyFactory, never()).createQuery(any(), any());
    }

    // --- intervalSearch field resolution ---

    @Test
    void testIntervalSearch_usesMetadataFieldsForMatchedCategory() throws IOException {
        ContentCategoryMetadata metadata = new ContentCategoryMetadata(
                ContentCategory.BOOKS, List.of("synopsis", "title"));
        when(metadataService.getContentCategoryMetadata()).thenReturn(Set.of(metadata));
        when(intervalFactory.createQuery(any(), any())).thenReturn(mock(Query.class));
        stubEmptySearchResponse();

        searchService.intervalSearch(request("books", "victor frankenstein"));

        verify(intervalFactory).createQuery(eq("synopsis"), eq("victor frankenstein"));
        verify(intervalFactory).createQuery(eq("title"),    eq("victor frankenstein"));
        verify(intervalFactory, times(2)).createQuery(any(), eq("victor frankenstein"));
    }

    @Test
    void testIntervalSearch_noQueryBuiltWhenCategoryNotInMetadata() throws IOException {
        when(metadataService.getContentCategoryMetadata()).thenReturn(Collections.emptySet());
        stubEmptySearchResponse();

        searchService.intervalSearch(request("unknown", "test"));

        verify(intervalFactory, never()).createQuery(any(), any());
    }

    // --- spanSearch field resolution ---

    @Test
    void testSpanSearch_usesMetadataFieldsForMatchedCategory() throws IOException {
        ContentCategoryMetadata metadata = new ContentCategoryMetadata(
                ContentCategory.BOOKS, List.of("synopsis", "title"));
        when(metadataService.getContentCategoryMetadata()).thenReturn(Set.of(metadata));
        when(spanFactory.createQuery(any(), any())).thenReturn(mock(Query.class));
        stubEmptySearchResponse();

        searchService.spanSearch(request("books", "victor frankenstein"));

        verify(spanFactory).createQuery(eq("synopsis"), eq("victor frankenstein"));
        verify(spanFactory).createQuery(eq("title"),    eq("victor frankenstein"));
        verify(spanFactory, times(2)).createQuery(any(), eq("victor frankenstein"));
    }

    @Test
    void testSpanSearch_noQueryBuiltWhenCategoryNotInMetadata() throws IOException {
        when(metadataService.getContentCategoryMetadata()).thenReturn(Collections.emptySet());
        stubEmptySearchResponse();

        searchService.spanSearch(request("unknown", "test"));

        verify(spanFactory, never()).createQuery(any(), any());
    }

    // --- helpers ---

    private static ContentSearchRequest request(String type, String pattern) {
        ContentSearchRequest req = new ContentSearchRequest();
        req.setType(type);
        req.setPattern(pattern);
        req.setClient(ClientType.WEB.toString());
        return req;
    }

    @SuppressWarnings("unchecked")
    private void stubEmptySearchResponse() throws IOException {
        HitsMetadata<Object> mockHits = mock(HitsMetadata.class);
        when(mockHits.hits()).thenReturn(Collections.emptyList());

        SearchResponse<Object> mockResponse = mock(SearchResponse.class);
        when(mockResponse.hits()).thenReturn(mockHits);

        when(esClient.search(any(SearchRequest.class), eq(Object.class))).thenReturn(mockResponse);
    }
}
