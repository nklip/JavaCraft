package my.javacraft.elastic.service.ranking;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import java.util.List;
import my.javacraft.elastic.model.Post;
import my.javacraft.elastic.service.DateService;
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

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
public class TopRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;
    @Mock
    DateService dateService;

    // ── all-time (no window) ──────────────────────────────────────────────────

    @Test
    public void testRetrieveTopPostsAllTime() throws IOException {
        // ES returns hits sorted by karma DESC — service must preserve that order.
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("post-1", "user-001", "2024-01-01T00:00:00Z", 90L, 0L, 3.0, 0.0, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new TopRankingService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("post-1", result.getFirst().postId());
        Assertions.assertEquals(90L, result.getFirst().karma());
    }

    @Test
    public void testRetrieveTopPostsOrderByKarma() throws IOException {
        // ES sorts by karma DESC — service returns in the order provided by ES.
        // postB has higher karma and is returned first by ES (simulated here).
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postB", "user-002", "2024-01-01T00:00:00Z", 50L, 0L, 2.5, 0.0, 0.0),
                new Post("postA", "user-001", "2024-01-01T00:00:00Z", 20L, 0L, 2.0, 0.0, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new TopRankingService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postB", result.get(0).postId(), "postB (karma=50) must rank above postA (karma=20)");
        Assertions.assertEquals(50L, result.get(0).karma());
        Assertions.assertEquals("postA", result.get(1).postId());
        Assertions.assertEquals(20L, result.get(1).karma());
    }

    @Test
    public void testRetrieveTopPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new TopRankingService(esClient, dateService).retrieveTopPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // ── windowed queries ──────────────────────────────────────────────────────

    @Test
    public void testRetrieveTopPostsByWeek() throws IOException {
        when(dateService.getNDaysBeforeDate(TopRankingService.TopWindow.WEEK.getDays()))
                .thenReturn("2026-03-09T00:00:00.000Z");

        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("post-recent", "user-001", "2026-03-10T00:00:00Z", 45L, 0L, 2.0, 0.0, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new TopRankingService(esClient, dateService)
                .retrieveTopPosts(10, TopRankingService.TopWindow.WEEK);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("post-recent", result.getFirst().postId());
        Assertions.assertEquals(45L, result.getFirst().karma());
    }

    @Test
    public void testAllWindowValuesHaveCorrectDays() {
        Assertions.assertEquals(1,   TopRankingService.TopWindow.DAY.getDays());
        Assertions.assertEquals(7,   TopRankingService.TopWindow.WEEK.getDays());
        Assertions.assertEquals(30,  TopRankingService.TopWindow.MONTH.getDays());
        Assertions.assertEquals(365, TopRankingService.TopWindow.YEAR.getDays());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
