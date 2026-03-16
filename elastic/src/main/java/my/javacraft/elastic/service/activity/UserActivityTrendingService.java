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
 * Trending means something is growing rapidly right now.
 *
 * Algorithm: two-window comparison.
 *   - recent window  : last RECENT_WINDOW_HOURS hours
 *   - baseline window: the BASELINE_WINDOW_HOURS hours before that
 *
 * Trend score = recent_count / (baseline_avg_per_hour + 1)
 *
 * A score >> 1 means a spike vs the baseline.
 * Adding 1 to the denominator (Laplace smoothing) ensures new items
 * with zero baseline still rank by their absolute recent count.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityTrendingService {

    static final int RECENT_WINDOW_HOURS = 1;
    static final int BASELINE_WINDOW_HOURS = 23;

    private final ElasticsearchClient esClient;
    private final DateService dateService;

    public List<UserActivity> retrieveTrendingUserSearches(int size) throws IOException {
        int querySize = Math.min(size * 10, UserActivityService.MAX_VALUES);

        String now = dateService.getCurrentDate();
        String recentCutoff = dateService.getNHoursBeforeDate(RECENT_WINDOW_HOURS);
        String baselineCutoff = dateService.getNHoursBeforeDate(RECENT_WINDOW_HOURS + BASELINE_WINDOW_HOURS);

        Map<String, Long> recentCounts = queryCountsByRecordId(recentCutoff, now, querySize);
        Map<String, Long> baselineCounts = queryCountsByRecordId(baselineCutoff, recentCutoff, querySize);

        List<String> trendingRecordIds = recentCounts.entrySet().stream()
                .map(e -> {
                    double score = computeTrendScore(e.getValue(), baselineCounts.getOrDefault(e.getKey(), 0L));
                    return Map.entry(score, e.getKey());
                })
                .sorted(Map.Entry.<Double, String>comparingByKey().reversed())
                .limit(size)
                .map(Map.Entry::getValue)
                .toList();

        if (trendingRecordIds.isEmpty()) {
            return List.of();
        }
        return hydrateActivities(trendingRecordIds);
    }

    /**
     * score = recentCount / (baselineAvgPerHour + 1)
     * High score = spike vs baseline. Items with zero baseline still rank by recentCount.
     */
    double computeTrendScore(long recentCount, long baselineCount) {
        double baselineAvgPerHour = (double) baselineCount / BASELINE_WINDOW_HOURS;
        return recentCount / (baselineAvgPerHour + 1.0);
    }

    /**
     * Query: terms aggregation on recordId within a time range.
     * Returns docCount per recordId.
     */
    private Map<String, Long> queryCountsByRecordId(String from, String to, int querySize) throws IOException {
        Query rangeQuery = RangeQuery.of(r -> r.date(d -> d
                .field(UserActivityService.TIMESTAMP)
                .gte(from)
                .lte(to)
        ))._toQuery();

        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(rangeQuery)
                .size(0)
                .aggregations(UserActivityService.RECORD_ID, a -> a
                        .terms(t -> t
                                .field(UserActivityService.RECORD_ID)
                                .size(querySize)
                        )
                )
                .build();

        log.debug("trending window query [{} → {}]: {}", from, to, JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, UserActivity.class)
                .aggregations()
                .get(UserActivityService.RECORD_ID)
                .sterms()
                .buckets()
                .array()
                .stream()
                .collect(Collectors.toMap(
                        b -> b.key().stringValue(),
                        StringTermsBucket::docCount
                ));
    }

    /**
     * Query: fetch one representative (most recent) UserActivity per trending recordId.
     * Preserves the trending score order from the caller.
     */
    private List<UserActivity> hydrateActivities(List<String> trendingRecordIds) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.terms(t -> t
                        .field(UserActivityService.RECORD_ID)
                        .terms(tv -> tv.value(
                                trendingRecordIds.stream().map(FieldValue::of).toList()
                        ))
                ))
                .size(trendingRecordIds.size())
                .collapse(c -> c.field(UserActivityService.RECORD_ID))
                .sort(so -> so.field(f -> f
                        .field(UserActivityService.TIMESTAMP)
                        .order(SortOrder.Desc)
                ))
                .build();

        log.debug("trending hydration query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        Map<String, UserActivity> byRecordId = esClient.search(request, UserActivity.class)
                .hits()
                .hits()
                .stream()
                .filter(hit -> hit.source() != null)
                .collect(Collectors.toMap(
                        hit -> hit.source().getRecordId(),
                        Hit::source
                ));

        return trendingRecordIds.stream()
                .map(byRecordId::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
