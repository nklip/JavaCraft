package my.javacraft.elastic.data.cucumber.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.api.model.ClientType;
import my.javacraft.elastic.api.model.ContentSearchRequest;
import my.javacraft.elastic.data.cucumber.conf.CucumberSpringConfiguration;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.core.ParameterizedTypeReference;
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
public class SearchControllerStepDefinitions {

    @LocalServerPort
    int port;

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

    @When("search for {string} in {string}")
    public void testSearch(String pattern, String type, DataTable dataTable) throws IOException, InterruptedException {
        String jsonBody = jsonBody(pattern, type);
        assertSearchResponseEventually("/api/services/search", jsonBody, dataTable);
    }

    private void assertSearchResponseEventually(String path, String jsonBody, DataTable dataTable) throws InterruptedException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:%s%s".formatted(port, path);
        int expectedRows = dataTable.height();

        Assertions.assertTrue(CucumberSpringConfiguration.assertWithWait(expectedRows, () -> {
            ResponseEntity<List<LinkedHashMap<String, Object>>> currentResponse = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {
                    }
            );
            List<LinkedHashMap<String, Object>> currentBody = currentResponse.getBody();
            return currentBody == null ? 0 : currentBody.size();
        }));

        ResponseEntity<List<LinkedHashMap<String, Object>>> httpResponse = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<>() {
                }
        );
        compareHttpResponseToDataTable(httpResponse, dataTable);
    }

    private void compareHttpResponseToDataTable(
            ResponseEntity<List<LinkedHashMap<String, Object>>> httpResponse, DataTable dataTable) {
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
