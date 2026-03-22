package my.javacraft.elastic.api.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PostRequestTest {

    @Test
    public void testValidationShouldPassForValidPostRequest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            PostRequest postRequest = createValidPostRequest();

            Set<ConstraintViolation<PostRequest>> violations = validator.validate(postRequest);

            Assertions.assertTrue(violations.isEmpty());
        }
    }

    @Test
    public void testValidationShouldFailWhenAuthorUserIdIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            PostRequest postRequest = createValidPostRequest();
            postRequest.setAuthorUserId(" ");

            Set<ConstraintViolation<PostRequest>> violations = validator.validate(postRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "authorUserId".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenAuthorUserIdIsTooLong() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            PostRequest postRequest = createValidPostRequest();
            postRequest.setAuthorUserId("u".repeat(129));

            Set<ConstraintViolation<PostRequest>> violations = validator.validate(postRequest);

            Assertions.assertTrue(violations.stream()
                    .anyMatch(v -> "authorUserId".equals(v.getPropertyPath().toString())));
        }
    }

    private PostRequest createValidPostRequest() {
        PostRequest postRequest = new PostRequest();
        postRequest.setAuthorUserId("nl8888");
        return postRequest;
    }
}
