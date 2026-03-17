package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.util.NamedValue;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.UserAction;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.DateService;
import org.springframework.stereotype.Service;

/*
 * TopService simulates Reddit's 'Top' category.
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
 * Java: top_score = upvotes − downvotes → sort DESC → top-N → hydrate with collapse query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopService {

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
    public List<UserActivity> retrieveTopPosts(int size) throws IOException {
        return queryAndHydrate(size, null);
    }

    /** Top posts within the given time window (day / week / month / year). */
    public List<UserActivity> retrieveTopPosts(int size, TopWindow window) throws IOException {
        String since = dateService.getNDaysBeforeDate(window.getDays());
        return queryAndHydrate(size, since);
    }

    private List<UserActivity> queryAndHydrate(int size, String since) throws IOException {
        List<String> orderedPostIds = queryTopPostIds(size, since);
        if (orderedPostIds.isEmpty()) {
            return List.of();
        }
        return hydrateActivities(orderedPostIds);
    }

    /**
     * Aggregation query: terms(postId) → filter(UPVOTE) + filter(DOWNVOTE).
     * Net score computed in Java; posts re-sorted and limited to {@code size}.
     *
     * @param since ISO-8601 lower bound for the range filter, or {@code null} for "all time"
     */
    private List<String> queryTopPostIds(int size, String since) throws IOException {
        int querySize = Math.min(size * 10, UserActivityService.MAX_VALUES);

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .size(0)
                .aggregations(UserActivityService.POST_ID, a -> a
                        .terms(t -> t
                                .field(UserActivityService.POST_ID)
                                .size(querySize)
                                .order(NamedValue.of("_count", SortOrder.Desc))
                        )
                        .aggregations("upvotes", sub -> sub
                                .filter(f -> f.term(t -> t
                                        .field(UserActivityService.ACTION)
                                        .value(v -> v.stringValue(UserAction.UPVOTE.name()))
                                ))
                        )
                        .aggregations("downvotes", sub -> sub
                                .filter(f -> f.term(t -> t
                                        .field(UserActivityService.ACTION)
                                        .value(v -> v.stringValue(UserAction.DOWNVOTE.name()))
                                ))
                        )
                );

        if (since != null) {
            builder.query(q -> q.range(r -> r.date(d -> d
                    .field(UserActivityService.TIMESTAMP)
                    .gte(since)
            )));
        }

        SearchRequest request = builder.build();
        log.debug("top aggregation query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, UserActivity.class)
                .aggregations()
                .get(UserActivityService.POST_ID)
                .sterms()
                .buckets()
                .array()
                .stream()
                .collect(Collectors.toMap(
                        b -> b.key().stringValue(),
                        b -> b.aggregations().get("upvotes").filter().docCount()
                           - b.aggregations().get("downvotes").filter().docCount()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(size)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Hydration query: fetch one representative (most recent) UserActivity per postId,
     * preserving the top-score order from the aggregation.
     */
    private List<UserActivity> hydrateActivities(List<String> orderedPostIds) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.terms(t -> t
                        .field(UserActivityService.POST_ID)
                        .terms(tv -> tv.value(
                                orderedPostIds.stream().map(FieldValue::of).toList()
                        ))
                ))
                .size(orderedPostIds.size())
                .collapse(c -> c.field(UserActivityService.POST_ID))
                .sort(so -> so.field(f -> f
                        .field(UserActivityService.TIMESTAMP)
                        .order(SortOrder.Desc)
                ))
                .build();

        log.debug("top hydration query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        Map<String, UserActivity> byPostId = esClient.search(request, UserActivity.class)
                .hits()
                .hits()
                .stream()
                .filter(hit -> hit.source() != null)
                .collect(Collectors.toMap(
                        hit -> hit.source().getPostId(),
                        Hit::source
                ));

        return orderedPostIds.stream()
                .map(byPostId::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
