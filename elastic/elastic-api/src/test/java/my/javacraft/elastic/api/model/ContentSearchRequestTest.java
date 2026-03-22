package my.javacraft.elastic.api.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
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

            Assertions.assertTrue(violations.stream().anyMatch(v -> "pattern".equals(v.getPropertyPath().toString())));
        }
    }

    @Test
    public void testValidationShouldFailWhenPatternIsNull() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ContentSearchRequest contentSearchRequest = createValidContentSearchRequest();
            contentSearchRequest.setPattern(null);

            Set<ConstraintViolation<ContentSearchRequest>> violations = validator.validate(contentSearchRequest);

            Assertions.assertTrue(violations.stream().anyMatch(v -> "pattern".equals(v.getPropertyPath().toString())));
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

    private ContentSearchRequest createValidContentSearchRequest() {
        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setPattern("test");
        return contentSearchRequest;
    }
}
