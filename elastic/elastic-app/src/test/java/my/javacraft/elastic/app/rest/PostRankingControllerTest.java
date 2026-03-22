package my.javacraft.elastic.app.rest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import my.javacraft.elastic.api.config.ApiLimits;
import my.javacraft.elastic.api.model.Post;
import my.javacraft.elastic.app.service.ranking.BestRankingService;
import my.javacraft.elastic.app.service.ranking.HotRankingService;
import my.javacraft.elastic.app.service.ranking.NewRankingService;
import my.javacraft.elastic.app.service.ranking.RisingRankingService;
import my.javacraft.elastic.app.service.ranking.TopRankingService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PostRankingControllerTest {

    @Mock
    TopRankingService topRankingService;
    @Mock
    HotRankingService hotRankingService;
    @Mock
    NewRankingService newRankingService;
    @Mock
    RisingRankingService risingRankingService;
    @Mock
    BestRankingService bestRankingService;

    private PostRankingController controller() {
        return new PostRankingController(
                topRankingService,
                hotRankingService,
                newRankingService,
                risingRankingService,
                bestRankingService
        );
    }

    @Test
    public void testNewPosts() throws IOException {
        List<Post> posts = new ArrayList<>();
        when(newRankingService.retrieveNewPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveNewPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testNewPostsReturnsServiceResultsInOrder() throws IOException {
        // New endpoint must preserve service output order (chronological, not karma-sorted)
        List<Post> posts = List.of(
                new Post("postA", "user-001", "2024-01-03T00:00:00Z",  2L, 0L, 0.0, 0.0, 0.0),
                new Post("postB", "user-002", "2024-01-02T00:00:00Z", 20L, 0L, 0.0, 0.0, 0.0),
                new Post("postC", "user-003", "2024-01-01T00:00:00Z", 10L, 0L, 0.0, 0.0, 0.0)
        );
        when(newRankingService.retrieveNewPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveNewPosts(10);

        Assertions.assertNotNull(response.getBody());
        List<Post> body = response.getBody();
        Assertions.assertEquals(3, body.size());
        Assertions.assertEquals("postA", body.get(0).postId(), "order must match service output");
        Assertions.assertEquals(2L,      body.get(0).karma());
        Assertions.assertEquals("postB", body.get(1).postId());
        Assertions.assertEquals(20L,     body.get(1).karma());
        Assertions.assertEquals("postC", body.get(2).postId());
        Assertions.assertEquals(10L,     body.get(2).karma());
    }

    @Test
    public void testTopPosts() throws IOException {
        List<Post> posts = new ArrayList<>();
        when(topRankingService.retrieveTopPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveTopPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testHotPosts() throws IOException {
        List<Post> posts = new ArrayList<>();
        when(hotRankingService.retrieveHotPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveHotPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testRisingPosts() throws IOException {
        List<Post> posts = new ArrayList<>();
        when(risingRankingService.retrieveRisingPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveRisingPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindow() throws IOException {
        List<Post> posts = new ArrayList<>();
        when(topRankingService.retrieveTopPosts(anyInt(), eq(TopRankingService.TopWindow.WEEK))).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveTopPostsByWindow("week", 10);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindowReturnsBadRequestForInvalidWindow() throws IOException {
        ResponseEntity<List<Post>> response = controller().retrieveTopPostsByWindow("invalid", 10);

        Assertions.assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void testTopPostsValidationShouldFailWhenSizeLessThanOne() throws NoSuchMethodException {
        PostRankingController c = controller();
        Method method = PostRankingController.class.getMethod("retrieveTopPosts", int.class);

        Set<ConstraintViolation<PostRankingController>> violations;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            violations = validatorFactory.getValidator().forExecutables()
                    .validateParameters(c, method, new Object[]{0});
        }

        Assertions.assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("greater than or equal to 1")));
    }

    @Test
    public void testHotPostsValidationShouldFailWhenSizeExceedsMaxValue() throws NoSuchMethodException {
        PostRankingController c = controller();
        Method method = PostRankingController.class.getMethod("retrieveHotPosts", int.class);

        Set<ConstraintViolation<PostRankingController>> violations;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            violations = validatorFactory.getValidator().forExecutables()
                    .validateParameters(c, method, new Object[]{ApiLimits.MAX_ES_LIMIT + 1});
        }

        Assertions.assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("less than or equal to " + ApiLimits.MAX_ES_LIMIT)));
    }

    @Test
    public void testRetrieveHotPostsReturnsServiceResultsAsIs() throws IOException {
        // Hot endpoint delegates ordering entirely to HotRankingService — no controller-level sort
        List<Post> posts = List.of(
                new Post("postA", "user-001", "2024-01-01T00:00:00Z", 10L, 0L, 3.5, 0.0, 0.0),
                new Post("postB", "user-002", "2024-01-01T00:00:00Z", 50L, 0L, 5.0, 0.0, 0.0),
                new Post("postC", "user-003", "2024-01-01T00:00:00Z", 30L, 0L, 4.2, 0.0, 0.0)
        );
        when(hotRankingService.retrieveHotPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<Post>> response = controller().retrieveHotPosts(10);

        Assertions.assertNotNull(response.getBody());
        List<Post> body = response.getBody();
        Assertions.assertEquals(3, body.size());
        Assertions.assertEquals("postA", body.get(0).postId(), "order must match service output");
        Assertions.assertEquals(10L,     body.get(0).karma());
        Assertions.assertEquals("postB", body.get(1).postId());
        Assertions.assertEquals(50L,     body.get(1).karma());
        Assertions.assertEquals("postC", body.get(2).postId());
        Assertions.assertEquals(30L,     body.get(2).karma());
    }
}
