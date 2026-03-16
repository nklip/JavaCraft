package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Buckets;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.model.UserClickTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked"})
@ExtendWith(MockitoExtension.class)
public class TopServiceTest {

    @Mock
    ElasticsearchClient esClient;

    @Test
    public void testRetrieveTopPostsFiltersUpvotesOnly() throws IOException {
        UserActivity userActivity = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:00:00.000Z");

        // --- Mock Query 1: aggregation response ---
        StringTermsBucket bucket = mock(StringTermsBucket.class);
        when(bucket.key()).thenReturn(FieldValue.of("12345"));

        Buckets<StringTermsBucket> buckets = mock(Buckets.class);
        when(buckets.array()).thenReturn(List.of(bucket));

        StringTermsAggregate sterms = mock(StringTermsAggregate.class);
        when(sterms.buckets()).thenReturn(buckets);

        Aggregate aggregate = mock(Aggregate.class);
        when(aggregate.sterms()).thenReturn(sterms);

        SearchResponse<UserActivity> aggResponse = mock(SearchResponse.class);
        when(aggResponse.aggregations()).thenReturn(Map.of(UserActivityService.POST_ID, aggregate));

        // --- Mock Query 2: hydration response ---
        Hit<UserActivity> hit = new Hit.Builder<UserActivity>()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .id("auto-id-1")
                .source(userActivity)
                .build();

        HitsMetadata<UserActivity> hitsMetadata = mock(HitsMetadata.class);
        when(hitsMetadata.hits()).thenReturn(List.of(hit));

        SearchResponse<UserActivity> hydrateResponse = mock(SearchResponse.class);
        when(hydrateResponse.hits()).thenReturn(hitsMetadata);

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
                .thenReturn(aggResponse)
                .thenReturn(hydrateResponse);

        // --- Act ---
        TopService service = new TopService(esClient);
        List<UserActivity> result = service.retrieveTopPosts(10);

        // --- Assert ---
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());

        UserActivity resultActivity = result.getFirst();
        Assertions.assertEquals("12345", resultActivity.getPostId());
        Assertions.assertEquals("nl8888", resultActivity.getUserId());
        Assertions.assertEquals("People", resultActivity.getSearchType());
        Assertions.assertEquals("UPVOTE", resultActivity.getAction()); // stored normalized
        Assertions.assertEquals("Nikita", resultActivity.getSearchValue());
        Assertions.assertEquals("2024-01-15T10:00:00.000Z", resultActivity.getTimestamp());
    }

    @Test
    public void testRetrieveTopPostsReturnsEmptyWhenNoUpvotes() throws IOException {
        Buckets<StringTermsBucket> buckets = mock(Buckets.class);
        when(buckets.array()).thenReturn(List.of());

        StringTermsAggregate sterms = mock(StringTermsAggregate.class);
        when(sterms.buckets()).thenReturn(buckets);

        Aggregate aggregate = mock(Aggregate.class);
        when(aggregate.sterms()).thenReturn(sterms);

        SearchResponse<UserActivity> aggResponse = mock(SearchResponse.class);
        when(aggResponse.aggregations()).thenReturn(Map.of(UserActivityService.POST_ID, aggregate));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class))).thenReturn(aggResponse);

        TopService service = new TopService(esClient);
        List<UserActivity> result = service.retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }
}
