package my.javacraft.elastic.rest;

import co.elastic.clients.elasticsearch.core.GetResponse;
import java.io.IOException;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
import my.javacraft.elastic.service.activity.UserActivityService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserActivityControllerTest {

    @Mock
    DateService dateService;
    @Mock
    UserActivityService userActivityService;
    @Mock
    UserActivityIngestionService userActivityIngestionService;

    @Test
    public void testCapture() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService, userActivityIngestionService
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
                dateService, userActivityService, userActivityIngestionService
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
}
