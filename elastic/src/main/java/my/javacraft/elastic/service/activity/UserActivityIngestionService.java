package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserActivity;
import org.springframework.stereotype.Service;

/**
 * Ingests each user click as a new immutable event document.
 * ES auto-generates the document ID; no upsert or scripting needed.
 * Index 'user-activity' must have the 'timestamp' field mapped as 'date'. See README.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityIngestionService {

    private final ElasticsearchClient esClient;

    public UserClickResponse ingestUserClick(UserClick userClick, String timestamp) throws IOException {
        UserActivity userActivity = new UserActivity(userClick, timestamp);

        IndexRequest<UserActivity> indexRequest = new IndexRequest.Builder<UserActivity>()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .document(userActivity)
                .build();

        log.debug("JSON representation of a query: {}", JsonpUtils.toJsonString(indexRequest, esClient._jsonpMapper()));

        IndexResponse indexResponse = esClient.index(indexRequest);

        UserClickResponse userClickResponse = new UserClickResponse();
        userClickResponse.setDocumentId(indexResponse.id());
        userClickResponse.setResult(indexResponse.result());
        return userClickResponse;
    }
}
