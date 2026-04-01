package my.javacraft.soap2rest.rest.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest {

    @AfterEach
    void resetCache() throws Exception {
        resetApiKeyCache();
    }

    @Test
    void testGetAuthenticationReturnsAuthForValidApiKey() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(AuthenticationService.AUTH_TOKEN_HEADER_NAME))
                .thenReturn("57AkjqNuz44QmUHQuvVo");

        Authentication authentication = AuthenticationService.getAuthentication(request);

        Assertions.assertNotNull(authentication);
        Assertions.assertEquals("57AkjqNuz44QmUHQuvVo", authentication.getPrincipal());
    }

    @Test
    void testGetAuthenticationThrowsForInvalidApiKey() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(AuthenticationService.AUTH_TOKEN_HEADER_NAME))
                .thenReturn("invalid-api-key");

        Assertions.assertThrows(
                BadCredentialsException.class,
                () -> AuthenticationService.getAuthentication(request));
    }

    @Test
    void testApiKeyCacheFieldIsVolatileForThreadSafePublish() {
        boolean hasVolatileSetCache = Arrays.stream(AuthenticationService.class.getDeclaredFields())
                .filter(field -> Set.class.isAssignableFrom(field.getType()))
                .anyMatch(field -> Modifier.isVolatile(field.getModifiers()));

        Assertions.assertTrue(
                hasVolatileSetCache,
                "API key cache must be published via volatile field");
    }

    @Test
    void testGetAuthenticationIsSafeUnderConcurrentFirstAccess() throws Exception {
        int threads = 24;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                await(start);
                try {
                    HttpServletRequest request = mock(HttpServletRequest.class);
                    when(request.getHeader(AuthenticationService.AUTH_TOKEN_HEADER_NAME))
                            .thenReturn("M4ZUq1qNOcdl3yfXPfI9");
                    Authentication authentication = AuthenticationService.getAuthentication(request);
                    Assertions.assertNotNull(authentication);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }

        ready.await();
        start.countDown();
        done.await();
        Assertions.assertNull(failure.get(), "Concurrent first access must not fail");
    }

    private void resetApiKeyCache() throws Exception {
        Field cacheField = AuthenticationService.class.getDeclaredField("apiKeys");
        cacheField.setAccessible(true);
        cacheField.set(null, Set.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruptedException);
        }
    }
}
