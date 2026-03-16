package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AdminServiceTest {

    @Mock
    ElasticsearchClient esClient;

    @Test
    public void testCreateUserActivityIndex() throws IOException {
        AdminService adminService = new AdminService(esClient);

        ElasticsearchIndicesClient indicesClient = Mockito.mock(ElasticsearchIndicesClient.class);
        CreateIndexResponse createIndexResponse = Mockito.mock(CreateIndexResponse.class);

        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createIndexResponse);
        when(createIndexResponse.acknowledged()).thenReturn(true);

        AdminService.IndexCreationResult response = adminService.createUserActivityIndex();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.created());
        Assertions.assertNotNull(response.response());
        Assertions.assertTrue(response.response().acknowledged());

        ArgumentCaptor<CreateIndexRequest> requestCaptor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        verify(indicesClient).create(requestCaptor.capture());

        Map<String, ?> properties = requestCaptor.getValue().mappings().properties();
        Assertions.assertTrue(properties.containsKey("timestamp"));
        Assertions.assertTrue(properties.containsKey("userId"));
        Assertions.assertTrue(properties.containsKey("postId"));
        Assertions.assertTrue(properties.containsKey("searchType"));
        Assertions.assertTrue(properties.containsKey("action"));
        Assertions.assertTrue(properties.containsKey("searchValue"));
        Assertions.assertFalse(properties.containsKey("recordId"));
    }

    @Test
    public void testCreateBooksIndex() throws IOException {
        AdminService adminService = new AdminService(esClient);

        ElasticsearchIndicesClient indicesClient = Mockito.mock(ElasticsearchIndicesClient.class);
        CreateIndexResponse createIndexResponse = Mockito.mock(CreateIndexResponse.class);

        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createIndexResponse);
        when(createIndexResponse.acknowledged()).thenReturn(true);

        AdminService.IndexCreationResult response = adminService.createBooksIndex();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.created());
        Assertions.assertNotNull(response.response());
        Assertions.assertTrue(response.response().acknowledged());
    }

    @Test
    public void testCreateMoviesIndex() throws IOException {
        AdminService adminService = new AdminService(esClient);

        ElasticsearchIndicesClient indicesClient = Mockito.mock(ElasticsearchIndicesClient.class);
        CreateIndexResponse createIndexResponse = Mockito.mock(CreateIndexResponse.class);

        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createIndexResponse);
        when(createIndexResponse.acknowledged()).thenReturn(true);

        AdminService.IndexCreationResult response = adminService.createMoviesIndex();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.created());
        Assertions.assertNotNull(response.response());
        Assertions.assertTrue(response.response().acknowledged());
    }

    @Test
    public void testCreateMusicIndex() throws IOException {
        AdminService adminService = new AdminService(esClient);

        ElasticsearchIndicesClient indicesClient = Mockito.mock(ElasticsearchIndicesClient.class);
        CreateIndexResponse createIndexResponse = Mockito.mock(CreateIndexResponse.class);

        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));
        when(indicesClient.create(any(CreateIndexRequest.class))).thenReturn(createIndexResponse);
        when(createIndexResponse.acknowledged()).thenReturn(true);

        AdminService.IndexCreationResult response = adminService.createMusicIndex();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.created());
        Assertions.assertNotNull(response.response());
        Assertions.assertTrue(response.response().acknowledged());
    }

    @Test
    public void testCreateIndexShouldReturnNoOpWhenIndexAlreadyExists() throws IOException {
        AdminService adminService = new AdminService(esClient);
        ElasticsearchIndicesClient indicesClient = Mockito.mock(ElasticsearchIndicesClient.class);

        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(true));

        AdminService.IndexCreationResult response = adminService.createBooksIndex();

        Assertions.assertNotNull(response);
        Assertions.assertFalse(response.created());
        Assertions.assertNotNull(response.response());
        Assertions.assertEquals("books", response.response().index());
        Assertions.assertTrue(response.response().acknowledged());
    }
}
