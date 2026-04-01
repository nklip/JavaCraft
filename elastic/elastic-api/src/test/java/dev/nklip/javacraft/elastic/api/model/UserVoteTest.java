package dev.nklip.javacraft.elastic.api.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UserVoteTest {

    @Test
    public void testJsonFormat() throws IOException {
        UserVote userVote = new UserVote(
                VoteRequestTest.createVoteRequest(),
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
                        .writeValueAsString(userVote)
                        .replaceAll("\r", "")
        );
    }

    @Test
    public void testValidationShouldFailWhenTimestampIsEmpty() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserVote userVote = createValidUserVote();
            userVote.setTimestamp("");

            Set<ConstraintViolation<UserVote>> violations = validator.validate(userVote);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "timestamp".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenPostIdIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserVote userVote = createValidUserVote();
            userVote.setPostId("   ");

            Set<ConstraintViolation<UserVote>> violations = validator.validate(userVote);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "postId".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenTimestampIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserVote userVote = createValidUserVote();
            userVote.setTimestamp("   ");

            Set<ConstraintViolation<UserVote>> violations = validator.validate(userVote);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "timestamp".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldPassForCompleteActivity() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserVote userVote = createValidUserVote();

            Set<ConstraintViolation<UserVote>> violations = validator.validate(userVote);

            Assertions.assertTrue(violations.isEmpty());
        }
    }

    @Test
    public void testConstructorNormalizesActionToUppercase() {
        VoteRequest voteRequest = VoteRequestTest.createVoteRequest(); // action = "Upvote"
        String timestamp = "2024-01-08T18:16:41.530Z";

        UserVote userVote = new UserVote(voteRequest, timestamp);

        Assertions.assertEquals("12345", userVote.getPostId());
        Assertions.assertEquals("nl8888", userVote.getUserId());
        Assertions.assertEquals("UPVOTE", userVote.getAction()); // normalized to uppercase
        Assertions.assertEquals(timestamp, userVote.getTimestamp());
    }

    @Test
    public void testConstructorShouldNormalizeWithLocaleRoot() {
        Locale previousDefaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            VoteRequest voteRequest = new VoteRequest();
            voteRequest.setPostId("12345");
            voteRequest.setUserId("nl8888");
            voteRequest.setAction("mini");

            UserVote userVote = new UserVote(voteRequest, "2024-01-08T18:16:41.530Z");

            Assertions.assertEquals("MINI", userVote.getAction());
        } finally {
            Locale.setDefault(previousDefaultLocale);
        }
    }

    @Test
    public void testValidationShouldFailWhenTimestampIsNotIso8601Utc() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            UserVote userVote = createValidUserVote();
            userVote.setTimestamp("2024/01/08 18:16:41");

            Set<ConstraintViolation<UserVote>> violations = validator.validate(userVote);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "timestamp".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testConstructorShouldThrowWhenActionIsNull() {
        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setPostId("12345");
        voteRequest.setUserId("nl8888");
        voteRequest.setAction(null);

        Assertions.assertThrows(NullPointerException.class,
                () -> new UserVote(voteRequest, "2024-01-08T18:16:41.530Z"));
    }

    public static UserVote createValidUserVote() {
        UserVote userVote = new UserVote();
        userVote.setPostId("12345");
        userVote.setUserId("nl8888");
        userVote.setAction("UPVOTE");
        userVote.setTimestamp("2024-01-08T18:16:41.530Z");
        return userVote;
    }
}
