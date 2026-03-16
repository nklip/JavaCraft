package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserClickTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked"})
@ExtendWith(MockitoExtension.class)
public class UserActivityIngestionServiceTest {

    @Mock
    ElasticsearchClient esClient;

    @Test
    public void testIngestCreatesNewDocument() throws IOException {
        IndexResponse indexResponse = mock(IndexResponse.class);
        when(indexResponse.id()).thenReturn("auto-generated-id");
        when(indexResponse.result()).thenReturn(Result.Created);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

        UserClick userClick = UserClickTest.createHitCount();
        UserActivityIngestionService service = new UserActivityIngestionService(esClient);

        UserClickResponse response = service.ingestUserClick(userClick, "2024-01-15T10:00:00.000Z");

        Assertions.assertNotNull(response);
        Assertions.assertEquals("auto-generated-id", response.getDocumentId());
        Assertions.assertEquals(Result.Created, response.getResult());
    }

    @Test
    public void testIngestTwiceBothReturnCreated() throws IOException {
        IndexResponse indexResponse = mock(IndexResponse.class);
        when(indexResponse.id()).thenReturn("id-1").thenReturn("id-2");
        when(indexResponse.result()).thenReturn(Result.Created);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.index(any(IndexRequest.class))).thenReturn(indexResponse);

        UserClick userClick = UserClickTest.createHitCount();
        UserActivityIngestionService service = new UserActivityIngestionService(esClient);

        UserClickResponse first = service.ingestUserClick(userClick, "2024-01-15T10:00:00.000Z");
        UserClickResponse second = service.ingestUserClick(userClick, "2024-01-15T10:01:00.000Z");

        // Every click is a new immutable document — always 'Created', never 'Updated'
        Assertions.assertEquals(Result.Created, first.getResult());
        Assertions.assertEquals(Result.Created, second.getResult());
        Assertions.assertNotEquals(first.getDocumentId(), second.getDocumentId());
    }
}
