package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.Post;
import org.springframework.stereotype.Service;

/*
 * NewRankingService simulates Reddit's 'New' category.
 *
 * 🆕 New
 *
 * Purely chronological — no ranking.
 *
 * Key properties:
 *
 * 1) Sort key: post.createdAt DESC (actual submission time from 'posts' index), postId ASC (deterministic tie-break)
 * 2) Every post appears regardless of vote count
 * 3) No decay, no score influence.
 * 4) Use case: see the firehose; catch posts before they get buried.
 *
 * ES query: single sort against 'posts' index.
 * karma is embedded in the document — no second query needed.
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewRankingService {

    private final ElasticsearchClient esClient;

    public List<Post> retrieveNewPosts(int size) throws IOException {
        int querySize = Math.min(size * 10, Constants.MAX_VALUES);

        SearchRequest request = new SearchRequest.Builder()
                .index(Constants.INDEX_POSTS)
                .size(querySize)
                .sort(s -> s.field(f -> f.field(Constants.CREATED_AT).order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field(Constants.POST_ID).order(SortOrder.Asc)))
                .build();

        log.debug("new posts query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, Post.class)
                .hits().hits().stream()
                .map(hit -> Objects.requireNonNull(hit.source()))
                .limit(size)
                .toList();
    }
}
