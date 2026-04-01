package dev.nklip.javacraft.soap2rest.soap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class HttpCallService {

    public static final String AUTH_TOKEN_HEADER_NAME = "X-API-KEY";

    @Value("${rest-app.host}")
    String host;

    @Value("${rest-app.port}")
    String port;

    RestTemplate restTemplate;
    ObjectMapper objectMapper;

    public HttpCallService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public String baseHost() {
        return host + ":" + port;
    }

    MultiValueMap<String, String> getHeaders() {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(AUTH_TOKEN_HEADER_NAME, "57AkjqNuz44QmUHQuvVo");
        return headers;
    }

    public<T> ResponseEntity<T> put(String methodUrl, Class<T> objectType, Object object) throws JsonProcessingException {
        MultiValueMap<String, String> headers = getHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(object), headers);

        // like that http://localhost:8081/api/v1/smart/1/gas
        String url = "%s%s".formatted(baseHost(), methodUrl);
        return restTemplate.exchange(
                url,
                HttpMethod.PUT,
                entity,
                objectType
        );
    }

    public ResponseEntity<String> delete(String methodUrl) {
        MultiValueMap<String, String> headers = getHeaders();

        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        // like that http://localhost:8081/api/v1/smart/1/gas
        String url = "%s%s".formatted(baseHost(), methodUrl);
        return restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                entity,
                String.class
        );
    }

    public<T> ResponseEntity<T> get(String methodUrl, Class<T> type) {
        MultiValueMap<String, String> headers = getHeaders();

        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        // like that http://localhost:8081/api/v1/smart/1/gas
        String url = "%s%s".formatted(baseHost(), methodUrl);
        return restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                type
        );
    }
}
