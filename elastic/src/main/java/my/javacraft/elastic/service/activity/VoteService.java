package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.UserAction;
import my.javacraft.elastic.model.UserVote;
import my.javacraft.elastic.model.VoteRequest;
import my.javacraft.elastic.model.VoteResponse;
import org.springframework.stereotype.Service;

/*
 * Ingests user votes into the 'user-activity' index.
 *
 * One document per (userId, postId) pair; document ID = {@code userId_postId.
 *
 * UPVOTE / DOWNVOTE — A Painless script enforces the vote-state machine atomically:
 * 
 *   No existing document → upsert creates it → Result.Created
 *   Same action already active → no-op, nothing written → Result.NoOp
 *   Different action (vote change) → action + timestamp updated → Result.Updated
 *
 * NOVOTE — issues a DeleteRequest for the composite document ID:
 * 
 *   Document exists → deleted → Result.Deleted
 *   Document absent → nothing to remove → Result.NotFound
 * 
 * Because exactly one document exists per (userId, postId), TopService and HotService
 * aggregations count each user's current vote correctly without any collapse or de-duplication.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteService {

    /**
     * Painless script evaluated by ES on every UPVOTE / DOWNVOTE update.
     * Sets {@code ctx.op = 'noop'} when the incoming action matches the stored one,
     * so ES skips the write and returns {@code Result.NoOp}.
     */
    private static final String VOTE_SCRIPT =
            "if (ctx._source.action == params.action) { ctx.op = 'noop'; } " +
            "else { ctx._source.action = params.action; ctx._source.timestamp = params.timestamp; }";

    private final ElasticsearchClient esClient;

    public VoteResponse ingestUserEvent(VoteRequest voteRequest, String timestamp) throws IOException {
        // Deterministic ID: one document per (userId, postId) → correct aggregations
        String documentId = voteRequest.getUserId() + "_" + voteRequest.getPostId();

        if (UserAction.NOVOTE.name().equals(voteRequest.getAction().toUpperCase())) {
            return removeVote(documentId);
        }
        return castVote(voteRequest, timestamp, documentId);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** UPVOTE or DOWNVOTE: upsert with Painless deduplication script. */
    private VoteResponse castVote(VoteRequest voteRequest, String timestamp, String documentId)
            throws IOException {

        UserVote userVote = new UserVote(voteRequest, timestamp);

        Script script = Script.of(s -> s
                .source(VOTE_SCRIPT)
                .params(Map.of(
                        Constants.ACTION,    JsonData.of(userVote.getAction()),
                        Constants.TIMESTAMP, JsonData.of(timestamp)
                ))
        );

        UpdateRequest<UserVote, UserVote> updateRequest =
                new UpdateRequest.Builder<UserVote, UserVote>()
                        .index(Constants.INDEX_USER_VOTE)
                        .id(documentId)
                        .script(script)
                        .upsert(userVote)
                        .build();

        log.debug("JSON representation of update request: {}",
                JsonpUtils.toJsonString(updateRequest, esClient._jsonpMapper()));

        UpdateResponse<UserVote> updateResponse = esClient.update(updateRequest, UserVote.class);

        log.info("vote ingested (documentId='{}', result='{}')", updateResponse.id(), updateResponse.result());

        VoteResponse voteResponse = new VoteResponse();
        voteResponse.setDocumentId(updateResponse.id());
        voteResponse.setResult(updateResponse.result());
        return voteResponse;
    }

    /**
     * NOVOTE: delete the (userId, postId) document if it exists.
     * Returns {@code Deleted} when a vote was removed, {@code NotFound} when there was none.
     */
    private VoteResponse removeVote(String documentId) throws IOException {
        DeleteRequest deleteRequest = new DeleteRequest.Builder()
                .index(Constants.INDEX_USER_VOTE)
                .id(documentId)
                .build();

        DeleteResponse deleteResponse = esClient.delete(deleteRequest);

        log.info("vote removed (documentId='{}', result='{}')", deleteResponse.id(), deleteResponse.result());

        VoteResponse voteResponse = new VoteResponse();
        voteResponse.setDocumentId(deleteResponse.id());
        voteResponse.setResult(deleteResponse.result());
        return voteResponse;
    }
}
