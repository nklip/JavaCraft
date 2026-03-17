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
 *
 * Implementation notes (approximations vs Reddit):
 *
 *   • No ES-side hard time window — matches Reddit (no hard cutoff in the formula).
 *   • Submission time is approximated as min(timestamp) — earliest recorded vote, because
 *     this service stores user interactions only, not post creation events.
 *   • Candidate pool: terms(postId, size = N × 10, order = _count DESC).
 *
 * ES query:
 *   terms(postId)
 *     ├─ filter(action = UPVOTE)   → upvote count
 *     ├─ filter(action = DOWNVOTE) → downvote count
 *     └─ min(timestamp)            → earliest activity ≈ submission time
 *
 * Java: hot_score = order + (minTimestampSec − EPOCH_ANCHOR_SECONDS) / TIME_SCALE
 *       sort DESC → top-N → hydrate with collapse query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotService {

    /** Unix-second epoch anchor used in Reddit's hot formula (Dec 8, 2005). */
    static final long EPOCH_ANCHOR_SECONDS = 1_134_028_003L;

    /** Score divisor: every 45 000 s ≈ 12.5 h of age adds +1 to hot_score. */
    static final double TIME_SCALE = 45_000.0;

    private final ElasticsearchClient esClient;

    public List<UserActivity> retrieveHotPosts(int size) throws IOException {
        int querySize = Math.min(size * 10, UserActivityService.MAX_VALUES);

        Map<String, Double> hotScores = queryHotScores(querySize);

        List<String> hotPostIds = hotScores.entrySet().stream()
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
     * Reddit hot_score = log₁₀(max(|score|,1)) × sign(score) + (firstSeenSec − EPOCH_ANCHOR) / TIME_SCALE.
     * Package-private for unit testing.
     */
    double computeHotScore(long upvotes, long downvotes, long firstSeenMs) {
        long score = upvotes - downvotes;
        double order = Math.log10(Math.max(Math.abs(score), 1)) * Math.signum(score);
        double seconds = (firstSeenMs / 1000.0) - EPOCH_ANCHOR_SECONDS;
        return order + seconds / TIME_SCALE;
    }

    /**
     * Single aggregation query: terms(postId) → upvote/downvote filters + min(timestamp).
     * Returns a map of postId → hot score.
     */
    private Map<String, Double> queryHotScores(int querySize) throws IOException {
        SearchRequest request = new SearchRequest.Builder()
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
                        .aggregations("first_seen", sub -> sub
                                .min(m -> m.field(UserActivityService.TIMESTAMP))
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
                        this::computeHotScoreFromBucket
                ));
    }

    private double computeHotScoreFromBucket(StringTermsBucket postBucket) {
        long upvotes   = postBucket.aggregations().get("upvotes").filter().docCount();
        long downvotes = postBucket.aggregations().get("downvotes").filter().docCount();
        long firstSeenMs = (long) postBucket.aggregations().get("first_seen").min().value();
        return computeHotScore(upvotes, downvotes, firstSeenMs);
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
