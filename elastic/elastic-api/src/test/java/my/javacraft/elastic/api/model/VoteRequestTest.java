package my.javacraft.elastic.api.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VoteRequestTest {

    @Test
    public void testVoteRequest() {
        VoteRequest voteRequest = createVoteRequest();

        Assertions.assertNotNull(voteRequest.getUserId());
        Assertions.assertNotNull(voteRequest.getPostId());
        Assertions.assertNotNull(voteRequest.getAction());
    }

    public static VoteRequest createVoteRequest() {
        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setUserId("nl8888");
        voteRequest.setPostId("12345");
        voteRequest.setAction("Upvote");
        return voteRequest;
    }
}
