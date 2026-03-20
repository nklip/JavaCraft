package my.javacraft.elastic.service.ranking;

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
import my.javacraft.elastic.service.DateService;
import org.springframework.stereotype.Service;

/*
 * RisingRankingService simulates Reddit-like "Rising".
 *
 * ⬆️ Rising
 *
 * 1) Candidates: posts created in the last 6 hours.
 * 2) Ranking: net vote velocity (karma / ageSeconds), stored as risingScore.
 * 3) This favors momentum: 50 net votes in 30 minutes outranks 200 over 5 hours.
 * 4) Acts as an early signal before a post becomes hot.
 * 5) Posts naturally drop off once they age past the candidate window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RisingRankingService {

    static final int RISING_CANDIDATE_WINDOW_HOURS = 6;

    private final ElasticsearchClient esClient;
    private final DateService dateService;

    public List<Post> retrieveRisingPosts(int size) throws IOException {
        int querySize = Math.min(size * 10, Constants.MAX_VALUES);
        String since = dateService.getNHoursBeforeDate(RISING_CANDIDATE_WINDOW_HOURS);

        SearchRequest request = new SearchRequest.Builder()
                .index(Constants.INDEX_POSTS)
                .size(querySize)
                .query(q -> q.range(r -> r.date(d -> d
                        .field(Constants.CREATED_AT)
                        .gte(since)
                )))
                .sort(s -> s.field(f -> f.field(Constants.RISING_SCORE).order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field(Constants.POST_ID).order(SortOrder.Asc)))
                .build();

        log.debug("rising posts query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, Post.class)
                .hits().hits().stream()
                .map(hit -> Objects.requireNonNull(hit.source()))
                .limit(size)
                .toList();
    }
}
