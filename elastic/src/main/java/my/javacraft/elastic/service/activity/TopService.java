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
import my.javacraft.elastic.model.UserActivity;
import org.springframework.stereotype.Service;

/*
 * TopService should simulate Reddit behavior for 'Top' posts.
 *
 * It should find all top posts by postId (upvotes − downvotes) by user activity over a certain period of time.
 *
 * For example:
 * Top day   → range(timestamp: last 24h)  + terms(recordId, _count DESC)
 * Top week  → range(timestamp: last 7d)   + terms(recordId, _count DESC)
 * Top month → range(timestamp: last 30d)  + terms(recordId, _count DESC)
 * Top year  → range(timestamp: last 365d) + terms(recordId, _count DESC)
 * Top all   → no range filter             + terms(recordId, _count DESC)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopService {

    private final ElasticsearchClient esClient;

//    public List<UserActivity> retrievePopularUserSearches(String userId, int size) throws IOException {
//        List<String> orderedRecordIds = queryPopularRecordIds(userId, size);
//        if (orderedRecordIds.isEmpty()) {
//            return List.of();
//        }
//        return hydrateActivities(userId, orderedRecordIds);
//    }
//
//    /**
//     * Query 1: terms aggregation to get recordIds ordered by event frequency (most-clicked first).
//     */
//    private List<String> queryPopularRecordIds(String userId, int size) throws IOException {
//        SearchRequest request = new SearchRequest.Builder()
//                .index(UserActivityService.INDEX_USER_ACTIVITY)
//                .query(q -> q.term(t -> t
//                        .field(UserActivityService.USER_ID)
//                        .value(v -> v.stringValue(userId))
//                ))
//                .size(0)
//                .aggregations(UserActivityService.RECORD_ID, a -> a
//                        .terms(t -> t
//                                .field(UserActivityService.RECORD_ID)
//                                .size(size)
//                                .order(NamedValue.of("_count", SortOrder.Desc))
//                        )
//                )
//                .build();
//
//        log.debug("popular aggregation query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));
//
//        return esClient.search(request, UserActivity.class)
//                .aggregations()
//                .get(UserActivityService.RECORD_ID)
//                .sterms()
//                .buckets()
//                .array()
//                .stream()
//                .map(StringTermsBucket::key)
//                .map(FieldValue::stringValue)
//                .toList();
//    }
//
//    /**
//     * Query 2: fetch one representative (most recent) UserActivity per recordId,
//     * preserving the popularity order from Query 1.
//     */
//    private List<UserActivity> hydrateActivities(String userId, List<String> orderedRecordIds) throws IOException {
//        SearchRequest request = new SearchRequest.Builder()
//                .index(UserActivityService.INDEX_USER_ACTIVITY)
//                .query(q -> q.bool(b -> b
//                        .must(m -> m.term(t -> t
//                                .field(UserActivityService.USER_ID)
//                                .value(v -> v.stringValue(userId))
//                        ))
//                        .must(m -> m.terms(t -> t
//                                .field(UserActivityService.RECORD_ID)
//                                .terms(tv -> tv.value(
//                                        orderedRecordIds.stream().map(FieldValue::of).toList()
//                                ))
//                        ))
//                ))
//                .size(orderedRecordIds.size())
//                .collapse(c -> c.field(UserActivityService.RECORD_ID))
//                .sort(so -> so.field(f -> f
//                        .field(UserActivityService.TIMESTAMP)
//                        .order(SortOrder.Desc)
//                ))
//                .build();
//
//        log.debug("popular hydration query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));
//
//        Map<String, UserActivity> byRecordId = esClient.search(request, UserActivity.class)
//                .hits()
//                .hits()
//                .stream()
//                .filter(hit -> hit.source() != null)
//                .collect(Collectors.toMap(
//                        hit -> hit.source().getRecordId(),
//                        Hit::source
//                ));
//
//        return orderedRecordIds.stream()
//                .map(byRecordId::get)
//                .filter(Objects::nonNull)
//                .toList();
//    }
}
