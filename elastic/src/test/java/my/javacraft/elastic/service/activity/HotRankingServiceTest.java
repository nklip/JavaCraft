package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import java.util.List;
import my.javacraft.elastic.model.Post;
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
public class HotRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;

    @Test
    public void testRetrieveHotPostsOrdersByHotScore() throws IOException {
        // ES returns hits sorted by hotScore DESC — service must preserve that order.
        // postA has a lower karma but was submitted more recently, so its hotScore is higher.
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2024-06-01T00:00:00Z",  5L, 20.5, 0.0),  // higher hotScore
                new Post("postB", "user-002", "2024-01-01T00:00:00Z", 50L, 18.3, 0.0)   // lower hotScore
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postA", result.get(0).postId(), "higher hotScore must rank first");
        Assertions.assertEquals("postB", result.get(1).postId());
        Assertions.assertEquals(5L, result.get(0).karma());
    }

    @Test
    public void testRetrieveHotPostsNetNegativeStillRanked() throws IOException {
        // Reddit's formula: downvoted post has negative order but positive time component.
        // The post must still appear — it is NOT filtered out.
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2024-01-01T00:00:00Z", -9L, 0.046, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isEmpty(), "net-negative posts must still appear");
        Assertions.assertEquals(-9L, result.getFirst().karma());
    }

    @Test
    public void testRetrieveHotPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new HotRankingService(esClient).retrieveHotPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    @Test
    public void testRetrieveHotPostsRespectsLimit() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2024-01-05T00:00:00Z", 10L, 5.0, 0.0),
                new Post("postB", "user-002", "2024-01-04T00:00:00Z", 10L, 4.8, 0.0),
                new Post("postC", "user-003", "2024-01-03T00:00:00Z", 10L, 4.6, 0.0),
                new Post("postD", "user-004", "2024-01-02T00:00:00Z", 10L, 4.4, 0.0),
                new Post("postE", "user-005", "2024-01-01T00:00:00Z", 10L, 4.2, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new HotRankingService(esClient).retrieveHotPosts(3);

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
