package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Buckets;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
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
public class HotServiceTest {

    @Mock
    ElasticsearchClient esClient;
    @Mock
    DateService dateService;

    @Test
    public void testComputeDecay() {
        HotService service = new HotService(esClient, dateService);

        // Age 0 → weight 1.0
        Assertions.assertEquals(1.0, service.computeDecay(0), 0.001);
        // At half-life (6h) → weight ≈ 0.5
        long halfLifeMs = (long) HotService.HOT_HALF_LIFE_HOURS * 3_600_000L;
        Assertions.assertEquals(0.5, service.computeDecay(halfLifeMs), 0.01);
        // At double half-life (12h) → weight ≈ 0.25
        Assertions.assertEquals(0.25, service.computeDecay(halfLifeMs * 2), 0.01);
    }

    @Test
    public void testRetrieveHotPostsOrdersByDecayedNetScore() throws IOException {
        when(dateService.getNDaysBeforeDate(HotService.HOT_WINDOW_DAYS))
                .thenReturn("2024-01-08T11:00:00.000Z");

        // Fixed reference time: 2024-01-15T11:00:00Z = 1705316400000 ms
        long nowMs = 1_705_316_400_000L;
        long recentBucketMs = nowMs - 1_800_000L;    // 30 min ago → decay ≈ 0.944
        long olderBucketMs  = nowMs - 25_200_000L;   // 7h ago    → decay ≈ 0.446

        // postA: recent bucket, 5 upvotes - 1 downvote = net 4 → score ≈ 4 × 0.944 = 3.78
        // postB: older bucket, 10 upvotes - 2 downvotes = net 8 → score ≈ 8 × 0.446 = 3.57
        // postA should rank above postB despite lower net count because it is more recent
        SearchResponse<UserActivity> hotResponse = buildHotAggResponse(Map.of(
                "postA", new long[]{recentBucketMs, 5L, 1L},
                "postB", new long[]{olderBucketMs,  10L, 2L}
        ));

        UserActivity activityA = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:30:00.000Z");
        activityA.setPostId("postA");
        UserActivity activityB = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T04:00:00.000Z");
        activityB.setPostId("postB");

        SearchResponse<UserActivity> hydrateResponse = buildHitsResponse(List.of(activityA, activityB));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
                .thenReturn(hotResponse)
                .thenReturn(hydrateResponse);

        HotService service = new HotService(esClient, dateService);
        List<UserActivity> result = service.retrieveHotPosts(10, nowMs);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postA", result.get(0).getPostId(), "postA should rank first (more recent)");
        Assertions.assertEquals("postB", result.get(1).getPostId());
    }

    @Test
    public void testRetrieveHotPostsExcludesNetNegativePosts() throws IOException {
        when(dateService.getNDaysBeforeDate(HotService.HOT_WINDOW_DAYS))
                .thenReturn("2024-01-08T11:00:00.000Z");

        long nowMs = 1_705_316_400_000L;
        long recentBucketMs = nowMs - 1_800_000L;

        // postA: net negative (1 upvote - 5 downvotes = -4) → excluded
        SearchResponse<UserActivity> hotResponse = buildHotAggResponse(Map.of(
                "postA", new long[]{recentBucketMs, 1L, 5L}
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
                .thenReturn(hotResponse);

        HotService service = new HotService(esClient, dateService);
        List<UserActivity> result = service.retrieveHotPosts(10, nowMs);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty(), "net-negative posts must be excluded from Hot");
    }

    @Test
    public void testRetrieveHotPostsReturnsEmptyWhenNoActivity() throws IOException {
        when(dateService.getNDaysBeforeDate(HotService.HOT_WINDOW_DAYS))
                .thenReturn("2024-01-08T11:00:00.000Z");

        SearchResponse<UserActivity> hotResponse = buildHotAggResponse(Map.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
                .thenReturn(hotResponse);

        HotService service = new HotService(esClient, dateService);
        List<UserActivity> result = service.retrieveHotPosts(10, 1_705_316_400_000L);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // --------------- helpers ---------------

    /**
     * Builds a mocked SearchResponse with nested terms → date_histogram → filter aggregations.
     * @param postData map of postId → [bucketTimestampMs, upvoteCount, downvoteCount]
     */
    private SearchResponse<UserActivity> buildHotAggResponse(Map<String, long[]> postData) {
        List<StringTermsBucket> postBuckets = postData.entrySet().stream()
                .map(e -> {
                    long[] d = e.getValue();

                    FilterAggregate upvotesFilter = mock(FilterAggregate.class);
                    when(upvotesFilter.docCount()).thenReturn(d[1]);
                    Aggregate upvotesAgg = mock(Aggregate.class);
                    when(upvotesAgg.filter()).thenReturn(upvotesFilter);

                    FilterAggregate downvotesFilter = mock(FilterAggregate.class);
                    when(downvotesFilter.docCount()).thenReturn(d[2]);
                    Aggregate downvotesAgg = mock(Aggregate.class);
                    when(downvotesAgg.filter()).thenReturn(downvotesFilter);

                    DateHistogramBucket hourBucket = mock(DateHistogramBucket.class);
                    when(hourBucket.key()).thenReturn(d[0]);
                    when(hourBucket.aggregations()).thenReturn(Map.of(
                            "upvotes", upvotesAgg,
                            "downvotes", downvotesAgg
                    ));

                    Buckets<DateHistogramBucket> hourBuckets = mock(Buckets.class);
                    when(hourBuckets.array()).thenReturn(List.of(hourBucket));

                    DateHistogramAggregate dhAgg = mock(DateHistogramAggregate.class);
                    when(dhAgg.buckets()).thenReturn(hourBuckets);

                    Aggregate byHourAgg = mock(Aggregate.class);
                    when(byHourAgg.dateHistogram()).thenReturn(dhAgg);

                    StringTermsBucket postBucket = mock(StringTermsBucket.class);
                    when(postBucket.key()).thenReturn(FieldValue.of(e.getKey()));
                    when(postBucket.aggregations()).thenReturn(Map.of("by_hour", byHourAgg));
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
