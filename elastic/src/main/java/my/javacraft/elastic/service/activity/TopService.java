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
 * TopService simulates Reddit's 'Top' category for a given user.
 *
 * Returns the posts a user has upvoted most frequently, ordered by upvote count descending.
 * Only UPVOTE events contribute — downvotes are negative feedback and are excluded.
 *
 * Two-query approach:
 *   Query 1 — terms aggregation: filter by userId + action=UPVOTE, group by postId, order by _count DESC
 *   Query 2 — collapse hydration: fetch one representative UserActivity doc per postId
 *
 * Time-window variants (Top day / week / month / year / all) can be added by passing
 * an optional range filter on the TIMESTAMP field to queryTopPostIds().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopService {

    private final ElasticsearchClient esClient;

    public List<UserActivity> retrievePopularUserSearches(String userId, int size) throws IOException {
        List<String> orderedPostIds = queryTopPostIds(userId, size);
        if (orderedPostIds.isEmpty()) {
            return List.of();
        }
        return hydrateActivities(userId, orderedPostIds);
    }

    /**
     * Query 1: terms aggregation filtered to UPVOTE actions only.
     * Returns postIds ordered by upvote frequency descending.
     */
    private List<String> queryTopPostIds(String userId, int size) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t
                                .field(UserActivityService.USER_ID)
                                .value(v -> v.stringValue(userId))
                        ))
                        .must(m -> m.term(t -> t
                                .field(UserActivityService.ACTION)
                                .value(v -> v.stringValue(UserAction.UPVOTE.name()))
                        ))
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
    private List<UserActivity> hydrateActivities(String userId, List<String> orderedPostIds) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t
                                .field(UserActivityService.USER_ID)
                                .value(v -> v.stringValue(userId))
                        ))
                        .must(m -> m.terms(t -> t
                                .field(UserActivityService.POST_ID)
                                .terms(tv -> tv.value(
                                        orderedPostIds.stream().map(FieldValue::of).toList()
                                ))
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
