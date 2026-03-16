package my.javacraft.elastic.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserClickTest {

    @Test
    public void testHitCount() {
        UserClick userClick = createHitCount();

        Assertions.assertNotNull(userClick.getUserId());
        Assertions.assertNotNull(userClick.getPostId());
        Assertions.assertNotNull(userClick.getAction());
    }

    public static UserClick createHitCount() {
        UserClick userClick = new UserClick();
        userClick.setUserId("nl8888");
        userClick.setPostId("12345");
        userClick.setAction("Upvote");
        return userClick;
    }
}
