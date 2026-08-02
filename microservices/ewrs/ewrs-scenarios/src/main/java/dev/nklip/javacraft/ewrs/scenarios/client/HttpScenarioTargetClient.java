package dev.nklip.javacraft.ewrs.scenarios.client;

import tools.jackson.databind.json.JsonMapper;
import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CompleteWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import dev.nklip.javacraft.ewrs.scenarios.config.ScenariosProperties;
import dev.nklip.javacraft.ewrs.scenarios.exception.ScenarioExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP implementation of the scenario gateway that drives {@code ewrs-app} over its public API.
 * This keeps scenario generation honest by exercising the same REST contract a real external caller would use.
 */
@Component
public class HttpScenarioTargetClient implements ScenarioTargetClient {

    private final ScenariosProperties properties;
    private final JsonMapper jsonMapper;
    private final JdkClientHttpRequestFactory requestFactory;

    public HttpScenarioTargetClient(ScenariosProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Keep the driver on plain HTTP/1.1 so local test runs do not depend on
                // h2/h2c negotiation behavior of the JDK client against the embedded server.
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.requestFactory = factory;
    }

    @Override
    public WorkRequestResponse create(CreateWorkRequest request) {
        try {
            return exchange(HttpMethod.POST, "/api/v1/work-requests", request);
        } catch (RestClientResponseException e) {
            throw unexpectedResponse("/api/v1/work-requests", e);
        }
    }

    @Override
    public WorkRequestResponse approve(int requestId, ApproveWorkRequest request) {
        String path = "/api/v1/work-requests/%s/approve".formatted(requestId);
        try {
            return exchange(HttpMethod.POST, path, request);
        } catch (RestClientResponseException e) {
            throw unexpectedResponse(path, e);
        }
    }

    @Override
    public WorkRequestResponse reject(int requestId, RejectWorkRequest request) {
        String path = "/api/v1/work-requests/%s/reject".formatted(requestId);
        try {
            return exchange(HttpMethod.POST, path, request);
        } catch (RestClientResponseException e) {
            throw unexpectedResponse(path, e);
        }
    }

    @Override
    public WorkRequestResponse start(int requestId, StartWorkRequest request) {
        String path = "/api/v1/work-requests/%s/start".formatted(requestId);
        try {
            return exchange(HttpMethod.POST, path, request);
        } catch (RestClientResponseException e) {
            throw unexpectedResponse(path, e);
        }
    }

    @Override
    public WorkRequestResponse complete(int requestId, CompleteWorkRequest request) {
        String path = "/api/v1/work-requests/%s/complete".formatted(requestId);
        try {
            return exchange(HttpMethod.POST, path, request);
        } catch (RestClientResponseException e) {
            throw unexpectedResponse(path, e);
        }
    }

    @Override
    public ErrorResponse startExpectingConflict(int requestId, StartWorkRequest request) {
        String path = "/api/v1/work-requests/%s/start".formatted(requestId);
        try {
            WorkRequestResponse response = exchange(HttpMethod.POST, path, request);
            throw new ScenarioExecutionException("""
                    Expected START for work request %s to fail with HTTP 409, but it succeeded with status %s
                    """.stripIndent().formatted(requestId, response.status()));
        } catch (RestClientResponseException e) {
            if (!e.getStatusCode().equals(HttpStatus.CONFLICT)) {
                throw unexpectedResponse(path, e);
            }
            return deserializeErrorResponse(e);
        }
    }

    @Override
    public @Nullable WorkRequestResponse getRequestIfAvailable(int requestId) {
        String path = "/api/v1/work-requests/%s".formatted(requestId);
        try {
            return exchange(HttpMethod.GET, path, null);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                return null;
            }
            throw unexpectedResponse(path, e);
        }
    }

    @Override
    public List<WorkRequestTimelineEventResponse> getTimeline(int requestId) {
        String path = "/api/v1/work-requests/%s/timeline".formatted(requestId);
        try {
            return exchangeForList(path, new ParameterizedTypeReference<>() {
            });
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                return List.of();
            }
            throw unexpectedResponse(path, e);
        }
    }

    @Override
    public String targetBaseUrl() {
        return resolveTargetBaseUrl();
    }

    private WorkRequestResponse exchange(HttpMethod method, String path, @Nullable Object body) {
        RestClient.RequestBodySpec requestSpec = restClient().method(method).uri(path);
        if (body != null) {
            requestSpec.body(body);
        }
        WorkRequestResponse response = requestSpec.retrieve().body(WorkRequestResponse.class);
        if (response == null) {
            throw new ScenarioExecutionException("Received an empty response from " + path);
        }
        return response;
    }

    private <T> List<T> exchangeForList(String path, ParameterizedTypeReference<List<T>> responseType) {
        List<T> response = restClient().get().uri(path).retrieve().body(responseType);
        if (response == null) {
            throw new ScenarioExecutionException("Received an empty list response from " + path);
        }
        return response;
    }

    private RestClient restClient() {
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(resolveTargetBaseUrl())
                // RestClient.builder() otherwise builds its own default JsonMapper, which
                // ignores every spring.jackson.* setting. That silently disagreed with how
                // ewrs-app serializes enums, so reuse the application's configured mapper
                // and leave the remaining default converters in place.
                .configureMessageConverters(converters ->
                        converters.withJsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper)))
                .build();
    }

    private String resolveTargetBaseUrl() {
        if (StringUtils.hasText(properties.targetBaseUrl())) {
            return properties.targetBaseUrl().trim();
        }

        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            throw new ScenarioExecutionException("""
                    No scenario target base URL is configured and there is no current HTTP request to infer it from
                    """.stripIndent().trim());
        }

        HttpServletRequest request = servletRequestAttributes.getRequest();
        return "%s://%s:%s".formatted(request.getScheme(), request.getServerName(), request.getServerPort());
    }

    private ScenarioExecutionException unexpectedResponse(String path, RestClientResponseException e) {
        return new ScenarioExecutionException("""
                EWRS scenario driver call to %s failed with HTTP %s: %s
                """.stripIndent().formatted(path, e.getStatusCode(), e.getResponseBodyAsString()), e);
    }

    private ErrorResponse deserializeErrorResponse(RestClientResponseException e) {
        try {
            return jsonMapper.readValue(e.getResponseBodyAsByteArray(), ErrorResponse.class);
        } catch (Exception ex) {
            throw new ScenarioExecutionException("Unable to parse EWRS error response for the expected conflict path", ex);
        }
    }
}
