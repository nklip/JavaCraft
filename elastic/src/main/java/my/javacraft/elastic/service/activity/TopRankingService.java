package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.util.NamedValue;
import java.io.IOException;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.model.UserAction;
import my.javacraft.elastic.model.UserVote;
import my.javacraft.elastic.service.DateService;
import org.springframework.stereotype.Service;

/*
 * TopRankingService simulates Reddit's 'Top' category.
 *
 * 🏆 Top (day / week / month / year / all)
 *
 * Raw net score within a time window — no decay.
 *
 * top_score = upvotes - downvotes
 *            WHERE submission_time >= (now - window)
 * ORDER BY top_score DESC
 *
 * Text table:
 * ┌────────┬───────────────┐
 * │ Filter │ Window        │
 * ├────────┼───────────────┤
 * │ Day    │ last 24 hours │
 * │ Week   │ last 7 days   │
 * │ Month  │ last 30 days  │
 * │ Year   │ last 365 days │
 * │ All    │ no filter     │
 * └────────┴───────────────┘
 *
 * Key properties:
 *
 * 1) No time decay within the window — a post from 6 days ago competes equally with one from today (for "week")
 * 2) Best/Top are the same algorithm (Best is just an alias Reddit uses for "Top — All Time" on some views)
 * 3) Rewards sustained quality; a niche post with a dedicated community can still win "month" or "year"
 *
 * Implementation:
 *
 * ES query: terms(postId, size = N × 10, order = _count DESC)   ← candidate pool
 *             ├─ filter(action = UPVOTE)   → upvote count
 *             └─ filter(action = DOWNVOTE) → downvote count
 *
 *           Optional date range filter: timestamp >= (now − window.days)
 *
 * Java: karma = upvotes − downvotes → sort DESC → top-N → PostPreview(postId, karma).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopRankingService {

    /**
     * Time windows accepted by {@link #retrieveTopPosts(int, TopWindow)}.
     * Maps directly to the Reddit Top filter labels.
     */
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
    public List<PostPreview> retrieveTopPosts(int size) throws IOException {
        return queryTopPosts(size, null);
    }

    /** Top posts within the given time window (day / week / month / year). */
    public List<PostPreview> retrieveTopPosts(int size, TopWindow window) throws IOException {
        String since = dateService.getNDaysBeforeDate(window.getDays());
        return queryTopPosts(size, since);
    }

    /**
     * Aggregation query: terms(postId) → filter(UPVOTE) + filter(DOWNVOTE).
     * Net score (karma) computed in Java; posts sorted and limited to {@code size}.
     * Projects directly to PostPreview — no hydration query needed.
     *
     * @param since ISO-8601 lower bound for the range filter, or {@code null} for "all time"
     */
    private List<PostPreview> queryTopPosts(int size, String since) throws IOException {
        int querySize = Math.min(size * 10, Constants.MAX_VALUES);

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(Constants.INDEX_USER_VOTES)
                .size(0)
                .aggregations(Constants.POST_ID, a -> a
                        .terms(t -> t
                                .field(Constants.POST_ID)
                                .size(querySize)
                                .order(NamedValue.of("_count", SortOrder.Desc))
                        )
                        .aggregations("upvotes", sub -> sub
                                .filter(f -> f.term(t -> t
                                        .field(Constants.ACTION)
                                        .value(v -> v.stringValue(UserAction.UPVOTE.name()))
                                ))
                        )
                        .aggregations("downvotes", sub -> sub
                                .filter(f -> f.term(t -> t
                                        .field(Constants.ACTION)
                                        .value(v -> v.stringValue(UserAction.DOWNVOTE.name()))
                                ))
                        )
                );

        if (since != null) {
            builder.query(q -> q.range(r -> r.date(d -> d
                    .field(Constants.TIMESTAMP)
                    .gte(since)
            )));
        }

        SearchRequest request = builder.build();
        log.debug("top aggregation query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, UserVote.class)
                .aggregations()
                .get(Constants.POST_ID)
                .sterms()
                .buckets()
                .array()
                .stream()
                .map(b -> new PostPreview(
                        b.key().stringValue(),
                        b.aggregations().get("upvotes").filter().docCount()
                        - b.aggregations().get("downvotes").filter().docCount()
                ))
                .sorted()
                .limit(size)
                .toList();
    }
}
