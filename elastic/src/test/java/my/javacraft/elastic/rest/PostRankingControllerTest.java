package my.javacraft.elastic.rest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.service.activity.HotService;
import my.javacraft.elastic.service.activity.TopService;
import my.javacraft.elastic.service.activity.UserActivityService;
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
    TopService topService;
    @Mock
    HotService hotService;

    @Test
    public void testTopPosts() throws IOException {
        PostRankingController controller = new PostRankingController(topService, hotService);

        List<PostPreview> posts = new ArrayList<>();
        when(topService.retrieveTopPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller.retrieveTopPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testHotPosts() throws IOException {
        PostRankingController controller = new PostRankingController(topService, hotService);

        List<PostPreview> posts = new ArrayList<>();
        when(hotService.retrieveHotPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller.retrieveHotPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindow() throws IOException {
        PostRankingController controller = new PostRankingController(topService, hotService);

        List<PostPreview> posts = new ArrayList<>();
        when(topService.retrieveTopPosts(anyInt(), eq(TopService.TopWindow.WEEK))).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller.retrieveTopPostsByWindow("week", 10);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindowReturnsBadRequestForInvalidWindow() throws IOException {
        PostRankingController controller = new PostRankingController(topService, hotService);

        ResponseEntity<List<PostPreview>> response = controller.retrieveTopPostsByWindow("invalid", 10);

        Assertions.assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void testTopPostsValidationShouldFailWhenSizeLessThanOne() throws NoSuchMethodException {
        PostRankingController controller = new PostRankingController(topService, hotService);
        Method method = PostRankingController.class.getMethod("retrieveTopPosts", int.class);

        Set<ConstraintViolation<PostRankingController>> violations;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            violations = validatorFactory.getValidator().forExecutables()
                    .validateParameters(controller, method, new Object[]{0});
        }

        Assertions.assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("greater than or equal to 1")));
    }

    @Test
    public void testHotPostsValidationShouldFailWhenSizeExceedsMaxValue() throws NoSuchMethodException {
        PostRankingController controller = new PostRankingController(topService, hotService);
        Method method = PostRankingController.class.getMethod("retrieveHotPosts", int.class);

        Set<ConstraintViolation<PostRankingController>> violations;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            violations = validatorFactory.getValidator().forExecutables()
                    .validateParameters(controller, method, new Object[]{UserActivityService.MAX_VALUES + 1});
        }

        Assertions.assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("less than or equal to " + UserActivityService.MAX_VALUES)));
    }

    @Test
    public void testRetrieveHotPostsReturnsServiceResultsAsIs() throws IOException {
        PostRankingController controller = new PostRankingController(topService, hotService);

        // Hot endpoint delegates ordering entirely to HotService — no controller-level sort
        List<PostPreview> posts = List.of(
                new PostPreview("postA", 10L),
                new PostPreview("postB", 50L),
                new PostPreview("postC", 30L)
        );
        when(hotService.retrieveHotPosts(anyInt())).thenReturn(posts);

        ResponseEntity<List<PostPreview>> response = controller.retrieveHotPosts(10);

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
