package dev.nklip.javacraft.soap2rest.rest.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

public class AuthenticationService {

    public static final String AUTH_TOKEN_HEADER_NAME = "X-API-KEY";

    private static final Object API_KEYS_LOCK = new Object();
    private static volatile Set<String> apiKeys = Set.of();

    public static Authentication getAuthentication(
            HttpServletRequest request) throws IOException {
        String apiKey = request.getHeader(AUTH_TOKEN_HEADER_NAME);
        Set<String> allowedApiKeys = getApiKeys();

        if (apiKey == null || !allowedApiKeys.contains(apiKey)) {
            throw new BadCredentialsException("Invalid API Key");
        }

        return new ApiKeyAuthentication(apiKey, AuthorityUtils.NO_AUTHORITIES);
    }

    private static Set<String> getApiKeys() throws IOException {
        Set<String> loadedApiKeys = apiKeys;
        if (loadedApiKeys.isEmpty()) {
            synchronized (API_KEYS_LOCK) {
                loadedApiKeys = apiKeys;
                if (loadedApiKeys.isEmpty()) {
                    loadedApiKeys = loadApiKeys();
                    apiKeys = loadedApiKeys;
                }
            }
        }
        return loadedApiKeys;
    }

    private static Set<String> loadApiKeys() throws IOException {
        ClassPathResource resource = new ClassPathResource("api.keys");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
        )) {
            // Classpath resources may live inside the packaged JAR during Failsafe and at runtime.
            return Set.copyOf(reader.lines().toList());
        }
    }

}
