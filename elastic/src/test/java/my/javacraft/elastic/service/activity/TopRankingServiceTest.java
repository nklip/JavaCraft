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
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.model.UserVote;
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
public class TopRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;
    @Mock
    DateService dateService;

    // ── all-time (no window) ──────────────────────────────────────────────────

    @Test
    public void testRetrieveTopPostsAllTime() throws IOException {
        SearchResponse<UserVote> aggResponse = buildAggResponse(Map.of("post-1", new long[]{100L, 10L}));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(aggResponse);

        List<PostPreview> result = new TopRankingService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("post-1", result.getFirst().getPostId());
        Assertions.assertEquals(90L, result.getFirst().getKarma());   // 100 - 10
    }

    @Test
    public void testRetrieveTopPostsNetScoreOrdering() throws IOException {
        // postA: 100 up - 80 down = 20 net   (higher raw upvotes but lower net)
        // postB:  60 up - 10 down = 50 net   (wins on net score)
        // ES candidate pool comes back postA-first (higher _count), Java must re-sort to postB-first
        SearchResponse<UserVote> aggResponse = buildAggResponse(Map.of(
                "postA", new long[]{100L, 80L},
                "postB", new long[]{ 60L, 10L}
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(aggResponse);

        List<PostPreview> result = new TopRankingService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postB", result.get(0).getPostId(), "postB (net=50) must rank above postA (net=20)");
        Assertions.assertEquals(50L, result.get(0).getKarma());
        Assertions.assertEquals("postA", result.get(1).getPostId());
        Assertions.assertEquals(20L, result.get(1).getKarma());
    }

    @Test
    public void testRetrieveTopPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<UserVote> aggResponse = buildAggResponse(Map.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class))).thenReturn(aggResponse);

        List<PostPreview> result = new TopRankingService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // ── windowed queries ──────────────────────────────────────────────────────

    @Test
    public void testRetrieveTopPostsByWeek() throws IOException {
        when(dateService.getNDaysBeforeDate(TopRankingService.TopWindow.WEEK.getDays()))
                .thenReturn("2026-03-09T00:00:00.000Z");

        SearchResponse<UserVote> aggResponse = buildAggResponse(Map.of("post-recent", new long[]{50L, 5L}));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(aggResponse);

        List<PostPreview> result = new TopRankingService(esClient, dateService)
                .retrieveTopPosts(10, TopRankingService.TopWindow.WEEK);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("post-recent", result.getFirst().getPostId());
        Assertions.assertEquals(45L, result.getFirst().getKarma());   // 50 - 5
    }

    @Test
    public void testAllWindowValuesHaveCorrectDays() {
        Assertions.assertEquals(1,   TopRankingService.TopWindow.DAY.getDays());
        Assertions.assertEquals(7,   TopRankingService.TopWindow.WEEK.getDays());
        Assertions.assertEquals(30,  TopRankingService.TopWindow.MONTH.getDays());
        Assertions.assertEquals(365, TopRankingService.TopWindow.YEAR.getDays());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a mocked aggregation SearchResponse.
     *
     * @param postData map of postId → [upvoteCount, downvoteCount]
     */
    private SearchResponse<UserVote> buildAggResponse(Map<String, long[]> postData) {
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

        SearchResponse<UserVote> response = mock(SearchResponse.class);
        when(response.aggregations()).thenReturn(Map.of(Constants.POST_ID, aggregate));
        return response;
    }
}
