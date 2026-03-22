package my.javacraft.elastic.app.service.ranking;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import java.util.List;
import my.javacraft.elastic.api.model.Post;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * Unit tests for BestRankingService.
 *
 * ES returns documents pre-sorted by bestScore DESC (the field is denormalized by the
 * Painless script on every vote). The service responsibility is:
 *   1. Issue the correct search query (sort by bestScore DESC, postId ASC)
 *   2. Preserve the order returned by ES
 *   3. Enforce the client-requested size limit
 *
 * Wilson score reminder:
 *   n = 2*upvotes - karma (total votes)
 *   p^ = upvotes / n
 *   bestScore = (p^ + z^2/2n - z*sqrt(p^(1-p^)/n + z^2/4n^2)) / (1 + z^2/n)
 *
 *   post with 95 upvotes / 5 downvotes (n=100, p^=0.95): bestScore ≈ 0.898
 *   post with 10 upvotes / 0 downvotes (n=10,  p^=1.00): bestScore ≈ 0.722
 *   post with 0  upvotes / 0 downvotes (n=0):             bestScore  = 0.0
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
public class BestRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;

    @Test
    public void testRetrieveBestPostsOrdersByBestScore() throws IOException {
        // ES returns hits sorted by bestScore DESC — service must preserve that order.
        // postA: 95 upvotes / 5 downvotes (n=100, p^=0.95) → bestScore ≈ 0.898
        // postB: 10 upvotes / 0 downvotes (n=10,  p^=1.00) → bestScore ≈ 0.722
        // Despite postB having a perfect ratio, the larger sample of postA wins.
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2024-01-01T00:00:00Z",  90L, 95L, 0.0, 0.0, 0.898),
                new Post("postB", "user-002", "2024-01-01T00:00:00Z",  10L, 10L, 0.0, 0.0, 0.722)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new BestRankingService(esClient).retrieveBestPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postA", result.get(0).postId(),
                "larger reliable sample (95/100) must rank above perfect-but-small (10/10)");
        Assertions.assertEquals("postB", result.get(1).postId());
        Assertions.assertTrue(result.get(0).bestScore() > result.get(1).bestScore(),
                "bestScore must decrease down the ranking");
    }

    @Test
    public void testRetrieveBestPostsZeroVotesHaveZeroBestScore() throws IOException {
        // A post with no votes at all has bestScore = 0.0 (n=0 guard in Painless script).
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2024-01-01T00:00:00Z", 0L, 0L, 0.0, 0.0, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new BestRankingService(esClient).retrieveBestPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals(0.0, result.getFirst().bestScore(),
                "post with no votes must have bestScore=0.0");
    }

    @Test
    public void testRetrieveBestPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new BestRankingService(esClient).retrieveBestPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void testRetrieveBestPostsRespectsLimit() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2024-01-01T00:00:00Z", 90L, 95L, 0.0, 0.0, 0.898),
                new Post("postB", "user-002", "2024-01-01T00:00:00Z", 10L, 10L, 0.0, 0.0, 0.722),
                new Post("postC", "user-003", "2024-01-01T00:00:00Z",  8L,  8L, 0.0, 0.0, 0.631),
                new Post("postD", "user-004", "2024-01-01T00:00:00Z",  4L,  5L, 0.0, 0.0, 0.380),
                new Post("postE", "user-005", "2024-01-01T00:00:00Z",  0L,  0L, 0.0, 0.0, 0.000)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new BestRankingService(esClient).retrieveBestPosts(3);

        Assertions.assertEquals(3, result.size(), "result must be capped at requested size");
        Assertions.assertEquals("postA", result.get(0).postId());
        Assertions.assertEquals("postB", result.get(1).postId());
        Assertions.assertEquals("postC", result.get(2).postId());
    }

    // --------------- helpers ---------------

    private SearchResponse<Post> buildPostsResponse(List<Post> posts) {
        List<Hit<Post>> hits = posts.stream()
                .map(post -> {
                    Hit<Post> hit = mock(Hit.class);
                    lenient().when(hit.source()).thenReturn(post);
                    return hit;
                })
                .toList();

        HitsMetadata<Post> hitsMetadata = mock(HitsMetadata.class);
        when(hitsMetadata.hits()).thenReturn(hits);

        SearchResponse<Post> response = mock(SearchResponse.class);
        when(response.hits()).thenReturn(hitsMetadata);
        return response;
    }
}
