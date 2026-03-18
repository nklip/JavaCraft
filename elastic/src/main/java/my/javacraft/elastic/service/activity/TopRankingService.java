package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.Post;
import my.javacraft.elastic.service.DateService;
import org.springframework.stereotype.Service;

/*
 * TopRankingService simulates Reddit's 'Top' category.
 *
 * 🏆 Top (day / week / month / year / all)
 *
 * Raw net score within a time window — no decay.
 *
 * top_score = karma
 * WHERE createdAt >= (now − window)
 * ORDER BY karma DESC
 *
 * karma is denormalized into the 'posts' index and updated atomically by
 * PostService.updateScores() on every vote. Read time is a single sort + range
 * query against 'posts' — no aggregation against 'user-votes' needed.
 *
 * Key properties:
 *
 * 1) No time decay within the window — a post from 6 days ago competes equally
 *    with one from today (for "week").
 * 2) Best/Top are the same algorithm — Best is just an alias Reddit uses for
 *    "Top — All Time" on some views.
 * 3) Rewards sustained quality: a niche post with a dedicated community can
 *    still win "month" or "year".
 *
 * ┌────────┬───────────────┐
 * │ Filter │ Window        │
 * ├────────┼───────────────┤
 * │ Day    │ last 24 hours │
 * │ Week   │ last 7 days   │
 * │ Month  │ last 30 days  │
 * │ Year   │ last 365 days │
 * │ All    │ no filter     │
 * └────────┴───────────────┘
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopRankingService {

    @Getter
    public enum TopWindow {
        DAY(1), WEEK(7), MONTH(30), YEAR(365);

        private final int days;

        TopWindow(int days) {
            this.days = days;
        }
    }

    private final ElasticsearchClient esClient;
    private final DateService dateService;

    /** Top posts across all time — no time filter. */
    public List<Post> retrieveTopPosts(int size) throws IOException {
        return queryTopPosts(size, null);
    }

    /** Top posts within the given time window (day / week / month / year). */
    public List<Post> retrieveTopPosts(int size, TopWindow window) throws IOException {
        String since = dateService.getNDaysBeforeDate(window.getDays());
        return queryTopPosts(size, since);
    }

    private List<Post> queryTopPosts(int size, String since) throws IOException {
        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(Constants.INDEX_POSTS)
                .size(Math.min(size, Constants.MAX_VALUES))
                .sort(s -> s.field(f -> f.field(Constants.KARMA).order(SortOrder.Desc)))
                .sort(s -> s.field(f -> f.field(Constants.POST_ID).order(SortOrder.Asc)));

        if (since != null) {
            builder.query(q -> q.range(r -> r.date(d -> d
                    .field(Constants.CREATED_AT)
                    .gte(since)
            )));
        }

        SearchRequest request = builder.build();
        log.debug("top posts query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, Post.class)
                .hits().hits().stream()
                .map(hit -> Objects.requireNonNull(hit.source()))
                .limit(size)
                .toList();
    }
}
