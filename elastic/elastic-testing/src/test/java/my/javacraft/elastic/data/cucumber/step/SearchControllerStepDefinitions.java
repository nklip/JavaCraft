package my.javacraft.elastic.data.cucumber.step;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.api.model.ClientType;
import my.javacraft.elastic.api.model.ContentSearchRequest;
import my.javacraft.elastic.data.cucumber.conf.CucumberSpringConfiguration;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
public class SearchControllerStepDefinitions {

    private static final AtomicBoolean SEARCH_DATASET_PREPARED = new AtomicBoolean(false);
    private static final Object SEARCH_DATASET_LOCK = new Object();

    @LocalServerPort
    int port;

    @Autowired
    ElasticsearchClient esClient;

    @Given("search dataset is prepared once")
    public void prepareSearchDatasetOnce() throws IOException {
        if (SEARCH_DATASET_PREPARED.get()) {
            log.info("search dataset setup already completed; skipping reinitialization");
            return;
        }

        synchronized (SEARCH_DATASET_LOCK) {
            if (SEARCH_DATASET_PREPARED.get()) {
                log.info("search dataset setup already completed by another scenario; skipping");
                return;
            }

            AdminControllerStepDefinitions adminSteps = new AdminControllerStepDefinitions(port, esClient);
            adminSteps.recreateIndex("books");
            adminSteps.recreateIndex("companies");
            adminSteps.recreateIndex("movies");
            adminSteps.recreateIndex("music");
            adminSteps.recreateIndex("people");

            ingestJson("data/json/books.json",     "books");
            ingestJson("data/json/companies.json", "companies");
            ingestJson("data/json/movies.json",    "movies");
            ingestJson("data/json/music.json",     "music");
            ingestJson("data/json/people.json",    "people");

            SEARCH_DATASET_PREPARED.set(true);
            log.info("search dataset setup completed once for SearchController scenarios");
        }
    }

    @Then("ingest {string} json file in {string} index")
    public void ingestJson(String pathToFile, String index) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        File resource = new ClassPathResource(pathToFile).getFile();
        TypeReference<List<LinkedHashMap<String, Object>>> typeRef = new TypeReference<>() {};
        List<LinkedHashMap<String, Object>> movies = objectMapper.readValue(resource, typeRef);

        Assertions.assertNotNull(movies);

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

    @When("wildcard search for {string} in {string}")
    public void testWildcard(String pattern, String type, DataTable dataTable) throws IOException, InterruptedException {
        String jsonBody = jsonBody(pattern, type);
        assertSearchResponseEventually("/api/services/search/wildcard", jsonBody, dataTable);
    }

    @When("fuzzy search for {string} in {string}")
    public void testFuzzy(String pattern, String type, DataTable dataTable) throws IOException, InterruptedException {
        String jsonBody = jsonBody(pattern, type);
        assertSearchResponseEventually("/api/services/search/fuzzy", jsonBody, dataTable);
    }

    @When("span search for {string} in {string}")
    public void testSpan(String pattern, String type, DataTable dataTable) throws IOException, InterruptedException {
        String jsonBody = jsonBody(pattern, type);
        assertSearchResponseEventually("/api/services/search/span", jsonBody, dataTable);
    }

    @When("interval search for {string} in {string}")
    public void testInterval(String pattern, String type, DataTable dataTable) throws IOException, InterruptedException {
        String jsonBody = jsonBody(pattern, type);
        assertSearchResponseEventually("/api/services/search/interval", jsonBody, dataTable);
    }

    @When("search for {string} in {string}")
    public void testSearch(String pattern, String type, DataTable dataTable) throws IOException, InterruptedException {
        String jsonBody = jsonBody(pattern, type);
        assertSearchResponseEventually("/api/services/search", jsonBody, dataTable);
    }

    private void assertSearchResponseEventually(String path, String jsonBody, DataTable expectedData) throws InterruptedException {
        int expectedRows = expectedData.height();

        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(expectedRows,
                () -> Optional.of(postSearch(path, jsonBody))
                        .map(HttpEntity::getBody)
                        .map(List::size)
                        .orElse(0)
        ), "Expecting " + expectedRows + " rows in actual search response");

        ResponseEntity<List<LinkedHashMap<String, Object>>> actualResponse = postSearch(path, jsonBody);
        compareDataTableToHttpResponse(expectedData, actualResponse);
    }

    private ResponseEntity<List<LinkedHashMap<String, Object>>> postSearch(String path, String jsonBody) {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:%s%s".formatted(port, path);

        return restTemplate.exchange(
                url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {}
        );
    }

    private void compareDataTableToHttpResponse(
            DataTable dataTable,
            ResponseEntity<List<LinkedHashMap<String, Object>>> httpResponse) {
        Assertions.assertNotNull(httpResponse);
        Assertions.assertNotNull(httpResponse.getBody());
        Assertions.assertEquals(dataTable.cells().size(), httpResponse.getBody().size());

        for (int i = 0; i < dataTable.cells().size(); i++) {
            LinkedHashMap<String, Object> map = httpResponse.getBody().get(i);
            List<String> row = dataTable.cells().get(i);

            for (String cell : row) {
                if (cell.contains("=")) {
                    String[] cellValues = cell.split("=");
                    String key = cellValues[0].trim();
                    String value = cellValues[1].trim();

                    Assertions.assertEquals(value, map.get(key).toString());
                }
            }
        }
    }

    private String jsonBody(String pattern, String type) throws JsonProcessingException {
        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setType(type);
        contentSearchRequest.setPattern(pattern);
        contentSearchRequest.setClient(ClientType.WEB.toString());

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonBody = objectMapper.writeValueAsString(contentSearchRequest);
        log.info("created json:\n{}", jsonBody);
        return jsonBody;
    }


}
