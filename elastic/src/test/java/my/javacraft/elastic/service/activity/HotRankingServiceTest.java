package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Buckets;
import co.elastic.clients.elasticsearch._types.aggregations.FilterAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.MinAggregate;
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
public class HotRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;

    // ── unit tests for computeHotScore ────────────────────────────────────────

    @Test
    public void testComputeHotScorePositiveVotes() {
        HotRankingService service = new HotRankingService(esClient);

        // Arrange: first vote exactly TIME_SCALE seconds after the epoch anchor
        //   seconds = 45 000, order = log₁₀(100) = 2.0
        //   expected = 2.0 + 45000 / 45000 = 3.0
        long firstSeenMs = (HotRankingService.EPOCH_ANCHOR_SECONDS + (long) HotRankingService.TIME_SCALE) * 1_000L;
        double score = service.computeHotScore(110L, 10L, firstSeenMs);   // net = 100

        Assertions.assertEquals(3.0, score, 0.001);
    }

    @Test
    public void testComputeHotScoreZeroVotes() {
        HotRankingService service = new HotRankingService(esClient);

        // score = 0 → order = log₁₀(1) × 0 = 0; hot_score = pure time component
        long firstSeenMs = (HotRankingService.EPOCH_ANCHOR_SECONDS + (long) HotRankingService.TIME_SCALE) * 1_000L;
        double score = service.computeHotScore(0L, 0L, firstSeenMs);

        Assertions.assertEquals(1.0, score, 0.001);   // 0 + 45000/45000
    }

    @Test
    public void testComputeHotScoreNegativeVotes() {
        HotRankingService service = new HotRankingService(esClient);

        // score = -9 → order = log₁₀(9) × (-1) ≈ -0.954
        // time component = 1.0  →  hot_score ≈ 0.046 (still positive — time dominates)
        long firstSeenMs = (HotRankingService.EPOCH_ANCHOR_SECONDS + (long) HotRankingService.TIME_SCALE) * 1_000L;
        double score = service.computeHotScore(1L, 10L, firstSeenMs);   // net = -9

        Assertions.assertTrue(score > 0, "time component must dominate a small negative order");
        Assertions.assertEquals(1.0 - Math.log10(9), score, 0.001);
    }

    // ── integration-style tests for retrieveHotPosts ─────────────────────────

    @Test
    public void testRetrieveHotPostsOrdersBySubmissionTime() throws IOException {
        // postA submitted 1 day later than postB — same net votes, so time decides ranking
        long anchorMs = HotRankingService.EPOCH_ANCHOR_SECONDS * 1_000L;
        long recentMs = anchorMs + 200_000_000L;          // newer
        long olderMs  = recentMs - 86_400_000L;           // 1 day earlier

        // postA: 5 net, newer → hot_score = log₁₀(5) + (recent−anchor)/45000
        // postB: 5 net, older → hot_score = log₁₀(5) + (older−anchor)/45000
        // postA wins because recentMs > olderMs
        SearchResponse<UserVote> response = buildHotAggResponse(Map.of(
                "postA", new long[]{recentMs, 5L, 0L},
                "postB", new long[]{olderMs,  5L, 0L}
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(response);

        List<PostPreview> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postA", result.get(0).getPostId(), "newer submission must rank first");
        Assertions.assertEquals("postB", result.get(1).getPostId());
        Assertions.assertEquals(5L, result.get(0).getKarma());
    }

    @Test
    public void testRetrieveHotPostsOrdersByVotesWhenSubmissionTimeEqual() throws IOException {
        // Same submission time, different vote counts — higher net votes wins via log₁₀ order
        long firstSeenMs = HotRankingService.EPOCH_ANCHOR_SECONDS * 1_000L + 200_000_000L;

        // postA: 100 net → order = log₁₀(100) = 2.0
        // postB:  10 net → order = log₁₀(10)  = 1.0
        SearchResponse<UserVote> response = buildHotAggResponse(Map.of(
                "postA", new long[]{firstSeenMs, 100L, 0L},
                "postB", new long[]{firstSeenMs,  10L, 0L}
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(response);

        List<PostPreview> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postA", result.get(0).getPostId(), "more upvotes must rank first when age is equal");
        Assertions.assertEquals("postB", result.get(1).getPostId());
        Assertions.assertEquals(100L, result.get(0).getKarma());
        Assertions.assertEquals(10L,  result.get(1).getKarma());
    }

    @Test
    public void testRetrieveHotPostsNetNegativeStillRanked() throws IOException {
        // Reddit's formula: downvoted post has negative order but positive time component.
        // The post must still appear — it is NOT filtered out.
        long firstSeenMs = HotRankingService.EPOCH_ANCHOR_SECONDS * 1_000L + 200_000_000L;

        SearchResponse<UserVote> response = buildHotAggResponse(Map.of(
                "postA", new long[]{firstSeenMs, 1L, 10L}   // net = -9
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(response);

        List<PostPreview> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isEmpty(), "net-negative posts must still appear — time component keeps score positive");
        Assertions.assertEquals(-9L, result.getFirst().getKarma());
    }

    @Test
    public void testRetrieveHotPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<UserVote> response = buildHotAggResponse(Map.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(UserVote.class)))
                .thenReturn(response);

        List<PostPreview> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // --------------- helpers ---------------

    /**
     * Builds a mocked SearchResponse with nested terms → filter + min aggregations.
     *
     * @param postData map of postId → [firstSeenMs, upvoteCount, downvoteCount]
     */
    private SearchResponse<UserVote> buildHotAggResponse(Map<String, long[]> postData) {
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

                    MinAggregate minAgg = mock(MinAggregate.class);
                    when(minAgg.value()).thenReturn((double) d[0]);
                    Aggregate firstSeenAgg = mock(Aggregate.class);
                    when(firstSeenAgg.min()).thenReturn(minAgg);

                    StringTermsBucket postBucket = mock(StringTermsBucket.class);
                    when(postBucket.key()).thenReturn(FieldValue.of(e.getKey()));
                    when(postBucket.aggregations()).thenReturn(Map.of(
                            "upvotes",    upvotesAgg,
                            "downvotes",  downvotesAgg,
                            "first_seen", firstSeenAgg
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
