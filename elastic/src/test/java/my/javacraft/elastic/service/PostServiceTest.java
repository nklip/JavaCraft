package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import my.javacraft.elastic.model.Post;
import my.javacraft.elastic.service.post.IdGenerator;
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
    @Mock
    IdGenerator idGenerator;

    private PostService service() {
        return new PostService(esClient, idGenerator);
    }

    @Test
    public void testSubmitPostIndexesWithServerGeneratedIdAndZeroKarma() throws IOException {
        when(idGenerator.generateUniquePostId()).thenReturn("abc123");
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.index(any(IndexRequest.class))).thenReturn(mock(IndexResponse.class));

        Post returned = service().submitPost("user-001");

        ArgumentCaptor<IndexRequest<Post>> captor = ArgumentCaptor.forClass(IndexRequest.class);
        verify(esClient).index(captor.capture());

        IndexRequest<Post> request = captor.getValue();
        Assertions.assertEquals("posts", request.index());
        Assertions.assertEquals("abc123", request.id());

        Post doc = request.document();
        Assertions.assertEquals("abc123", doc.postId());
        Assertions.assertEquals("user-001", doc.author());
        Assertions.assertEquals(0L,  doc.karma(),       "initial karma must be zero");
        Assertions.assertEquals(0.0, doc.risingScore(), "initial risingScore must be zero — no votes yet");
        Assertions.assertNotNull(doc.createdAt(), "createdAt must be server-generated");

        // returned Post must reflect exactly what was indexed
        Assertions.assertEquals("abc123", returned.postId());
        Assertions.assertEquals("user-001", returned.author());
        Assertions.assertEquals(0L,  returned.karma());
        Assertions.assertEquals(0.0, returned.risingScore());
        Assertions.assertNotNull(returned.createdAt());
    }

    @Test
    public void testUpdateScoresIssuesScriptedUpdate() throws IOException {
        UpdateResponse<Post> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(updateResponse);

        service().updateScores("post-1", 1);

        ArgumentCaptor<UpdateRequest<Post, Post>> captor = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(esClient).update(captor.capture(), any(Class.class));

        UpdateRequest<Post, Post> request = captor.getValue();
        Assertions.assertEquals("post-1", request.id());
        Assertions.assertEquals("posts", request.index());
        Assertions.assertNotNull(request.script(), "scripted update must be used for atomic karma+hotScore increment");
        Assertions.assertNull(request.upsert(), "scores update must not upsert — post must pre-exist");
    }

    @Test
    public void testUpdateScoresSkipsOrphanedVoteGracefully() throws IOException {
        ErrorCause errorCause = mock(ErrorCause.class);
        when(errorCause.type()).thenReturn("document_missing_exception");
        ElasticsearchException esException = mock(ElasticsearchException.class);
        when(esException.error()).thenReturn(errorCause);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenThrow(esException);

        Assertions.assertDoesNotThrow(
                () -> service().updateScores("post-id-0", 1),
                "scores update for orphaned vote must be silently skipped, not thrown"
        );
    }

    @Test
    public void testUpdateScoresRethrowsOtherElasticsearchExceptions() throws IOException {
        ErrorCause errorCause = mock(ErrorCause.class);
        when(errorCause.type()).thenReturn("index_not_found_exception");
        ElasticsearchException esException = mock(ElasticsearchException.class);
        when(esException.error()).thenReturn(errorCause);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenThrow(esException);

        Assertions.assertThrows(
                ElasticsearchException.class,
                () -> service().updateScores("post-1", 1),
                "unexpected ES errors must not be silently swallowed"
        );
    }

    @Test
    public void testUpdateScoresCalledMultipleTimes() throws IOException {
        UpdateResponse<Post> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(updateResponse);

        service().updateScores("post-1",  1);
        service().updateScores("post-1", -1);
        service().updateScores("post-1",  2);

        verify(esClient, times(3)).update(any(UpdateRequest.class), any(Class.class));
    }
}
