package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import my.javacraft.elastic.model.UserPostEvent;
import my.javacraft.elastic.model.UserPostEventResponse;
import my.javacraft.elastic.model.UserPostEventTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the three branches of the vote-state machine enforced by the Painless script:
 *
 * <pre>
 *   No document → Created
 *   Same action → NoOp    (script sets ctx.op = 'noop', nothing written)
 *   Diff action → Updated (script updates action + timestamp)
 * </pre>
 *
 * In production the script runs inside ES; here we mock the UpdateResponse / DeleteResponse
 * to represent each of those outcomes and verify the service returns them correctly.
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
public class UserActivityServiceTest {

    @Mock
    ElasticsearchClient esClient;

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserActivityService service() {
        return new UserActivityService(esClient);
    }

    private void stubDelete(String docId, Result result) throws IOException {
        DeleteResponse deleteResponse = mock(DeleteResponse.class);
        when(deleteResponse.id()).thenReturn(docId);
        when(deleteResponse.result()).thenReturn(result);
        when(esClient.delete(any(DeleteRequest.class))).thenReturn(deleteResponse);
    }

    private void stubUpdate(String docId, Result result) throws IOException {
        UpdateResponse<Object> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.id()).thenReturn(docId);
        when(updateResponse.result()).thenReturn(result);
        when(esClient._jsonpMapper()).thenReturn(new JacksonJsonpMapper());
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(
                (UpdateResponse) updateResponse
        );
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    public void testFirstVoteCreatesDocument() throws IOException {
        // Arrange
        UserPostEvent userPostEvent = UserPostEventTest.createHitCount();
        String expectedId = userPostEvent.getUserId() + "_" + userPostEvent.getPostId();
        stubUpdate(expectedId, Result.Created);

        // Act
        UserPostEventResponse response = service().ingestUserEvent(userPostEvent, "2024-01-15T10:00:00Z");

        // Assert
        Assertions.assertEquals(expectedId, response.getDocumentId());
        Assertions.assertEquals(Result.Created, response.getResult());
    }

    @Test
    public void testSameVoteRepeatedIsIgnored() throws IOException {
        // Arrange: ES Painless script returns NoOp when action hasn't changed
        UserPostEvent userPostEvent = UserPostEventTest.createHitCount();   // action = UPVOTE
        String expectedId = userPostEvent.getUserId() + "_" + userPostEvent.getPostId();
        stubUpdate(expectedId, Result.NoOp);

        // Act: user tries to upvote the same post a second time
        UserPostEventResponse response = service().ingestUserEvent(userPostEvent, "2024-01-15T10:05:00Z");

        // Assert: no document was written
        Assertions.assertEquals(expectedId, response.getDocumentId());
        Assertions.assertEquals(Result.NoOp, response.getResult());
    }

    @Test
    public void testDifferentVoteChangesAction() throws IOException {
        // Arrange: ES Painless script returns Updated when user switches from UPVOTE → DOWNVOTE
        UserPostEvent userPostEvent = UserPostEventTest.createHitCount();   // first action = UPVOTE
        userPostEvent.setAction("Downvote");                        // now changing to DOWNVOTE
        String expectedId = userPostEvent.getUserId() + "_" + userPostEvent.getPostId();
        stubUpdate(expectedId, Result.Updated);

        // Act
        UserPostEventResponse response = service().ingestUserEvent(userPostEvent, "2024-01-15T10:10:00Z");

        // Assert: the document was updated in-place (still one doc per user+post)
        Assertions.assertEquals(expectedId, response.getDocumentId());
        Assertions.assertEquals(Result.Updated, response.getResult());
    }

    @Test
    public void testNovoteDeletesExistingVote() throws IOException {
        // Arrange: user cancels a vote they previously cast
        UserPostEvent userPostEvent = UserPostEventTest.createHitCount();
        userPostEvent.setAction("novote");           // case-insensitive: normalised to NOVOTE
        String expectedId = userPostEvent.getUserId() + "_" + userPostEvent.getPostId();
        stubDelete(expectedId, Result.Deleted);

        // Act
        UserPostEventResponse response = service().ingestUserEvent(userPostEvent, "2024-01-15T10:20:00Z");

        // Assert: document removed, no vote stored
        Assertions.assertEquals(expectedId, response.getDocumentId());
        Assertions.assertEquals(Result.Deleted, response.getResult());
    }

    @Test
    public void testNovoteOnNonExistentVoteReturnsNotFound() throws IOException {
        // Arrange: user sends NOVOTE but has never voted on this post
        UserPostEvent userPostEvent = UserPostEventTest.createHitCount();
        userPostEvent.setAction("NOVOTE");
        String expectedId = userPostEvent.getUserId() + "_" + userPostEvent.getPostId();
        stubDelete(expectedId, Result.NotFound);

        // Act
        UserPostEventResponse response = service().ingestUserEvent(userPostEvent, "2024-01-15T10:20:00Z");

        // Assert: graceful no-op, no exception thrown
        Assertions.assertEquals(expectedId, response.getDocumentId());
        Assertions.assertEquals(Result.NotFound, response.getResult());
    }
}
