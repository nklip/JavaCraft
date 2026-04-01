package dev.nklip.javacraft.elastic.api.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContentSearchRequestTest {

    @Test
    public void testValidationShouldFailWhenPatternIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setPattern(" ");

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "pattern"));
        }
    }

    @Test
    public void testValidationShouldFailWhenPatternIsNull() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setPattern(null);

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "pattern"));
        }
    }

    @Test
    public void testValidationShouldPassWhenPatternIsNotBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setPattern("harry");

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(violations.isEmpty());
        }
    }

    @Test
    public void testValidationShouldFailWhenTypeIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setType(" ");

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "type"));
            Assertions.assertTrue(hasNotBlankViolationForField(violations, "type"));
        }
    }

    @Test
    public void testValidationShouldFailWhenClientIsBlank() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setClient(" ");

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "client"));
            Assertions.assertTrue(hasNotBlankViolationForField(violations, "client"));
        }
    }

    @Test
    public void testValidationShouldFailWhenPatternIsTooLong() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setPattern("a".repeat(513));

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "pattern"));
        }
    }

    @Test
    public void testValidationShouldFailWhenTypeIsInvalid() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setType("invalid-type");

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "type"));
        }
    }

    @Test
    public void testValidationShouldFailWhenClientIsInvalid() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setClient("desktop");

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(hasViolationForField(violations, "client"));
        }
    }

    private ContentSearchRequest createValidContentSearchRequest() {
        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setPattern("test");
        return contentSearchRequest;
    }

    private boolean hasViolationForField(Set<ConstraintViolation<ContentSearchRequest>> violations, String fieldName) {
        return violations.stream().anyMatch(v -> fieldName.equals(v.getPropertyPath().toString()));
    }

    private boolean hasNotBlankViolationForField(Set<ConstraintViolation<ContentSearchRequest>> violations, String fieldName) {
        return violations.stream()
                .filter(v -> fieldName.equals(v.getPropertyPath().toString()))
                .map(v -> v.getConstraintDescriptor().getAnnotation().annotationType())
                .anyMatch(annotationType -> annotationType.equals(NotBlank.class));
    }
}
