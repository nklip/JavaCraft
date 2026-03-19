package my.javacraft.elastic.model;

/**
 * Post document stored in the 'posts' index.
 * Document ID is {@code postId} — one document per post.
 *
 * <p>{@code createdAt} drives the 'New' feed sort order.
 * {@code karma}, {@code hotScore}, {@code risingScore}, and {@code bestScore} are
 * denormalized from the 'user-votes' index and updated atomically on every vote,
 * so all five ranking feeds (New, Hot, Top, Rising, Best) can be served with a
 * single query against this index.
 *
 * <p>Hot score formula (Reddit open-source):
 * <pre>
 *   order     = log₁₀(max(|karma|, 1)) × sign(karma)
 *   hotScore  = order + (createdAt_epoch − 1 134 028 003) / 45 000
 * </pre>
 *
 * <p>Rising score formula (velocity — votes per second since creation):
 * <pre>
 *   ageSeconds   = max(voteTime_epoch − createdAt_epoch, 1)
 *   risingScore  = karma / ageSeconds
 * </pre>
 * A higher {@code risingScore} means the post accumulated karma faster.
 *
 * <p>Best score formula (Wilson score lower bound, Reddit's "Best" sort):
 * <pre>
 *   n         = totalVotes = 2 * upvotes - karma
 *   p^        = upvotes / n
 *   z         = 1.96                   (95 % confidence)
 *   bestScore = (p^ + z^2/2n - z*sqrt(p^(1-p^)/n + z^2/4n^2)) / (1 + z^2/n)
 * </pre>
 * Penalises posts with few votes (high uncertainty); zero when no votes cast.
 */
public record Post(
        String postId,
        // userId of the person who submitted this post.
        String author,
        // ISO-8601 UTC timestamp of when the post was created.
        String createdAt,
        // Denormalized net vote score: upvotes - downvotes. Updated on every vote.
        long karma,
        // Raw upvote counter. Together with karma allows deriving totalVotes = 2*upvotes - karma.
        long upvotes,
        /*
         * Denormalized Reddit hot score. Updated on every vote.
         * Combines karma (log-compressed) with post age so that recency and quality
         * are both reflected — no separate query against 'user-votes' is needed at read time.
         */
        double hotScore,
        /*
         * Denormalized rising score: karma / max(age_seconds, 1).
         * Measures vote velocity — how fast the post is accumulating karma relative
         * to how long it has existed. Updated on every vote. Zero at submission;
         * negative if net-downvoted.
         */
        double risingScore,
        /*
         * Denormalized Wilson score lower bound (95 % confidence interval).
         * Rewards posts that are both highly upvoted AND have enough votes to be
         * statistically reliable. Zero when no votes have been cast.
         */
        double bestScore
) {}
