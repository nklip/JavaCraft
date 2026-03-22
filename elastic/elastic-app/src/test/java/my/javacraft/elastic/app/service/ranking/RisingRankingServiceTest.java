package my.javacraft.elastic.app.service.ranking;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import java.util.List;
import my.javacraft.elastic.api.config.Constants;
import my.javacraft.elastic.api.model.Post;
import my.javacraft.elastic.app.service.DateService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
public class RisingRankingServiceTest {

    @Mock
    ElasticsearchClient esClient;
    @Mock
    DateService dateService;

    @Test
    public void testRetrieveRisingPostsUsesSixHourWindowAndVelocitySort() throws IOException {
        String since = "2026-03-20T06:00:00.000Z";
        when(dateService.getNHoursBeforeDate(RisingRankingService.RISING_CANDIDATE_WINDOW_HOURS)).thenReturn(since);

        SearchResponse<Post> response = buildPostsResponse(List.of(
                // 50 net votes in 30 minutes outranks 200 net votes over ~5 hours by risingScore.
                new Post("post-fast", "user-001", "2026-03-20T11:30:00.000Z", 50L, 50L, 0.0, 0.0278, 0.0),
                new Post("post-slower", "user-002", "2026-03-20T07:00:00.000Z", 200L, 200L, 0.0, 0.0111, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new RisingRankingService(esClient, dateService).retrieveRisingPosts(10);

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(requestCaptor.capture(), eq(Post.class));
        SearchRequest request = requestCaptor.getValue();

        Assertions.assertEquals(100, request.size(), "query fan-out should overfetch and trim");
        Assertions.assertNotNull(request.query());
        Assertions.assertEquals(Constants.CREATED_AT, request.query().range().date().field());
        Assertions.assertEquals(since, request.query().range().date().gte());
        Assertions.assertEquals(Constants.RISING_SCORE, request.sort().get(0).field().field());
        Assertions.assertEquals(SortOrder.Desc, request.sort().get(0).field().order());
        Assertions.assertEquals(Constants.POST_ID, request.sort().get(1).field().field());
        Assertions.assertEquals(SortOrder.Asc, request.sort().get(1).field().order());

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("post-fast", result.get(0).postId(), "higher karma velocity must rank first");
        Assertions.assertEquals("post-slower", result.get(1).postId());
    }

    @Test
    public void testRetrieveRisingPostsRespectsLimit() throws IOException {
        when(dateService.getNHoursBeforeDate(RisingRankingService.RISING_CANDIDATE_WINDOW_HOURS))
                .thenReturn("2026-03-20T06:00:00.000Z");

        SearchResponse<Post> response = buildPostsResponse(List.of(
                new Post("postA", "user-001", "2026-03-20T11:00:00.000Z", 30L, 30L, 0.0, 0.0300, 0.0),
                new Post("postB", "user-002", "2026-03-20T10:30:00.000Z", 28L, 28L, 0.0, 0.0260, 0.0),
                new Post("postC", "user-003", "2026-03-20T10:00:00.000Z", 25L, 25L, 0.0, 0.0220, 0.0),
                new Post("postD", "user-004", "2026-03-20T09:30:00.000Z", 20L, 20L, 0.0, 0.0180, 0.0)
        ));

        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new RisingRankingService(esClient, dateService).retrieveRisingPosts(3);

        Assertions.assertEquals(3, result.size(), "result must be capped at requested size");
        Assertions.assertEquals("postA", result.get(0).postId());
        Assertions.assertEquals("postB", result.get(1).postId());
        Assertions.assertEquals("postC", result.get(2).postId());
    }

    @Test
    public void testRetrieveRisingPostsReturnsEmptyWhenNoCandidates() throws IOException {
        when(dateService.getNHoursBeforeDate(RisingRankingService.RISING_CANDIDATE_WINDOW_HOURS))
                .thenReturn("2026-03-20T06:00:00.000Z");

        SearchResponse<Post> response = buildPostsResponse(List.of());
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.search(any(SearchRequest.class), eq(Post.class))).thenReturn(response);

        List<Post> result = new RisingRankingService(esClient, dateService).retrieveRisingPosts(10);

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isEmpty());
    }

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
