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
 * 🔥 Hot
 *
 * Time-decayed net score — the "front page" algorithm.
 *
 * Reddit's actual formula (open-sourced):
 *
 * score     = upvotes - downvotes
 * order     = log₁₀(max(|score|, 1)) × sign(score)
 * seconds   = submission_time - 1134028003   # epoch anchor (Dec 2005)
 * hot_score = order + seconds / 45000
 *
 * Key properties:
 *
 * 1) log₁₀ compresses the vote gap — going from 1→10 votes is as valuable as 10→100
 * 2) Time contribution grows linearly: every ~12.5 hours a post gains +1 to the score
 * 3) Early votes matter more than late votes (a post that reaches 100 votes in hour 1 beats one that reaches 1000
 * votes in hour 10)
 * 4) No hard cutoff — old posts with massive scores can still appear, just very slowly pushed down
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
