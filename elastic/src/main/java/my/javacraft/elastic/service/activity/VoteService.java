package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
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
import my.javacraft.elastic.service.PostService;
import org.springframework.stereotype.Service;

/*
 * Ingests user votes into the 'user-votes' index and keeps the denormalized
 * karma counter in the 'posts' index in sync.
 *
 * One document per (userId, postId) pair; document ID = {@code userId_postId}.
 *
 * UPVOTE / DOWNVOTE — A Painless script enforces the vote-state machine atomically:
 *
 *   No existing document → upsert creates it → Result.Created
 *   Same action already active → no-op, nothing written → Result.NoOp
 *   Different action (vote change) → action + timestamp updated → Result.Updated
 *
 * NOVOTE — issues a GetRequest to retrieve the old action, then a DeleteRequest:
 *
 *   Document exists → old action read → deleted → Result.Deleted
 *   Document absent → nothing to remove → Result.NotFound
 *
 * Karma delta per result:
 * ┌──────────────┬────────────┬───────────┬───────┬─────────────────────┐
 * │ Prior state  │ New action │ ES Result │ Delta │ Math                │
 * ├──────────────┼────────────┼───────────┼───────┼─────────────────────┤
 * │ No document  │ UPVOTE     │ Created   │ +1    │ 0 -> +1             │
 * │ No document  │ DOWNVOTE   │ Created   │ -1    │ 0 -> -1             │
 * │ UPVOTE       │ UPVOTE     │ NoOp      │ 0     │ no write            │
 * │ DOWNVOTE     │ DOWNVOTE   │ NoOp      │ 0     │ no write            │
 * │ DOWNVOTE     │ UPVOTE     │ Updated   │ +2    │ -1 -> +1 = net +2   │
 * │ UPVOTE       │ DOWNVOTE   │ Updated   │ -2    │ +1 -> -1 = net -2   │
 * │ was UPVOTE   │ NOVOTE     │ Deleted   │ -1    │ +1 -> 0 = net -1    │
 * │ was DOWNVOTE │ NOVOTE     │ Deleted   │ +1    │ -1 -> 0 = net +1    │
 * │ No document  │ NOVOTE     │ NotFound  │ 0     │ nothing to undo     │
 * └──────────────┴────────────┴───────────┴───────┴─────────────────────┘
 *
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
    private final PostService postService;

    public VoteResponse processVoteRequest(VoteRequest voteRequest, String timestamp) throws IOException {
        String documentId = voteRequest.getUserId() + "_" + voteRequest.getPostId();

        if (UserAction.NOVOTE.name().equals(voteRequest.getAction().toUpperCase())) {
            return removeVote(voteRequest.getPostId(), documentId);
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
                        .index(Constants.INDEX_USER_VOTES)
                        .id(documentId)
                        .script(script)
                        .upsert(userVote)
                        .build();

        log.debug("JSON representation of update request: {}",
                JsonpUtils.toJsonString(updateRequest, esClient._jsonpMapper()));

        UpdateResponse<UserVote> updateResponse = esClient.update(updateRequest, UserVote.class);

        log.info("vote ingested (documentId='{}', result='{}')", updateResponse.id(), updateResponse.result());

        int delta = switch (updateResponse.result()) {
            case Created -> UserAction.UPVOTE.name().equals(userVote.getAction()) ?  1 : -1;
            case Updated -> UserAction.UPVOTE.name().equals(userVote.getAction()) ?  2 : -2;
            default      -> 0;  // NoOp: same vote repeated, karma unchanged
        };
        if (delta != 0) {
            postService.updateScores(voteRequest.getPostId(), delta);
        }

        VoteResponse voteResponse = new VoteResponse();
        voteResponse.setDocumentId(updateResponse.id());
        voteResponse.setResult(updateResponse.result());
        return voteResponse;
    }

    /**
     * NOVOTE: reads the existing vote to determine the karma delta, then deletes it.
     * Returns {@code Deleted} when a vote was removed, {@code NotFound} when there was none.
     */
    private VoteResponse removeVote(String postId, String documentId) throws IOException {
        GetResponse<UserVote> existing = esClient.get(
                GetRequest.of(g -> g.index(Constants.INDEX_USER_VOTES).id(documentId)),
                UserVote.class
        );

        DeleteRequest deleteRequest = new DeleteRequest.Builder()
                .index(Constants.INDEX_USER_VOTES)
                .id(documentId)
                .build();

        DeleteResponse deleteResponse = esClient.delete(deleteRequest);

        log.info("vote removed (documentId='{}', result='{}')", deleteResponse.id(), deleteResponse.result());

        if (deleteResponse.result() == Result.Deleted && existing.found()) {
            UserVote oldVote = existing.source();   // @Nullable — null when _source is disabled
            if (oldVote != null) {
                int delta = UserAction.UPVOTE.name().equals(oldVote.getAction()) ? -1 : 1;
                postService.updateScores(postId, delta);
            }
        }

        VoteResponse voteResponse = new VoteResponse();
        voteResponse.setDocumentId(deleteResponse.id());
        voteResponse.setResult(deleteResponse.result());
        return voteResponse;
    }
}
