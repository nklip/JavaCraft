package dev.nklip.javacraft.soap2rest.soap.service;

import tools.jackson.core.JacksonException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class HttpCallService {

    public static final String AUTH_TOKEN_HEADER_NAME = "X-API-KEY";

    private final String host;
    private final String port;
    private final RestClient restClient;

    public HttpCallService(
            @Value("${rest-app.host}") String host,
            @Value("${rest-app.port}") String port,
            @Value("${rest-app.auth-token:57AkjqNuz44QmUHQuvVo}") String authToken,
            @Value("${rest-app.timeout.connect:2s}") Duration connectTimeout,
            @Value("${rest-app.timeout.read:10s}") Duration readTimeout
    ) {
        this.host = host;
        this.port = port;
        this.restClient = createRestClient(baseHost(), authToken, connectTimeout, readTimeout);
    }

    public String baseHost() {
        return host + ":" + port;
    }

    public <T> ResponseEntity<T> put(String methodUrl, Class<T> objectType, Object object)
            throws JacksonException {
        return restClient.method(HttpMethod.PUT)
                .uri(methodUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(object)
                .retrieve()
                .toEntity(objectType);
    }

    public ResponseEntity<String> delete(String methodUrl) {
        return restClient.method(HttpMethod.DELETE)
                .uri(methodUrl)
                .retrieve()
                .toEntity(String.class);
    }

    public <T> ResponseEntity<T> get(String methodUrl, Class<T> type) {
        return restClient.method(HttpMethod.GET)
                .uri(methodUrl)
                .retrieve()
                .toEntity(type);
    }

    private static RestClient createRestClient(
            String baseUrl,
            String authToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(AUTH_TOKEN_HEADER_NAME, authToken)
                .build();
    }
}
