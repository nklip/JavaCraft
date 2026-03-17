package my.javacraft.elastic.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserActivityTest {

    @Test
    public void testJsonFormat() throws IOException {
        UserActivity userActivity = new UserActivity(
                UserPostEventTest.createHitCount(),
                "2024-01-08T18:16:41.530Z"
        );

        ObjectMapper objectMapper = new ObjectMapper();
        Assertions.assertEquals("""
                {
                  "postId" : "12345",
                  "userId" : "nl8888",
                  "action" : "UPVOTE",
                  "timestamp" : "2024-01-08T18:16:41.530Z"
                }""",
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(userActivity)
                        .replaceAll("\r", "")
        );
    }

    @Test
    public void testValidationShouldFailWhenTimestampIsEmpty() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserActivity userActivity = createValidUserActivity();
            userActivity.setTimestamp("");

            Set<ConstraintViolation<UserActivity>> violations = validator.validate(userActivity);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "timestamp".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldPassForCompleteActivity() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserActivity userActivity = createValidUserActivity();

            Set<ConstraintViolation<UserActivity>> violations = validator.validate(userActivity);

            Assertions.assertTrue(violations.isEmpty());
        }
    }

    @Test
    public void testConstructorNormalizesActionToUppercase() {
        UserPostEvent userPostEvent = UserPostEventTest.createHitCount(); // action = "Upvote"
        String timestamp = "2024-01-08T18:16:41.530Z";

        UserActivity userActivity = new UserActivity(userPostEvent, timestamp);

        Assertions.assertEquals("12345", userActivity.getPostId());
        Assertions.assertEquals("nl8888", userActivity.getUserId());
        Assertions.assertEquals("UPVOTE", userActivity.getAction()); // normalized to uppercase
        Assertions.assertEquals(timestamp, userActivity.getTimestamp());
    }

    public static UserActivity createValidUserActivity() {
        UserActivity userActivity = new UserActivity();
        userActivity.setPostId("12345");
        userActivity.setUserId("nl8888");
        userActivity.setAction("UPVOTE");
        userActivity.setTimestamp("2024-01-08T18:16:41.530Z");
        return userActivity;
    }
}
