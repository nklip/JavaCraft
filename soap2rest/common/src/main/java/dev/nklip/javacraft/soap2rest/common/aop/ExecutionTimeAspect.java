package dev.nklip.javacraft.soap2rest.common.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.context.annotation.Configuration;

/**
 * Aspect that measures methods annotated with {@link ExecutionTime}.
 *
 * <p>How it works:</p>
 * <p>1. The {@code @Around} advice is triggered before the annotated method starts.</p>
 * <p>2. The current timestamp is captured.</p>
 * <p>3. {@link ProceedingJoinPoint#proceed()} invokes the original method.</p>
 * <p>4. After the method returns, the advice captures the end timestamp and logs the duration.</p>
 * <p>5. The original return value is passed back unchanged.</p>
 *
 * <p>If the target method throws an exception, this aspect does not swallow it;
 * the exception is propagated to the caller and the success log is not written.</p>
 */
@Slf4j
@Aspect
@Configuration
public class ExecutionTimeAspect {

    /**
     * Surrounds an {@link ExecutionTime}-annotated method with timing logic.
     */
    @Around("@annotation(dev.nklip.javacraft.soap2rest.utils.interceptor.ExecutionTime)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        Long startedTime = System.currentTimeMillis();

        Object returnObject = point.proceed();

        Long endedTime = System.currentTimeMillis();

        String logOutput =
                """
                \n===============================================================
                Method Signature: %s
                Method Execution: %s ms
                """.formatted(point.getSignature(), endedTime - startedTime);

        log.info(logOutput);

        return returnObject;
    }

}
