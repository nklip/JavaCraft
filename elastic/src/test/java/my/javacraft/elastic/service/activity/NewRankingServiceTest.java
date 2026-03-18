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
import my.javacraft.elastic.model.PostPreview;
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
public class NewRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;

    @Test
    public void testRetrieveNewPostsSortsByMostRecentFirst() throws IOException {
        // Posts returned in createdAt DESC order from 'posts' index; karma embedded in document.
        // postA is newer (appears first in ES hits) but has lower karma — karma must NOT reorder.
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "2024-01-02T00:00:00.000Z", 60L),
                new Post("postB", "2024-01-01T00:00:00.000Z", 80L)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<PostPreview> result = new NewRankingService(esClient).retrieveNewPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("postA", result.get(0).getPostId(),
                "most recently submitted post must rank first, regardless of karma");
        Assertions.assertEquals(60L, result.get(0).getKarma(),
                "karma from Post document must be passed through");
        Assertions.assertEquals("postB", result.get(1).getPostId());
        Assertions.assertEquals(80L, result.get(1).getKarma());
    }

    @Test
    public void testRetrieveNewPostsKarmaDoesNotAffectOrder() throws IOException {
        // ES returns hits in createdAt DESC order; service must preserve that order exactly.
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "2024-01-03T00:00:00.000Z",  1L),  // newest,  karma=1
                new Post("postB", "2024-01-02T00:00:00.000Z", 50L),  // middle
                new Post("postC", "2024-01-01T00:00:00.000Z", 100L)  // oldest, karma=100
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<PostPreview> result = new NewRankingService(esClient).retrieveNewPosts(10);

        Assertions.assertEquals(3, result.size());
        Assertions.assertEquals("postA", result.get(0).getPostId(), "newest (lowest karma) must be first");
        Assertions.assertEquals("postB", result.get(1).getPostId());
        Assertions.assertEquals("postC", result.get(2).getPostId(), "oldest (highest karma) must be last");
    }

    @Test
    public void testRetrieveNewPostsRespectsLimit() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "2024-01-05T00:00:00.000Z", 10L),
                new Post("postB", "2024-01-04T00:00:00.000Z", 10L),
                new Post("postC", "2024-01-03T00:00:00.000Z", 10L),
                new Post("postD", "2024-01-02T00:00:00.000Z", 10L),
                new Post("postE", "2024-01-01T00:00:00.000Z", 10L)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<PostPreview> result = new NewRankingService(esClient).retrieveNewPosts(3);

        Assertions.assertEquals(3, result.size(), "result must be capped at requested size");
        Assertions.assertEquals("postA", result.get(0).getPostId());
        Assertions.assertEquals("postB", result.get(1).getPostId());
        Assertions.assertEquals("postC", result.get(2).getPostId());
    }

    @Test
    public void testRetrieveNewPostsReturnsEmptyWhenNoActivity() throws IOException {
        SearchResponse<Post> response = buildPostsResponse(List.of());

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<PostPreview> result = new NewRankingService(esClient).retrieveNewPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

    // --------------- helpers ---------------

    private SearchResponse<Post> buildPostsResponse(List<Post> posts) {
        List<Hit<Post>> hits = posts.stream()
                .map(post -> {
                    Hit<Post> hit = mock(Hit.class);
                    // lenient: stream is lazy so hits beyond the requested limit may not be consumed
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
