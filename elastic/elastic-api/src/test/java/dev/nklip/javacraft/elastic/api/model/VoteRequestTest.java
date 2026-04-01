package dev.nklip.javacraft.elastic.api.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VoteRequestTest {

    @Test
    public void testVoteRequest() {
        VoteRequest voteRequest = createVoteRequest();

        Assertions.assertEquals("nl8888", voteRequest.getUserId());
        Assertions.assertEquals("12345", voteRequest.getPostId());
        Assertions.assertEquals("Upvote", voteRequest.getAction());
    }

    @Test
    public void testValidationShouldFailWhenUserIdIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VoteRequest voteRequest = createVoteRequest();
            voteRequest.setUserId("   ");

            Set<ConstraintViolation<VoteRequest>> violations = validator.validate(voteRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "userId".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenActionIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VoteRequest voteRequest = createVoteRequest();
            voteRequest.setAction(" ");

            Set<ConstraintViolation<VoteRequest>> violations = validator.validate(voteRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "action".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenUserIdIsTooLong() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VoteRequest voteRequest = createVoteRequest();
            voteRequest.setUserId("u".repeat(129));

            Set<ConstraintViolation<VoteRequest>> violations = validator.validate(voteRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "userId".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenPostIdIsTooLong() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VoteRequest voteRequest = createVoteRequest();
            voteRequest.setPostId("p".repeat(129));

            Set<ConstraintViolation<VoteRequest>> violations = validator.validate(voteRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "postId".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenActionIsInvalid() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            VoteRequest voteRequest = createVoteRequest();
            voteRequest.setAction("LIKE");

            Set<ConstraintViolation<VoteRequest>> violations = validator.validate(voteRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "action".equals(v.getPropertyPath().toString())));
        }
    }

    public static VoteRequest createVoteRequest() {
        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setUserId("nl8888");
        voteRequest.setPostId("12345");
        voteRequest.setAction("Upvote");
        return voteRequest;
    }
}
