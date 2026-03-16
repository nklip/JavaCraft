package my.javacraft.elastic.service.activity;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.util.NamedValue;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.UserAction;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.DateService;
import org.springframework.stereotype.Service;

/*
 * HotService simulates Reddit's 'Hot' category.
 *
 * Hot gives every interaction an exponentially decaying weight based on its age.
 * Upvotes contribute positively; downvotes contribute negatively.
 * There are no discrete time windows — the score decays continuously.
 *
 * Formula per post:
 *   hot_score = Σ (upvotes − downvotes) per hour bucket × e^(−λ × bucket_age_hours)
 *   where λ = ln(2) / HOT_HALF_LIFE_HOURS
 *
 * With HOT_HALF_LIFE_HOURS = 6:
 *   now       → weight 1.000
 *   6h ago    → weight 0.500
 *   12h ago   → weight 0.250
 *   24h ago   → weight 0.063
 *
 * Single aggregation query:
 *   range(last HOT_WINDOW_DAYS days)
 *     terms(postId, size = querySize, order = _count DESC)   ← candidate pool
 *       └─ date_histogram(timestamp, fixed_interval = 1h)
 *            ├─ filter(action = UPVOTE)   → upvote count
 *            └─ filter(action = DOWNVOTE) → downvote count
 *
 * Java: for each postId → Σ (up − down) × decay(age) → hot_score
 *       sort DESC → top-N → hydrate with collapse query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotService {

    static final int HOT_WINDOW_DAYS = 7;
    static final int HOT_HALF_LIFE_HOURS = 6;
    private static final double LAMBDA = Math.log(2) / HOT_HALF_LIFE_HOURS;

    private final ElasticsearchClient esClient;
    private final DateService dateService;

    public List<UserActivity> retrieveHotPosts(int size) throws IOException {
        return retrieveHotPosts(size, Instant.now().toEpochMilli());
    }

    /**
     * Package-private overload that accepts a fixed reference timestamp for deterministic testing.
     */
    List<UserActivity> retrieveHotPosts(int size, long nowMs) throws IOException {
        int querySize = Math.min(size * 10, UserActivityService.MAX_VALUES);

        Map<String, Double> hotScores = queryHotScores(querySize, nowMs);

        List<String> hotPostIds = hotScores.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(size)
                .map(Map.Entry::getKey)
                .toList();

        if (hotPostIds.isEmpty()) {
            return List.of();
        }
        return hydrateActivities(hotPostIds);
    }

    /**
     * Exponential decay weight for an event at a given age.
     * Package-private for direct unit testing.
     */
    double computeDecay(long ageMs) {
        double ageHours = (double) ageMs / 3_600_000.0;
        return Math.exp(-LAMBDA * ageHours);
    }

    /**
     * Single aggregation query: terms(postId) → date_histogram → upvote/downvote filters.
     * Returns a map of postId → hot score.
     */
    private Map<String, Double> queryHotScores(int querySize, long nowMs) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.range(r -> r.date(d -> d
                        .field(UserActivityService.TIMESTAMP)
                        .gte(dateService.getNDaysBeforeDate(HOT_WINDOW_DAYS))
                )))
                .size(0)
                .aggregations(UserActivityService.POST_ID, a -> a
                        .terms(t -> t
                                .field(UserActivityService.POST_ID)
                                .size(querySize)
                                .order(NamedValue.of("_count", SortOrder.Desc))
                        )
                        .aggregations("by_hour", inner -> inner
                                .dateHistogram(dh -> dh
                                        .field(UserActivityService.TIMESTAMP)
                                        .fixedInterval(fi -> fi.time("1h"))
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
                        )
                )
                .build();

        log.debug("hot score query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        return esClient.search(request, UserActivity.class)
                .aggregations()
                .get(UserActivityService.POST_ID)
                .sterms()
                .buckets()
                .array()
                .stream()
                .collect(Collectors.toMap(
                        b -> b.key().stringValue(),
                        b -> computeHotScore(b, nowMs)
                ));
    }

    private double computeHotScore(StringTermsBucket postBucket, long nowMs) {
        return postBucket.aggregations()
                .get("by_hour")
                .dateHistogram()
                .buckets()
                .array()
                .stream()
                .mapToDouble(hourBucket -> {
                    long ageMs = nowMs - hourBucket.key();
                    double decay = computeDecay(ageMs);
                    long upvotes = hourBucket.aggregations().get("upvotes").filter().docCount();
                    long downvotes = hourBucket.aggregations().get("downvotes").filter().docCount();
                    return (upvotes - downvotes) * decay;
                })
                .sum();
    }

    /**
     * Query: fetch one representative (most recent) UserActivity per hot postId.
     * Preserves the hot score order from the caller.
     */
    private List<UserActivity> hydrateActivities(List<String> hotPostIds) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
                .index(UserActivityService.INDEX_USER_ACTIVITY)
                .query(q -> q.terms(t -> t
                        .field(UserActivityService.POST_ID)
                        .terms(tv -> tv.value(
                                hotPostIds.stream().map(FieldValue::of).toList()
                        ))
                ))
                .size(hotPostIds.size())
                .collapse(c -> c.field(UserActivityService.POST_ID))
                .sort(so -> so.field(f -> f
                        .field(UserActivityService.TIMESTAMP)
                        .order(SortOrder.Desc)
                ))
                .build();

        log.debug("hot hydration query: {}", JsonpUtils.toJsonString(request, esClient._jsonpMapper()));

        Map<String, UserActivity> byPostId = esClient.search(request, UserActivity.class)
                .hits()
                .hits()
                .stream()
                .filter(hit -> hit.source() != null)
                .collect(Collectors.toMap(
                        hit -> hit.source().getPostId(),
                        Hit::source
                ));

        return hotPostIds.stream()
                .map(byPostId::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
