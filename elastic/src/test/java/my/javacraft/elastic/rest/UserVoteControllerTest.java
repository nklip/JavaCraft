package my.javacraft.elastic.rest;

import java.io.IOException;
import my.javacraft.elastic.model.VoteRequest;
import my.javacraft.elastic.model.VoteResponse;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.activity.VoteService;
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
public class UserVoteControllerTest {

    @Mock
    DateService dateService;
    @Mock
    VoteService voteService;

    @Test
    public void testCapture() throws IOException {
        VoteController voteController = new VoteController(
                dateService, voteService
        );

        when(dateService.getCurrentDate()).thenReturn("2024-01-15");

        VoteResponse voteResponse = Mockito.mock(VoteResponse.class);
        when(voteService.processVoteRequest(any(), anyString())).thenReturn(voteResponse);

        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setPostId("did-1");
        voteRequest.setUserId("nl8888");
        voteRequest.setAction("Upvote");

        ResponseEntity<VoteResponse> response = voteController.captureVoteRequest(voteRequest);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
    }

}
