package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Buckets;
import co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate;
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
import my.javacraft.elastic.service.DateService;
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
    @Mock
    DateService dateService;

    // ── all-time (no window) ──────────────────────────────────────────────────

    @Test
    public void testRetrieveTopPostsAllTime() throws IOException {
        UserActivity userActivity = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:00:00.000Z");
        userActivity.setPostId("post-1");

        SearchResponse<UserActivity> aggResponse = buildAggResponse(Map.of("post-1", new long[]{100L, 10L}));

        Hit<UserActivity> hit = new Hit.Builder<UserActivity>()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .id("id-post-1")
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

        List<UserActivity> result = new TopService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("post-1", result.getFirst().getPostId());
    }

    @Test
    public void testRetrieveTopPostsNetScoreOrdering() throws IOException {
        // postA: 100 up - 80 down = 20 net   (higher raw upvotes but lower net)
        // postB:  60 up - 10 down = 50 net   (wins on net score)
        // ES candidate pool comes back postA-first (higher _count), Java must re-sort to postB-first
        SearchResponse<UserActivity> aggResponse = buildAggResponse(Map.of(
                "postA", new long[]{100L, 80L},
                "postB", new long[]{ 60L, 10L}
        ));

        UserActivity activityA = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:00:00.000Z");
        activityA.setPostId("postA");
        UserActivity activityB = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:00:00.000Z");
        activityB.setPostId("postB");

        SearchResponse<UserActivity> hydrateResponse = buildHitsResponse(List.of(activityA, activityB));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
                .thenReturn(aggResponse)
                .thenReturn(hydrateResponse);

        List<UserActivity> result = new TopService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postB", result.get(0).getPostId(), "postB (net=50) must rank above postA (net=20)");
        Assertions.assertEquals("postA", result.get(1).getPostId());
    }

    @Test
    public void testRetrieveTopPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<UserActivity> aggResponse = buildAggResponse(Map.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class))).thenReturn(aggResponse);

        List<UserActivity> result = new TopService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // ── windowed queries ──────────────────────────────────────────────────────

    @Test
    public void testRetrieveTopPostsByWeek() throws IOException {
        when(dateService.getNDaysBeforeDate(TopService.TopWindow.WEEK.getDays()))
                .thenReturn("2026-03-09T00:00:00.000Z");

        UserActivity userActivity = new UserActivity(UserClickTest.createHitCount(), "2026-03-12T08:00:00.000Z");
        userActivity.setPostId("post-recent");

        SearchResponse<UserActivity> aggResponse = buildAggResponse(Map.of("post-recent", new long[]{50L, 5L}));
        SearchResponse<UserActivity> hydrateResponse = buildHitsResponse(List.of(userActivity));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
                .thenReturn(aggResponse)
                .thenReturn(hydrateResponse);

        List<UserActivity> result = new TopService(esClient, dateService)
                .retrieveTopPosts(10, TopService.TopWindow.WEEK);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("post-recent", result.getFirst().getPostId());
    }

    @Test
    public void testAllWindowValuesHaveCorrectDays() {
        Assertions.assertEquals(1,   TopService.TopWindow.DAY.getDays());
        Assertions.assertEquals(7,   TopService.TopWindow.WEEK.getDays());
        Assertions.assertEquals(30,  TopService.TopWindow.MONTH.getDays());
        Assertions.assertEquals(365, TopService.TopWindow.YEAR.getDays());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a mocked aggregation SearchResponse.
     *
     * @param postData map of postId → [upvoteCount, downvoteCount]
     */
    private SearchResponse<UserActivity> buildAggResponse(Map<String, long[]> postData) {
        List<StringTermsBucket> postBuckets = postData.entrySet().stream()
                .map(e -> {
                    long[] d = e.getValue();

                    FilterAggregate upvotesFilter = mock(FilterAggregate.class);
                    when(upvotesFilter.docCount()).thenReturn(d[0]);
                    Aggregate upvotesAgg = mock(Aggregate.class);
                    when(upvotesAgg.filter()).thenReturn(upvotesFilter);

                    FilterAggregate downvotesFilter = mock(FilterAggregate.class);
                    when(downvotesFilter.docCount()).thenReturn(d[1]);
                    Aggregate downvotesAgg = mock(Aggregate.class);
                    when(downvotesAgg.filter()).thenReturn(downvotesFilter);

                    StringTermsBucket postBucket = mock(StringTermsBucket.class);
                    when(postBucket.key()).thenReturn(FieldValue.of(e.getKey()));
                    when(postBucket.aggregations()).thenReturn(Map.of(
                            "upvotes",   upvotesAgg,
                            "downvotes", downvotesAgg
                    ));
                    return postBucket;
                })
                .toList();

        Buckets<StringTermsBucket> buckets = mock(Buckets.class);
        when(buckets.array()).thenReturn(postBuckets);

        StringTermsAggregate sterms = mock(StringTermsAggregate.class);
        when(sterms.buckets()).thenReturn(buckets);

        Aggregate aggregate = mock(Aggregate.class);
        when(aggregate.sterms()).thenReturn(sterms);

        SearchResponse<UserActivity> response = mock(SearchResponse.class);
        when(response.aggregations()).thenReturn(Map.of(UserActivityService.POST_ID, aggregate));
        return response;
    }

    private SearchResponse<UserActivity> buildHitsResponse(List<UserActivity> activities) {
        List<Hit<UserActivity>> hits = activities.stream()
                .map(a -> new Hit.Builder<UserActivity>()
                        .index(UserActivityService.INDEX_USER_ACTIVITY)
                        .id("id-" + a.getPostId())
                        .source(a)
                        .build())
                .toList();

        HitsMetadata<UserActivity> hitsMetadata = mock(HitsMetadata.class);
        when(hitsMetadata.hits()).thenReturn(hits);

        SearchResponse<UserActivity> response = mock(SearchResponse.class);
        when(response.hits()).thenReturn(hitsMetadata);
        return response;
    }
}
