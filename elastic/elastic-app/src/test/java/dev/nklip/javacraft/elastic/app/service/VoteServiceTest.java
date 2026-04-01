package dev.nklip.javacraft.elastic.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import java.io.IOException;
import dev.nklip.javacraft.elastic.api.model.UserVote;
import dev.nklip.javacraft.elastic.api.model.VoteRequest;
import dev.nklip.javacraft.elastic.api.model.VoteResult;
import dev.nklip.javacraft.elastic.api.model.VoteResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * Also verifies that karma deltas are forwarded to PostService on every meaningful state change:
 *
 * <pre>
 *   Created  + UPVOTE   → postService.updateScores(postId, +1, +1)
 *   Created  + DOWNVOTE → postService.updateScores(postId, −1,  0)
 *   NoOp     + any      → no karma update
 *   Updated  + UPVOTE   → postService.updateScores(postId, +2, +1)
 *   Updated  + DOWNVOTE → postService.updateScores(postId, −2, −1)
 *   Deleted  + was UPVOTE   → postService.updateScores(postId, −1, −1)
 *   Deleted  + was DOWNVOTE → postService.updateScores(postId, +1,  0)
 *   NotFound + any      → no karma update
 * </pre>
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
public class VoteServiceTest {

    @Mock
    ElasticsearchClient esClient;
    @Mock
    PostService postService;

    private VoteService service() {
        return new VoteService(esClient, postService);
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
        when(esClient.update(any(UpdateRequest.class), any(Class.class))).thenReturn(updateResponse);
    }

    private void stubGet(boolean found, String action) throws IOException {
        GetResponse<UserVote> getResponse = mock(GetResponse.class);
        when(getResponse.found()).thenReturn(found);
        if (found) {
            UserVote vote = mock(UserVote.class);
            when(vote.getAction()).thenReturn(action);
            when(getResponse.source()).thenReturn(vote);
        }
        when(esClient.get(any(GetRequest.class), any(Class.class))).thenReturn(getResponse);
    }
    
    public VoteRequest createVoteRequest() {
        VoteRequest voteRequest = new VoteRequest();
        voteRequest.setUserId("nl8888");
        voteRequest.setPostId("12345");
        voteRequest.setAction("Upvote");
        return voteRequest;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    public void testFirstVoteCreatesDocumentAndUpdatesKarma() throws IOException {
        VoteRequest voteRequest = createVoteRequest();   // action = UPVOTE
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubUpdate(expectedId, Result.Created);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:00:00Z");

        Assertions.assertEquals(expectedId, response.getDocumentId());
        Assertions.assertEquals(VoteResult.Created, response.getResult());
        verify(postService).updateScores(voteRequest.getPostId(), 1, 1);
    }

    @Test
    public void testFirstDownvoteCreatesDocumentAndDecrementsKarma() throws IOException {
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("DOWNVOTE");
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubUpdate(expectedId, Result.Created);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:00:00Z");

        Assertions.assertEquals(VoteResult.Created, response.getResult());
        verify(postService).updateScores(voteRequest.getPostId(), -1, 0);  // downvote: karma-1, upvotes unchanged
    }

    @Test
    public void testSameVoteRepeatedDoesNotChangeKarma() throws IOException {
        VoteRequest voteRequest = createVoteRequest();   // action = UPVOTE
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubUpdate(expectedId, Result.NoOp);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:05:00Z");

        Assertions.assertEquals(VoteResult.NoOp, response.getResult());
        verify(postService, never()).updateScores(anyString(), anyInt(), anyInt());
    }

    @Test
    public void testDownvoteAfterUpvoteFlipsKarmaByTwo() throws IOException {
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("Downvote");                         // switching UPVOTE → DOWNVOTE
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubUpdate(expectedId, Result.Updated);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:10:00Z");

        Assertions.assertEquals(VoteResult.Updated, response.getResult());
        verify(postService).updateScores(voteRequest.getPostId(), -2, -1);
    }

    @Test
    public void testUpvoteAfterDownvoteFlipsKarmaByTwo() throws IOException {
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("UPVOTE");                           // switching DOWNVOTE → UPVOTE
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubUpdate(expectedId, Result.Updated);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:10:00Z");

        Assertions.assertEquals(VoteResult.Updated, response.getResult());
        verify(postService).updateScores(voteRequest.getPostId(), 2, 1);
    }

    @Test
    public void testNovoteAfterUpvoteDecrementsKarma() throws IOException {
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("novote");
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubGet(true, "UPVOTE");
        stubDelete(expectedId, Result.Deleted);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:20:00Z");

        Assertions.assertEquals(VoteResult.Deleted, response.getResult());
        verify(postService).updateScores(voteRequest.getPostId(), -1, -1);
    }

    @Test
    public void testNovoteAfterDownvoteIncrementsKarma() throws IOException {
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("NOVOTE");
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        stubGet(true, "DOWNVOTE");
        stubDelete(expectedId, Result.Deleted);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:20:00Z");

        Assertions.assertEquals(VoteResult.Deleted, response.getResult());
        verify(postService).updateScores(voteRequest.getPostId(), 1, 0);  // removing downvote: karma+1, upvotes unchanged
    }

    @Test
    public void testNovoteOnNonExistentVoteDoesNotChangeKarma() throws IOException {
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("NOVOTE");
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        // Don't stub found() — deleteResult=NotFound short-circuits the &&, so found() is never called.
        GetResponse<UserVote> getResponse = mock(GetResponse.class);
        when(esClient.get(any(GetRequest.class), any(Class.class))).thenReturn(getResponse);
        stubDelete(expectedId, Result.NotFound);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:20:00Z");

        Assertions.assertEquals(VoteResult.NotFound, response.getResult());
        verify(postService, never()).updateScores(anyString(), anyInt(), anyInt());
    }

    @Test
    public void testNovoteWhenSourceNullDoesNotUpdateKarma() throws IOException {
        // Guard against NPE: found()=true but source()=null occurs when _source is disabled on the index.
        // Karma must not be updated and no NullPointerException must be thrown.
        VoteRequest voteRequest = createVoteRequest();
        voteRequest.setAction("NOVOTE");
        String expectedId = voteRequest.getUserId() + "_" + voteRequest.getPostId();
        GetResponse<UserVote> getResponse = mock(GetResponse.class);
        when(getResponse.found()).thenReturn(true);
        // source() not stubbed → returns null (Mockito default), simulating _source disabled
        when(esClient.get(any(GetRequest.class), any(Class.class))).thenReturn(getResponse);
        stubDelete(expectedId, Result.Deleted);

        VoteResponse response = service().processVoteRequest(voteRequest, "2024-01-15T10:20:00Z");

        Assertions.assertEquals(VoteResult.Deleted, response.getResult());
        verify(postService, never()).updateScores(anyString(), anyInt(), anyInt());
    }
}
