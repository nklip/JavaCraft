package my.javacraft.elastic.data.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.io.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class AdminControllerStepDefinitions {

    private static final Set<String> SUPPORTED_INDEXES = Set.of("books", "movies", "music", "user-votes", "posts");

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;

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

    @Then("ingest {string} json file with {int} entities in {string} index")
    public void ingestJson(String pathToFile, Integer expectedItems, String index) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        File resource = new ClassPathResource(pathToFile).getFile();
        TypeReference<List<LinkedHashMap<String, Object>>> typeRef = new TypeReference<>() {};
        List<LinkedHashMap<String, Object>> movies = objectMapper.readValue(resource, typeRef);

        Assertions.assertNotNull(movies);
        Assertions.assertEquals(expectedItems, movies.size());

        for (LinkedHashMap<String, Object> entity : movies) {
            String id = createId(entity);
            UpdateRequest<Object, Object> updateRequest = new UpdateRequest.Builder<>()
                    .index(index)
                    .id(id)
                    .doc(entity)
                    .upsert(entity)
                    .build();

            UpdateResponse<Object> updateResponse = esClient.update(updateRequest, Object.class);
            log.info("document with id = '{}' was ingested with the result '{}'",
                    id, updateResponse.result().toString()
            );
        }
    }

    private String createId(LinkedHashMap<String, Object> entity) {
        String id = "";
        if (entity.containsKey("name")) {
            id += ((String)entity.get("name")).toLowerCase().replaceAll(" ", "-");
        }
        if (entity.containsKey("release_year")) {
            if (!id.isEmpty()) {
                id += "-";
            }
            id += entity.get("release_year");
        }
        return id;
    }

}
