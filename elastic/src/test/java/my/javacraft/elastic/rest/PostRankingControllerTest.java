package my.javacraft.elastic.rest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.service.activity.HotRankingService;
import my.javacraft.elastic.service.activity.NewRankingService;
import my.javacraft.elastic.service.activity.TopRankingService;
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

    private PostRankingController controller() {
        return new PostRankingController(topRankingService, hotRankingService, newRankingService);
    }

    @Test
    public void testNewPosts() throws IOException {
        List<PostPreview> posts = new ArrayList<>();
        when(newRankingService.retrieveNewPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller().retrieveNewPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testNewPostsReturnsServiceResultsInOrder() throws IOException {
        // New endpoint must preserve service output order (chronological, not karma-sorted)
        List<PostPreview> posts = List.of(
                new PostPreview("postA", 2L),
                new PostPreview("postB", 20L),
                new PostPreview("postC", 10L)
        );
        when(newRankingService.retrieveNewPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller().retrieveNewPosts(10);

        Assertions.assertNotNull(response.getBody());
        List<PostPreview> body = response.getBody();
        Assertions.assertEquals(3, body.size());
        Assertions.assertEquals("postA", body.get(0).getPostId(), "order must match service output");
        Assertions.assertEquals(2L,      body.get(0).getKarma());
        Assertions.assertEquals("postB", body.get(1).getPostId());
        Assertions.assertEquals(20L,     body.get(1).getKarma());
        Assertions.assertEquals("postC", body.get(2).getPostId());
        Assertions.assertEquals(10L,     body.get(2).getKarma());
    }

    @Test
    public void testTopPosts() throws IOException {
        List<PostPreview> posts = new ArrayList<>();
        when(topRankingService.retrieveTopPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller().retrieveTopPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testHotPosts() throws IOException {
        List<PostPreview> posts = new ArrayList<>();
        when(hotRankingService.retrieveHotPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller().retrieveHotPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindow() throws IOException {
        List<PostPreview> posts = new ArrayList<>();
        when(topRankingService.retrieveTopPosts(anyInt(), eq(TopRankingService.TopWindow.WEEK))).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller().retrieveTopPostsByWindow("week", 10);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindowReturnsBadRequestForInvalidWindow() throws IOException {
        ResponseEntity<List<PostPreview>> response = controller().retrieveTopPostsByWindow("invalid", 10);

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
                    .validateParameters(c, method, new Object[]{Constants.MAX_VALUES + 1});
        }

        Assertions.assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("less than or equal to " + Constants.MAX_VALUES)));
    }

    @Test
    public void testRetrieveHotPostsReturnsServiceResultsAsIs() throws IOException {
        // Hot endpoint delegates ordering entirely to HotRankingService — no controller-level sort
        List<PostPreview> posts = List.of(
                new PostPreview("postA", 10L),
                new PostPreview("postB", 50L),
                new PostPreview("postC", 30L)
        );
        when(hotRankingService.retrieveHotPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller().retrieveHotPosts(10);

        Assertions.assertNotNull(response.getBody());
        List<PostPreview> body = response.getBody();
        Assertions.assertEquals(3, body.size());
        Assertions.assertEquals("postA", body.get(0).getPostId(), "order must match service output");
        Assertions.assertEquals(10L,     body.get(0).getKarma());
        Assertions.assertEquals("postB", body.get(1).getPostId());
        Assertions.assertEquals(50L,     body.get(1).getKarma());
        Assertions.assertEquals("postC", body.get(2).getPostId());
        Assertions.assertEquals(30L,     body.get(2).getKarma());
    }
}
