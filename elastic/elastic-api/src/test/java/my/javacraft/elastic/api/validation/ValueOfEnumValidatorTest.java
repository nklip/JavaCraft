package my.javacraft.elastic.api.validation;

import my.javacraft.elastic.api.model.ClientType;
import java.util.Locale;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ValueOfEnumValidatorTest {

    @Test
    public void testValidOfEnumValidator() {
        ValueOfEnum valueOfEnum = Mockito.mock(ValueOfEnum.class);
        Mockito.when(valueOfEnum.enumClass()).thenAnswer(invocation -> ClientType.class);

        ValueOfEnumValidator validator = new ValueOfEnumValidator();
        validator.initialize(valueOfEnum);

        Assertions.assertTrue(validator.isValid(ClientType.MOBILE.toString().toUpperCase(), null));
        Assertions.assertTrue(validator.isValid(ClientType.WEB.toString().toLowerCase(), null));
        Assertions.assertFalse(validator.isValid("random value", null));
        Assertions.assertFalse(validator.isValid(null, null));
    }

    @Test
    public void testValidOfEnumValidatorShouldBeLocaleIndependent() {
        ValueOfEnum valueOfEnum = Mockito.mock(ValueOfEnum.class);
        Mockito.when(valueOfEnum.enumClass()).thenAnswer(invocation -> ClientType.class);

        ValueOfEnumValidator validator = new ValueOfEnumValidator();
        validator.initialize(valueOfEnum);

        Locale previousDefaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            Assertions.assertTrue(validator.isValid("mobile", null));
        } finally {
            Locale.setDefault(previousDefaultLocale);
        }
    }
}
