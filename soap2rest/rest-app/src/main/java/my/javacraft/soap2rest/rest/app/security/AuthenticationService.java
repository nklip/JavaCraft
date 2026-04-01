package my.javacraft.soap2rest.rest.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.util.ResourceUtils;

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
        File file = ResourceUtils.getFile("classpath:api.keys");
        return Set.copyOf(Files.readAllLines(Paths.get(file.getAbsolutePath())));
    }

}
