package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import my.javacraft.elastic.model.Post;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
public class PostServiceTest {

    @Mock
    ElasticsearchClient esClient;

    private PostService service() {
        return new PostService(esClient);
    }

    @Test
    public void testCreatePostUpsertWithZeroKarma() throws IOException {
        UpdateResponse<Post> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Created);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(updateResponse);

        service().createPost("post-1", "2024-01-15T10:00:00.000Z");

        ArgumentCaptor<UpdateRequest<Post, Post>> captor = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(esClient).update(captor.capture(), any(Class.class));

        UpdateRequest<Post, Post> request = captor.getValue();
        Assertions.assertEquals("post-1", request.id());
        Assertions.assertEquals("posts", request.index());
    }

    @Test
    public void testUpdateKarmaIssuesScriptedUpdate() throws IOException {
        UpdateResponse<Post> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(updateResponse);

        service().updateKarma("post-1", 1);

        ArgumentCaptor<UpdateRequest<Post, Post>> captor = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(esClient).update(captor.capture(), any(Class.class));

        UpdateRequest<Post, Post> request = captor.getValue();
        Assertions.assertEquals("post-1", request.id());
        Assertions.assertEquals("posts", request.index());
        Assertions.assertNotNull(request.script(), "scripted update must be used for atomic karma increment");
        Assertions.assertNull(request.upsert(), "karma update must not upsert — post must pre-exist");
    }

    @Test
    public void testUpdateKarmaSkipsOrphanedVoteGracefully() throws IOException {
        // document_missing_exception = no post document in the index (orphaned vote from scheduler test,
        // or posts index not yet seeded). Must be silently swallowed, not propagated.
        ErrorCause errorCause = mock(ErrorCause.class);
        when(errorCause.type()).thenReturn("document_missing_exception");
        ElasticsearchException esException = mock(ElasticsearchException.class);
        when(esException.error()).thenReturn(errorCause);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenThrow(esException);

        Assertions.assertDoesNotThrow(
                () -> service().updateKarma("post-id-0", 1),
                "karma update for orphaned vote must be silently skipped, not thrown"
        );
    }

    @Test
    public void testUpdateKarmaRethrowsOtherElasticsearchExceptions() throws IOException {
        // Any exception type other than document_missing_exception must propagate.
        ErrorCause errorCause = mock(ErrorCause.class);
        when(errorCause.type()).thenReturn("index_not_found_exception");
        ElasticsearchException esException = mock(ElasticsearchException.class);
        when(esException.error()).thenReturn(errorCause);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenThrow(esException);

        Assertions.assertThrows(
                ElasticsearchException.class,
                () -> service().updateKarma("post-1", 1),
                "unexpected ES errors must not be silently swallowed"
        );
    }

    @Test
    public void testUpdateKarmaCalledMultipleTimes() throws IOException {
        UpdateResponse<Post> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(updateResponse);

        service().updateKarma("post-1",  1);
        service().updateKarma("post-1", -1);
        service().updateKarma("post-1",  2);

        verify(esClient, times(3)).update(any(UpdateRequest.class), any(Class.class));
    }
}
