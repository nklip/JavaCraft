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
 * BestRankingService simulates Reddit's 'Best' comment/post sort.
 *
 * ⭐ Best
 *
 * Bayesian confidence interval on upvote ratio.
 *
 * Reddit uses the Wilson score lower bound:
 *
 *   n         = totalVotes = 2 * upvotes - karma
 *   p^        = upvotes / n
 *   z         = 1.96  (95% confidence)
 *
 *   bestScore = (p^ + z^2/2n - z*sqrt(p^(1-p^)/n + z^2/4n^2)) / (1 + z^2/n)
 *
 * Key properties:
 *
 * 1) Penalises posts with few votes (high uncertainty in the true upvote ratio).
 * 2) A post with 10 upvotes / 0 downvotes scores lower than one with 1000/50:
 *    the confidence interval is wider when n is small.
 * 3) Rewards posts that are both reliably liked AND have a large enough sample
 *    to be statistically trustworthy.
 * 4) Used as the default comment sort ("Best" in the UI)
 *
 * Implementation details:
 * 1) bestScore is denormalized into the 'posts' index and updated atomically by
 *    PostService.updateScores() on every vote (Painless script). Read time is a
 *    single sort query — no aggregation against 'user-votes' needed.
 * 2) ES query: sort bestScore DESC, postId ASC (deterministic tie-break)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BestRankingService {

    private final ElasticsearchClient esClient;

    public List<Post> retrieveBestPosts(int size) throws IOException {
        int querySize = Math.min(size * 10, Constants.MAX_VALUES);

        SearchRequest request = new SearchRequest.Builder()
                .index(Constants.INDEX_POSTS)
                .size(querySize)
                .sort(s -> s.field(f -> f.field(Constants.BEST_SCORE).order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field(Constants.POST_ID).order(SortOrder.Asc)))
                .build();

        log.debug("best posts query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, Post.class)
                .hits().hits().stream()
                .map(hit -> Objects.requireNonNull(hit.source()))
                .limit(size)
                .toList();
    }
}
