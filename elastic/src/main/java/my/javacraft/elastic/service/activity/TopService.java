package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.util.NamedValue;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.UserAction;
import my.javacraft.elastic.model.UserActivity;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopService {

    private final ElasticsearchClient esClient;

    public List<UserActivity> retrieveTopPosts(int size) throws IOException {
        List<String> orderedPostIds = queryTopPostIds(size);
        if (orderedPostIds.isEmpty()) {
            return List.of();
        }
        return hydrateActivities(orderedPostIds);
    }

    /**
     * Query 1: terms aggregation filtered to UPVOTE actions only (all users).
     * Returns postIds ordered by upvote frequency descending.
     */
    private List<String> queryTopPostIds(int size) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.term(t -> t
                        .field(UserActivityService.ACTION)
                        .value(v -> v.stringValue(UserAction.UPVOTE.name()))
                ))
                .size(0)
                .aggregations(UserActivityService.POST_ID, a -> a
                        .terms(t -> t
                                .field(UserActivityService.POST_ID)
                                .size(size)
                                .order(NamedValue.of("_count", SortOrder.Desc))
                        )
                )
                .build();

        log.debug("top aggregation query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, UserActivity.class)
                .aggregations()
                .get(UserActivityService.POST_ID)
                .sterms()
                .buckets()
                .array()
                .stream()
                .map(StringTermsBucket::key)
                .map(FieldValue::stringValue)
                .toList();
    }

    /**
     * Query 2: fetch one representative (most recent) UserActivity per postId,
     * preserving the top order from Query 1.
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
