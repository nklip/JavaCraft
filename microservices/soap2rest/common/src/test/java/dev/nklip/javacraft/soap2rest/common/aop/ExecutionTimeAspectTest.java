package dev.nklip.javacraft.soap2rest.common.aop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.FilterType;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionTimeAspectTest {

    private static final Pattern EXECUTION_TIME_PATTERN =
            Pattern.compile("Method Execution: (\\d+) ms");

    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        aspectLogger = (Logger) LoggerFactory.getLogger(ExecutionTimeAspect.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        aspectLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        aspectLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void testAroundReturnsResultCallsProceedAndLogsExecutionTime() throws Throwable {
        ExecutionTimeAspect aspect = new ExecutionTimeAspect();
        ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);

        when(point.getSignature()).thenReturn(signature);
        when(signature.toString()).thenReturn("calculate");
        when(point.proceed()).thenReturn("ok");

        Object result = aspect.around(point);

        Assertions.assertEquals("ok", result);
        verify(point).proceed();

        String logMessage = getOnlyLogMessage();
        Assertions.assertTrue(logMessage.contains("Method Signature: calculate"));
        assertExecutionTimeAtLeast(logMessage, 0);
    }

    @Test
    void testAroundPropagatesProceedExceptionWithoutLoggingSuccess() throws Throwable {
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
        Assertions.assertTrue(logAppender.list.isEmpty());
    }

    /**
     * The two tests above invoke the advice directly, so they pass even when the
     * {@code @Around} pointcut names a package that does not exist and therefore matches
     * nothing. The tests below wire a real proxying context instead: Spring only creates a
     * proxy when an advisor actually applies, so proxy creation is the observable proof
     * that the pointcut resolves to {@link ExecutionTime}.
     */
    @Configuration
    @EnableAspectJAutoProxy
    static class ProxyingConfig {

        @Bean
        ExecutionTimeAspect executionTimeAspect() {
            return new ExecutionTimeAspect();
        }

        @Bean
        AnnotatedService annotatedService() {
            return new AnnotatedService();
        }

        @Bean
        UnannotatedService unannotatedService() {
            return new UnannotatedService();
        }
    }

    static class AnnotatedService {

        @ExecutionTime
        String measured(long waitTimeMs) throws InterruptedException {
            Thread.sleep(waitTimeMs);
            return "measured:" + waitTimeMs;
        }
    }

    static class UnannotatedService {

        String plain() {
            return "plain";
        }
    }

    @Test
    void testPointcutAdvisesAnnotatedMethodsAndLogsExecutionTime() throws InterruptedException {
        long waitTimeMs = 10;

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ProxyingConfig.class)) {

            AnnotatedService service = context.getBean(AnnotatedService.class);

            Assertions.assertTrue(
                    AopUtils.isAopProxy(service),
                    "@ExecutionTime methods must be advised; an unmatched pointcut leaves the bean unproxied"
            );
            Assertions.assertEquals("measured:" + waitTimeMs, service.measured(waitTimeMs));

            String logMessage = getOnlyLogMessage();
            Assertions.assertTrue(logMessage.contains("AnnotatedService.measured(long)"));
            assertExecutionTimeAtLeast(logMessage, waitTimeMs);
        }
    }

    /**
     * Mirrors how the applications actually pick the aspect up: it is discovered as a
     * component-scanned class, not registered through an explicit {@code @Bean} method.
     */
    @Configuration
    @EnableAspectJAutoProxy
    @ComponentScan(
            basePackageClasses = ExecutionTimeAspect.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = ".*Test.*"
            )
    )
    static class ComponentScanningConfig {

        @Bean
        AnnotatedService annotatedService() {
            return new AnnotatedService();
        }
    }

    @Test
    void testAspectAppliesWhenDiscoveredByComponentScanning() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ComponentScanningConfig.class)) {

            AnnotatedService service = context.getBean(AnnotatedService.class);

            Assertions.assertTrue(
                    AopUtils.isAopProxy(service),
                    "the aspect must still advise when it is component-scanned, which is how the apps load it"
            );
        }
    }

    @Test
    void testPointcutLeavesUnannotatedBeansAlone() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ProxyingConfig.class)) {

            UnannotatedService service = context.getBean(UnannotatedService.class);

            Assertions.assertFalse(
                    AopUtils.isAopProxy(service),
                    "the pointcut must stay specific to @ExecutionTime rather than advising every bean"
            );
            Assertions.assertEquals("plain", service.plain());
        }
    }

    private String getOnlyLogMessage() {
        Assertions.assertEquals(1, logAppender.list.size());
        return logAppender.list.getFirst().getFormattedMessage();
    }

    private void assertExecutionTimeAtLeast(String logMessage, long expectedTimeMs) {
        Matcher matcher = EXECUTION_TIME_PATTERN.matcher(logMessage);
        Assertions.assertTrue(matcher.find(), "execution-time log entry is missing");

        long actualTimeMs = Long.parseLong(matcher.group(1));
        Assertions.assertTrue(
                actualTimeMs >= expectedTimeMs,
                "expected execution time of at least %s ms but was %s ms"
                        .formatted(expectedTimeMs, actualTimeMs)
        );
    }
}
