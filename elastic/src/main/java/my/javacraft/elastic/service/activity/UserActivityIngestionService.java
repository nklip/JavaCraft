package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserActivity;
import org.springframework.stereotype.Service;

/**
 * Ingests user votes into the 'user-activity' index.
 *
 * <p>One document per (userId, postId) pair; document ID = {@code userId_postId}.
 * A Painless script enforces the vote-state machine atomically inside ES:
 * <ul>
 *   <li>No existing document → upsert creates it → {@code Result.Created}</li>
 *   <li>Same action already active → no-op, nothing written → {@code Result.NoOp}</li>
 *   <li>Different action (vote change) → action + timestamp updated → {@code Result.Updated}</li>
 * </ul>
 *
 * <p>Because exactly one document exists per (userId, postId), TopService and HotService
 * aggregations count each user's current vote correctly without any collapse or de-duplication.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityIngestionService {

    /**
     * Painless script evaluated by ES on every update.
     * Sets {@code ctx.op = 'noop'} when the incoming action matches the stored one,
     * so ES skips the write and returns {@code Result.NoOp}.
     */
    private static final String VOTE_SCRIPT =
            "if (ctx._source.action == params.action) { ctx.op = 'noop'; } " +
            "else { ctx._source.action = params.action; ctx._source.timestamp = params.timestamp; }";

    private final ElasticsearchClient esClient;

    public UserClickResponse ingestUserClick(UserClick userClick, String timestamp) throws IOException {
        UserActivity userActivity = new UserActivity(userClick, timestamp);

        // Deterministic ID: one document per (userId, postId) → correct aggregations
        String documentId = userClick.getUserId() + "_" + userClick.getPostId();

        // In ES Java client 8.18, Script is flat: source + params are direct fields.
        Script script = Script.of(s -> s
                .source(VOTE_SCRIPT)
                .params(Map.of(
                        UserActivityService.ACTION,    JsonData.of(userActivity.getAction()),
                        UserActivityService.TIMESTAMP, JsonData.of(timestamp)
                ))
        );

        UpdateRequest<UserActivity, UserActivity> updateRequest =
                new UpdateRequest.Builder<UserActivity, UserActivity>()
                        .index(UserActivityService.INDEX_USER_ACTIVITY)
                        .id(documentId)
                        .script(script)
                        .upsert(userActivity)
                        .build();

        log.debug("JSON representation of update request: {}",
                JsonpUtils.toJsonString(updateRequest, esClient._jsonpMapper()));

        UpdateResponse<UserActivity> updateResponse = esClient.update(updateRequest, UserActivity.class);

        log.info("vote ingested (documentId='{}', result='{}')", updateResponse.id(), updateResponse.result());

        UserClickResponse userClickResponse = new UserClickResponse();
        userClickResponse.setDocumentId(updateResponse.id());
        userClickResponse.setResult(updateResponse.result());
        return userClickResponse;
    }
}
