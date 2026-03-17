package my.javacraft.elastic.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VoteRequestTest {

    @Test
    public void testHitCount() {
        VoteRequest voteRequest = createHitCount();

        Assertions.assertNotNull(voteRequest.getUserId());
        Assertions.assertNotNull(voteRequest.getPostId());
        Assertions.assertNotNull(voteRequest.getAction());
    }

    public static VoteRequest createHitCount() {
        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setUserId("nl8888");
        voteRequest.setPostId("12345");
        voteRequest.setAction("Upvote");
        return voteRequest;
    }
}
