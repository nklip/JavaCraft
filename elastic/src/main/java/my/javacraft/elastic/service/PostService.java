package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.Post;
import org.springframework.stereotype.Service;

/**
 * Manages post documents in the 'posts' index.
 * One document per post; document ID = {@code postId}.
 * Upsert semantics: safe to call multiple times for the same postId.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final ElasticsearchClient esClient;

    /**
     * Upserts a post document with {@code karma = 0}.
     * Subsequent votes will increment/decrement karma via {@link #updateKarma}.
     */
    public void createPost(String postId, String createdAt) throws IOException {
        Post post = new Post(postId, createdAt, 0L);

        UpdateRequest<Post, Post> request = new UpdateRequest.Builder<Post, Post>()
                .index(Constants.INDEX_POSTS)
                .id(postId)
                .doc(post)
                .upsert(post)
                .build();

        log.debug("create post request: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        var result = esClient.update(request, Post.class);
        log.info("post indexed (postId='{}', result='{}')", postId, result.result());
    }

    /**
     * Atomically adjusts the karma of an existing post document by {@code delta}.
     * Uses a Painless script so the increment is race-free under concurrent votes.
     *
     * <p>If the post document does not exist (orphaned vote, or posts index not yet seeded),
     * the update is silently skipped with a WARN log rather than propagating an exception.
     * Karma is best-effort: missing-post votes simply do not influence the score.
     *
     * @param postId post to update
     * @param delta  +1 / −1 for new vote; +2 / −2 for vote flip; never 0 (callers skip)
     */
    public void updateKarma(String postId, int delta) throws IOException {
        Script script = Script.of(s -> s
                .source("ctx._source." + Constants.KARMA + " += params.delta")
                .params(Map.of("delta", JsonData.of(delta)))
        );

        var request = new UpdateRequest.Builder<Post, Post>()
                .index(Constants.INDEX_POSTS)
                .id(postId)
                .script(script)
                .build();

        log.debug("updateKarma request: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        try {
            var result = esClient.update(request, Post.class);
            log.debug("karma updated (postId='{}', delta={}, result='{}')", postId, delta, result.result());
        } catch (ElasticsearchException ex) {
            if ("document_missing_exception".equals(ex.error().type())) {
                log.warn("karma update skipped — post '{}' not found in '{}' index (orphaned vote)",
                        postId, Constants.INDEX_POSTS);
                return;
            }
            throw ex;
        }
    }
}
