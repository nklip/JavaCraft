package my.javacraft.soap2rest.utils.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for lightweight execution-time logging.
 *
 * <p>{@link ExecutionTimeAspect} looks for this annotation at runtime,
 * wraps the annotated method call, measures how long the invocation takes,
 * and logs the method signature together with the elapsed time in milliseconds.
 * The annotation itself does not change the method result or exception flow.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExecutionTime {
}
