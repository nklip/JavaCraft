package dev.nklip.javacraft.elastic.app.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.nklip.javacraft.elastic.api.model.ClientType;
import dev.nklip.javacraft.elastic.api.model.ContentCategory;
import dev.nklip.javacraft.elastic.api.model.ContentSearchRequest;
import dev.nklip.javacraft.elastic.app.service.SearchService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.http.ResponseEntity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SearchControllerTest {

    @Mock
    SearchService searchService;

    @Test
    public void testWildcardSearch() throws IOException {
        SearchController searchServiceController = new SearchController(searchService);

        Map<String, String> document = new LinkedHashMap<>();
        document.put("result", "test1 value");
        List<Object> documentList = new ArrayList<>();
        documentList.add(document);
        when(searchService.wildcardSearch(any(ContentSearchRequest.class))).thenReturn(documentList);

        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setPattern("test1");

        ResponseEntity<List<Object>> response = searchServiceController.wildcardSearch(contentSearchRequest);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertFalse(response.getBody().isEmpty());
        Assertions.assertEquals(1, response.getBody().size());
    }

    @Test
    public void testFuzzySearch() throws IOException {
        SearchController searchServiceController = new SearchController(searchService);

        Map<String, String> document = new LinkedHashMap<>();
        document.put("result", "test2 value");
        List<Object> documentList = new ArrayList<>();
        documentList.add(document);
        when(searchService.fuzzySearch(any(ContentSearchRequest.class))).thenReturn(documentList);

        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setPattern("tes?");

        ResponseEntity<List<Object>> response = searchServiceController.fuzzySearch(contentSearchRequest);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertFalse(response.getBody().isEmpty());
        Assertions.assertEquals(1, response.getBody().size());
    }

    @Test
    public void testIntervalSearch() throws IOException {
        SearchController searchServiceController = new SearchController(searchService);

        Map<String, String> document = new LinkedHashMap<>();
        document.put("result", "test3 should be submitted");
        List<Object> documentList = new ArrayList<>();
        documentList.add(document);
        when(searchService.intervalSearch(any(ContentSearchRequest.class))).thenReturn(documentList);

        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setPattern("test3 submitted");

        ResponseEntity<List<Object>> response = searchServiceController.intervalSearch(contentSearchRequest);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertFalse(response.getBody().isEmpty());
        Assertions.assertEquals(1, response.getBody().size());
    }

    @Test
    public void testSpanSearch() throws IOException {
        SearchController searchServiceController = new SearchController(searchService);

        Map<String, String> document = new LinkedHashMap<>();
        document.put("result", "test3 should be submitted");
        List<Object> documentList = new ArrayList<>();
        documentList.add(document);
        when(searchService.spanSearch(any(ContentSearchRequest.class))).thenReturn(documentList);

        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setPattern("test3 submitted");

        ResponseEntity<List<Object>> response = searchServiceController.spanSearch(contentSearchRequest);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertFalse(response.getBody().isEmpty());
        Assertions.assertEquals(1, response.getBody().size());
    }

    @Test
    public void testSearch() throws IOException {
        SearchController searchServiceController = new SearchController(searchService);

        List<Document> documentList = new ArrayList<>();
        when(searchService.search(any(ContentSearchRequest.class))).thenReturn(documentList);

        ContentSearchRequest contentSearchRequest = new ContentSearchRequest();
        contentSearchRequest.setClient(ClientType.WEB.toString());
        contentSearchRequest.setType(ContentCategory.ALL.toString());
        contentSearchRequest.setPattern("test4");

        ResponseEntity<List<Document>> response = searchServiceController.search(contentSearchRequest);

        Assertions.assertNotNull(response);
        Assertions.assertNotNull(response.getBody());
        Assertions.assertTrue(response.getBody().isEmpty());
    }

}
