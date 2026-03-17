package my.javacraft.elastic.rest;

import java.io.IOException;
import my.javacraft.elastic.model.UserPostEvent;
import my.javacraft.elastic.model.UserPostEventResponse;
import my.javacraft.elastic.service.DateService;
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

    @Test
    public void testCapture() throws IOException {
        UserActivityController userActivityController = new UserActivityController(
                dateService, userActivityService
        );

        when(dateService.getCurrentDate()).thenReturn("2024-01-15");

        UserPostEventResponse userPostEventResponse = Mockito.mock(UserPostEventResponse.class);
        when(userActivityService.ingestUserEvent(any(), anyString())).thenReturn(userPostEventResponse);

        UserPostEvent userPostEvent = new UserPostEvent();
        userPostEvent.setPostId("did-1");
        userPostEvent.setUserId("nl8888");
        userPostEvent.setAction("Upvote");

        ResponseEntity<UserPostEventResponse> response = userActivityController.captureUserPostEvent(userPostEvent);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

}
