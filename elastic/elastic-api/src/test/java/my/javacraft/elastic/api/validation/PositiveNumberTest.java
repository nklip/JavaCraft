package my.javacraft.elastic.api.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PositiveNumberTest {

    @Test
    void testPositiveOrDefault() {
        Assertions.assertEquals(10, my.javacraft.elastic.api.validation.PositiveNumber.positiveOrDefault(10, 3));
        Assertions.assertEquals(3, my.javacraft.elastic.api.validation.PositiveNumber.positiveOrDefault(0, 3));

        Assertions.assertEquals(100L, my.javacraft.elastic.api.validation.PositiveNumber.positiveOrDefault(100L, 5L));
        Assertions.assertEquals(5L, my.javacraft.elastic.api.validation.PositiveNumber.positiveOrDefault(-1L, 5L));
    }

    @Test
    void testConstructorShouldThrowAssertionError() throws Exception {
        Constructor<PositiveNumber> constructor = PositiveNumber.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException exception = Assertions.assertThrows(
                InvocationTargetException.class,
                constructor::newInstance
        );
        Assertions.assertInstanceOf(AssertionError.class, exception.getCause());
    }
}
