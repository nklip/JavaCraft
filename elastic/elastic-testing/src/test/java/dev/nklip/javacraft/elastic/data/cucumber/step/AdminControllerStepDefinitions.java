package dev.nklip.javacraft.elastic.data.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import io.cucumber.java.en.Given;
import java.io.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
public class AdminControllerStepDefinitions {

    private static final Set<String> SUPPORTED_INDEXES = Set.of("books", "companies", "movies", "music", "people", "posts", "user-votes");

    int port;
    ElasticsearchClient esClient;

    public AdminControllerStepDefinitions(
            @LocalServerPort int port,
            @Autowired ElasticsearchClient esClient) {
        this.port = port;
        this.esClient = esClient;
    }

    /**
     * Drops the index if it exists, then recreates it via the admin endpoint.
     * Use in test {@code prepare data} scenarios that must start with a clean slate,
     * e.g. scheduler tests that assert zero outdated records but could otherwise see
     * leftover documents from a previous feature run.
     */
    @Given("index {string} is recreated")
    public void recreateIndex(String index) throws IOException {
        if (!SUPPORTED_INDEXES.contains(index)) {
            throw new IllegalArgumentException("AdminController does not support index: " + index);
        }
        ExistsRequest existsRequest = new ExistsRequest.Builder().index(index).build();
        if (esClient.indices().exists(existsRequest).value()) {
            esClient.indices().delete(d -> d.index(index));
            log.info("index '{}' deleted for clean-slate recreation", index);
        }
        createIndex(index);
    }

    @Given("index {string} exists")
    public void createIndex(String index) {
        if (!SUPPORTED_INDEXES.contains(index)) {
            throw new IllegalArgumentException("AdminController does not support index: " + index);
        }

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:%s/api/admin/indexes/%s".formatted(port, index),
                HttpMethod.PUT,
                HttpEntity.EMPTY,
                String.class
        );
        int statusCode = response.getStatusCode().value();
        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful());
        Assertions.assertTrue(statusCode == 200 || statusCode == 201);
        Assertions.assertTrue(response.getBody().contains("\"acknowledged\":true"));
        log.info("index '{}' ensured via admin endpoint with status {}", index, statusCode);
    }

}
