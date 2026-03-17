package my.javacraft.elastic.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserPostEventTest {

    @Test
    public void testHitCount() {
        UserPostEvent userPostEvent = createHitCount();

        Assertions.assertNotNull(userPostEvent.getUserId());
        Assertions.assertNotNull(userPostEvent.getPostId());
        Assertions.assertNotNull(userPostEvent.getAction());
    }

    public static UserPostEvent createHitCount() {
        UserPostEvent userPostEvent = new UserPostEvent();
        userPostEvent.setUserId("nl8888");
        userPostEvent.setPostId("12345");
        userPostEvent.setAction("Upvote");
        return userPostEvent;
    }
}
