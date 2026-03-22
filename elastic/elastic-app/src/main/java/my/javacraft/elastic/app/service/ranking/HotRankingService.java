package my.javacraft.elastic.app.service.ranking;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.api.config.ApiLimits;
import my.javacraft.elastic.app.config.ElasticsearchConstants;
import my.javacraft.elastic.api.model.Post;
import org.springframework.stereotype.Service;

/*
 * HotRankingService simulates Reddit's 'Hot' category.
 *
 * 🔥 Hot
 *
 * Time-decayed net score — the "front page" algorithm.
 *
 * Reddit's actual formula (open-sourced):
 *
 * score     = upvotes − downvotes
 * order     = log₁₀(max(|score|, 1)) × sign(score)
 * seconds   = submission_time − 1134028003   # epoch anchor (Dec 2005)
 * hot_score = order + seconds / 45000
 *
 * hotScore is denormalized into the 'posts' index and updated atomically by
 * PostService.updateScores() on every vote (Painless script). Read time is a
 * single sort query — no aggregation against 'user-votes' needed.
 *
 * Key properties:
 *
 * 1) log₁₀ compresses the vote gap — going from 1→10 votes is as valuable
 *    as going from 10→100.
 * 2) Time contribution grows linearly: every ~12.5 hours a post gains +1
 *    to the score.
 * 3) Early votes matter more than late votes: a post that reaches 100 votes
 *    in hour 1 beats one that reaches 1000 votes in hour 10.
 * 4) No hard cutoff — old posts with massive scores can still appear, just
 *    very slowly pushed down.
 *
 * ES query: sort hotScore DESC, postId ASC (deterministic tie-break)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotRankingService {

    private final ElasticsearchClient esClient;

    public List<Post> retrieveHotPosts(int size) throws IOException {
        int querySize = Math.min(size * 10, ApiLimits.MAX_ES_LIMIT);

        SearchRequest request = new SearchRequest.Builder()
                .index(ElasticsearchConstants.INDEX_POSTS)
                .size(querySize)
                .sort(s -> s.field(f -> f.field(ElasticsearchConstants.HOT_SCORE).order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field(ElasticsearchConstants.POST_ID).order(SortOrder.Asc)))
                .build();

        log.debug("hot posts query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, Post.class)
                .hits().hits().stream()
                .map(hit -> Objects.requireNonNull(hit.source()))
                .limit(size)
                .toList();
    }
}
