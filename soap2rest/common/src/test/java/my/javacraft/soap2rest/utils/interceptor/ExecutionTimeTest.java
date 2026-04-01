package my.javacraft.soap2rest.utils.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ExecutionTimeTest {

    @Test
    void testAnnotationHasMethodTargetAndRuntimeRetention() {
        Target target = ExecutionTime.class.getAnnotation(Target.class);
        Retention retention = ExecutionTime.class.getAnnotation(Retention.class);

        Assertions.assertNotNull(target);
        Assertions.assertNotNull(retention);
        Assertions.assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
        Assertions.assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void testAnnotationIsAvailableOnAnnotatedMethod() throws NoSuchMethodException {
        Method method = AnnotatedClass.class.getDeclaredMethod("annotatedMethod");

        Assertions.assertTrue(method.isAnnotationPresent(ExecutionTime.class));
    }

    private static final class AnnotatedClass {

        @ExecutionTime
        void annotatedMethod() {
        }
    }
}
