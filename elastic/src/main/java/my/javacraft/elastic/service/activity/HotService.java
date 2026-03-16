package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.DateService;
import org.springframework.stereotype.Service;

/*
 * HotService should simulate Reddit behavior for 'Hot' posts.
 *
 * Hot gives every click an exponentially decaying weight based on its age.
 * There are no window boundaries — the score continuously decreases as time passes.
 * An item that gets 100 clicks per hour, every hour, stays Hot indefinitely.
 * An item with a spike last week has almost fully decayed.
 *
 * The decay formula:
 * hot_score(postId) = Σ click_count(bucket) × e^(−λ × bucket_age_hours)
 * where λ = ln(2) / half_life_hours
 *
 * With half_life = 6h:
 * |-----------|--------|
 * | Age       | Weight |
 * |-----------|--------|
 * | now       | 1.000  |
 * | 6h ago    | 0.500  |
 * | 12h ago   | 0.250  |
 * | 24h ago   | 0.063  |
 * | 48h ago   | 0.004  |
 * | 7 days ago| ~0     |
 * |-----------|--------|
 *
 * Clicks from yesterday are essentially invisible. A burst this hour dominates.
 *
 * Hot needs the full time distribution of each postId to apply per-bucket weights.
 * A date_histogram gives exactly that in one round trip:
 *
 * range(last 7 days)
 *   terms(postId, size=N×10, order=_count DESC)   ← candidate pool
 *     └─ date_histogram(timestamp, fixed_interval=1h) ← per-record hourly distribution
 *
 * Java:
 *   for each postId bucket:
 *     hot_score = Σ (hourly_count × e^(−λ × bucket_age_hours))
 *   sort by hot_score DESC → top-N
 *   hydrate with collapse query
 *
 * Why outer terms → inner date_histogram (not the reverse)
 * You could also do date_histogram outer → terms inner (as we currently have in the Trending approach).
 *
 * The problem:
 * Outer date_histogram + inner terms(size=N) → you might miss a postId's contribution in a given hour if it falls outside the inner top-N
 * Outer terms + inner date_histogram → you get the complete hourly distribution for each candidate postId, no gaps
 *
 * The outer terms uses order=_count DESC within the last 7 days to pick the candidate pool (size = querySize × 10).
 * Hot score re-ranks them in Java.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotService {

//    static final int RECENT_WINDOW_HOURS = 1;
//    static final int BASELINE_WINDOW_HOURS = 23;

    private final ElasticsearchClient esClient;
    private final DateService dateService;
//
//    public List<UserActivity> retrieveTrendingUserSearches(int size) throws IOException {
//        int querySize = Math.min(size * 10, UserActivityService.MAX_VALUES);
//
//        String now = dateService.getCurrentDate();
//        String recentCutoff = dateService.getNHoursBeforeDate(RECENT_WINDOW_HOURS);
//        String baselineCutoff = dateService.getNHoursBeforeDate(RECENT_WINDOW_HOURS + BASELINE_WINDOW_HOURS);
//
//        Map<String, Long> recentCounts = queryCountsByPostId(recentCutoff, now, querySize);
//        Map<String, Long> baselineCounts = queryCountsByPostId(baselineCutoff, recentCutoff, querySize);
//
//        List<String> trendingPostIds = recentCounts.entrySet().stream()
//                .map(e -> {
//                    double score = computeTrendScore(e.getValue(), baselineCounts.getOrDefault(e.getKey(), 0L));
//                    return Map.entry(score, e.getKey());
//                })
//                .sorted(Map.Entry.<Double, String>comparingByKey().reversed())
//                .limit(size)
//                .map(Map.Entry::getValue)
//                .toList();
//
//        if (trendingPostIds.isEmpty()) {
//            return List.of();
//        }
//        return hydrateActivities(trendingPostIds);
//    }
//
//    /**
//     * score = recentCount / (baselineAvgPerHour + 1)
//     * High score = spike vs baseline. Items with zero baseline still rank by recentCount.
//     */
//    double computeTrendScore(long recentCount, long baselineCount) {
//        double baselineAvgPerHour = (double) baselineCount / BASELINE_WINDOW_HOURS;
//        return recentCount / (baselineAvgPerHour + 1.0);
//    }
//
//    /**
//     * Query: terms aggregation on postId within a time range.
//     * Returns docCount per postId.
//     */
//    private Map<String, Long> queryCountsByPostId(String from, String to, int querySize) throws IOException {
//        Query rangeQuery = RangeQuery.of(r -> r.date(d -> d
//                .field(UserActivityService.TIMESTAMP)
//                .gte(from)
//                .lte(to)
//        ))._toQuery();
//
//        SearchRequest request = new SearchRequest.Builder()
//                .index(UserActivityService.INDEX_USER_ACTIVITY)
//                .query(rangeQuery)
//                .size(0)
//                .aggregations(UserActivityService.POST_ID, a -> a
//                        .terms(t -> t
//                                .field(UserActivityService.POST_ID)
//                                .size(querySize)
//                        )
//                )
//                .build();
//
//        log.debug("trending window query [{} → {}]: {}", from, to, JsonpUtils.toJsonString(request, esClient._jsonpMapper()));
//
//        return esClient.search(request, UserActivity.class)
//                .aggregations()
//                .get(UserActivityService.POST_ID)
//                .sterms()
//                .buckets()
//                .array()
//                .stream()
//                .collect(Collectors.toMap(
//                        b -> b.key().stringValue(),
//                        StringTermsBucket::docCount
//                ));
//    }
//
//    /**
//     * Query: fetch one representative (most recent) UserActivity per trending postId.
//     * Preserves the trending score order from the caller.
//     */
//    private List<UserActivity> hydrateActivities(List<String> trendingPostIds) throws IOException {
//        SearchRequest request = new SearchRequest.Builder()
//                .index(UserActivityService.INDEX_USER_ACTIVITY)
//                .query(q -> q.terms(t -> t
//                        .field(UserActivityService.POST_ID)
//                        .terms(tv -> tv.value(
//                                trendingPostIds.stream().map(FieldValue::of).toList()
//                        ))
//                ))
//                .size(trendingPostIds.size())
//                .collapse(c -> c.field(UserActivityService.POST_ID))
//                .sort(so -> so.field(f -> f
//                        .field(UserActivityService.TIMESTAMP)
//                        .order(SortOrder.Desc)
//                ))
//                .build();
//
//        log.debug("trending hydration query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));
//
//        Map<String, UserActivity> byPostId = esClient.search(request, UserActivity.class)
//                .hits()
//                .hits()
//                .stream()
//                .filter(hit -> hit.source() != null)
//                .collect(Collectors.toMap(
//                        hit -> hit.source().getPostId(),
//                        Hit::source
//                ));
//
//        return trendingPostIds.stream()
//                .map(byPostId::get)
//                .filter(Objects::nonNull)
//                .toList();
//    }
}
