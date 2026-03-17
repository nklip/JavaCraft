package my.javacraft.elastic.rest;

import co.elastic.clients.elasticsearch.core.GetResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
import my.javacraft.elastic.service.activity.TopService;
import my.javacraft.elastic.service.activity.UserActivityService;
import my.javacraft.elastic.service.activity.HotService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserActivityControllerTest {

    @Mock
    DateService dateService;
    @Mock
    UserActivityService userActivityService;
    @Mock
    TopService topService;
    @Mock
    HotService hotService;
    @Mock
    UserActivityIngestionService userActivityIngestionService;

    @Test
    public void testCapture() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );

        when(dateService.getCurrentDate()).thenReturn("2024-01-15");

        UserClickResponse userClickResponse = Mockito.mock(UserClickResponse.class);
        when(userActivityIngestionService.ingestUserClick(any(), anyString())).thenReturn(userClickResponse);

        UserClick userClick = new UserClick();
        userClick.setPostId("did-1");
        userClick.setUserId("nl8888");
        userClick.setAction("Upvote");

        ResponseEntity<UserClickResponse> response = userActivityController.captureUserClick(userClick);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testGetHitCount() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );

        UserActivity userActivity = Mockito.mock(UserActivity.class);
        GetResponse<UserActivity> getResponse = new GetResponse.Builder<UserActivity>()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .found(true)
                .id("part-of-mock-so-any-id")
                .source(userActivity)
                .build();
        when(userActivityService.getUserActivityByDocumentId(anyString())).thenReturn(getResponse);

        ResponseEntity<GetResponse<UserActivity>> response = userActivityController
                .getHitCount("documentId");

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPosts() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );

        List<UserActivity> activityList = new ArrayList<>();
        when(topService.retrieveTopPosts(anyInt())).thenReturn(activityList);

        ResponseEntity<List<UserActivity>> response = userActivityController
                .retrieveTopPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testHotPosts() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );

        List<UserActivity> activityList = new ArrayList<>();
        when(hotService.retrieveHotPosts(anyInt())).thenReturn(activityList);

        ResponseEntity<List<UserActivity>> response = userActivityController
                .retrieveHotPosts(10);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

//    @Test
//    public void testDeleteIndex() throws IOException {
//        UserActivityController userActivityController = new UserActivityController(
//                dateService, userActivityService, topService, hotService, userActivityIngestionService
//        );
//
//        DeleteIndexResponse deleteIndexResponse = Mockito.mock(DeleteIndexResponse.class);
//        when(userActivityService.deleteIndex(anyString())).thenReturn(deleteIndexResponse);
//
//        ResponseEntity<DeleteIndexResponse> response = userActivityController
//                .deleteIndex("nl88888");
//
//        Assertions.assertNotNull(response);
//        Assertions.assertNotNull(response.getBody());
//    }

//    @Test
//    public void testDeleteHitCountDocument() throws IOException {
//        UserActivityController userActivityController = new UserActivityController(
//                dateService, userActivityService, topService, hotService, userActivityIngestionService
//        );
//
//        DeleteResponse deleteResponse = Mockito.mock(DeleteResponse.class);
//        when(userActivityService.deleteDocument(anyString(), anyString())).thenReturn(deleteResponse);
//
//        ResponseEntity<DeleteResponse> response = userActivityController
//                .deleteHitCountDocument("hit_count", "nl88888");
//
//        Assertions.assertNotNull(response);
//        Assertions.assertNotNull(response.getBody());
//    }

    @Test
    public void testTopPostsByWindow() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );

        List<UserActivity> activityList = new ArrayList<>();
        when(topService.retrieveTopPosts(anyInt(), eq(TopService.TopWindow.WEEK))).thenReturn(activityList);

        ResponseEntity<List<UserActivity>> response = userActivityController
                .retrieveTopPostsByWindow("week", 10);

        Assertions.assertNotNull(response);
        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertNotNull(response.getBody());
    }

    @Test
    public void testTopPostsByWindowReturnsBadRequestForInvalidWindow() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );

        ResponseEntity<List<UserActivity>> response = userActivityController
                .retrieveTopPostsByWindow("invalid", 10);

        Assertions.assertEquals(400, response.getStatusCode().value());
    }

    @Test
    public void testTopPostsValidationShouldFailWhenSizeLessThanOne() throws NoSuchMethodException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );
        Method method = UserActivityController.class.getMethod("retrieveTopPosts", int.class);

        Set<ConstraintViolation<UserActivityController>> violations;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            violations = validatorFactory.getValidator().forExecutables()
                    .validateParameters(userActivityController, method, new Object[]{0});
        }

        Assertions.assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("greater than or equal to 1")));
    }

    @Test
    public void testHotPostsValidationShouldFailWhenSizeExceedsMaxValue() throws NoSuchMethodException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, topService, hotService, userActivityIngestionService
        );
        Method method = UserActivityController.class.getMethod("retrieveHotPosts", int.class);

        Set<ConstraintViolation<UserActivityController>> violations;
        try (ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {
            violations = validatorFactory.getValidator().forExecutables()
                    .validateParameters(userActivityController, method, new Object[]{UserActivityService.MAX_VALUES + 1});
        }

        Assertions.assertTrue(violations.stream()
                .anyMatch(v -> v.getMessage().contains("less than or equal to " + UserActivityService.MAX_VALUES)));
    }

}
