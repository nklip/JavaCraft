package dev.nklip.javacraft.soap2rest.common.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This test requires org.mockito.plugins.MockMaker with content to work: mock-maker-subclass
 * <p>
 * This test config helps to avoid inline bytecode redefinition on AspectJ interfaces.
 * <p>
 * Why this works:
 * <p>
 * 1) Error comes from inline mock maker retransformation (attempted to delete a method).
 * 2) mock-maker-subclass avoids that path and works for mocking ProceedingJoinPoint/Signature.
 */
class ExecutionTimeAspectTest {

    @Test
    void testAroundReturnsResultAndCallsProceed() throws Throwable {
        ExecutionTimeAspect aspect = new ExecutionTimeAspect();
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);

        when(point.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn("calculate");
        when(point.proceed()).thenReturn("ok");

        Object result = aspect.around(point);

        Assertions.assertEquals("ok", result);
        verify(point).proceed();
    }

    @Test
    void testAroundPropagatesProceedException() throws Throwable {
        ExecutionTimeAspect aspect = new ExecutionTimeAspect();
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        IllegalStateException expected = new IllegalStateException("boom");

        when(point.proceed()).thenThrow(expected);

        IllegalStateException actual = Assertions.assertThrows(
                IllegalStateException.class,
                () -> aspect.around(point)
        );

        Assertions.assertSame(expected, actual);
        verify(point).proceed();
    }
}
