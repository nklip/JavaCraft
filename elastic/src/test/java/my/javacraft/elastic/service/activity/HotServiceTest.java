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

//    @Test
//    public void testComputeTrendScore() {
//        HotService service = new HotService(esClient, dateService);
//
//        // High recent spike vs low baseline → high score
//        double spikeScore = service.computeTrendScore(10, 2);
//        // Low recent vs high baseline → low score
//        double noSpikeScore = service.computeTrendScore(1, 50);
//        // New item with zero baseline → score = recentCount
//        double newItemScore = service.computeTrendScore(3, 0);
//
//        Assertions.assertTrue(spikeScore > noSpikeScore, "spike should score higher than consistent item");
//        Assertions.assertEquals(3.0, newItemScore, 0.001, "new item score should equal recentCount / 1");
//    }
//
//    @Test
//    public void testRetrieveTrendingOrdersByScore() throws IOException {
//        // recordA: recent=5, baseline=2 → higher spike
//        // recordB: recent=2, baseline=10 → lower spike
//        when(dateService.getCurrentDate()).thenReturn("2024-01-15T11:00:00.000Z");
//        when(dateService.getNHoursBeforeDate(HotService.RECENT_WINDOW_HOURS))
//                .thenReturn("2024-01-15T10:00:00.000Z");
//        when(dateService.getNHoursBeforeDate(HotService.RECENT_WINDOW_HOURS + HotService.BASELINE_WINDOW_HOURS))
//                .thenReturn("2024-01-14T11:00:00.000Z");
//
//        UserActivity activityA = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:30:00.000Z");
//        activityA.setRecordId("recordA");
//
//        UserActivity activityB = new UserActivity(UserClickTest.createHitCount(), "2024-01-15T10:20:00.000Z");
//        activityB.setRecordId("recordB");
//
//        // Mock Query 1 — recent counts: recordA=5, recordB=2
//        SearchResponse<UserActivity> recentResponse = buildAggResponse(Map.of("recordA", 5L, "recordB", 2L));
//        // Mock Query 2 — baseline counts: recordA=2, recordB=10
//        SearchResponse<UserActivity> baselineResponse = buildAggResponse(Map.of("recordA", 2L, "recordB", 10L));
//        // Mock Query 3 — hydration: return activityA and activityB
//        SearchResponse<UserActivity> hydrateResponse = buildHitsResponse(List.of(activityA, activityB));
//
//        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
//        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
//                .thenReturn(recentResponse)
//                .thenReturn(baselineResponse)
//                .thenReturn(hydrateResponse);
//
//        HotService service = new HotService(esClient, dateService);
//        List<UserActivity> result = service.retrieveTrendingUserSearches(10);
//
//        Assertions.assertNotNull(result);
//        Assertions.assertEquals(2, result.size());
//        // recordA has higher trend score → must be first
//        Assertions.assertEquals("recordA", result.get(0).getRecordId());
//        Assertions.assertEquals("recordB", result.get(1).getRecordId());
//    }
//
//    @Test
//    public void testRetrieveTrendingReturnsEmptyWhenNoRecentActivity() throws IOException {
//        when(dateService.getCurrentDate()).thenReturn("2024-01-15T11:00:00.000Z");
//        when(dateService.getNHoursBeforeDate(HotService.RECENT_WINDOW_HOURS))
//                .thenReturn("2024-01-15T10:00:00.000Z");
//        when(dateService.getNHoursBeforeDate(HotService.RECENT_WINDOW_HOURS + HotService.BASELINE_WINDOW_HOURS))
//                .thenReturn("2024-01-14T11:00:00.000Z");
//
//        SearchResponse<UserActivity> emptyRecentResponse = buildAggResponse(Map.of());
//        SearchResponse<UserActivity> emptyBaselineResponse = buildAggResponse(Map.of());
//
//        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
//        when(esClient.search(any(SearchRequest.class), eq(UserActivity.class)))
//                .thenReturn(emptyRecentResponse)
//                .thenReturn(emptyBaselineResponse);
//
//        HotService service = new HotService(esClient, dateService);
//        List<UserActivity> result = service.retrieveTrendingUserSearches(10);
//
//        Assertions.assertNotNull(result);
//        Assertions.assertTrue(result.isEmpty());
//    }

    // --------------- helpers ---------------

    private SearchResponse<UserActivity> buildAggResponse(Map<String, Long> countsByRecordId) {
        List<StringTermsBucket> bucketList = countsByRecordId.entrySet().stream()
                .map(e -> {
                    StringTermsBucket bucket = mock(StringTermsBucket.class);
                    when(bucket.key()).thenReturn(FieldValue.of(e.getKey()));
                    when(bucket.docCount()).thenReturn(e.getValue());
                    return bucket;
                })
                .toList();

        Buckets<StringTermsBucket> buckets = mock(Buckets.class);
        when(buckets.array()).thenReturn(bucketList);

        StringTermsAggregate sterms = mock(StringTermsAggregate.class);
        when(sterms.buckets()).thenReturn(buckets);

        Aggregate aggregate = mock(Aggregate.class);
        when(aggregate.sterms()).thenReturn(sterms);

        SearchResponse<UserActivity> response = mock(SearchResponse.class);
        when(response.aggregations()).thenReturn(Map.of(UserActivityService.RECORD_ID, aggregate));
        return response;
    }

    private SearchResponse<UserActivity> buildHitsResponse(List<UserActivity> activities) {
        List<Hit<UserActivity>> hits = activities.stream()
                .map(a -> new Hit.Builder<UserActivity>()
                        .index(UserActivityService.INDEX_USER_ACTIVITY)
                        .id("id-" + a.getRecordId())
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
