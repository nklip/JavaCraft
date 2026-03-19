package my.javacraft.elastic.model;

/**
 * Post document stored in the 'posts' index.
 * Document ID is {@code postId} — one document per post.
 *
 * <p>{@code createdAt} drives the 'New' feed sort order.
 * {@code karma}, {@code hotScore}, and {@code risingScore} are denormalized from the
 * 'user-votes' index and updated atomically on every vote, so all four ranking feeds
 * (New, Hot, Top, Rising) can be served with a single query against this index.
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
 * {@code RisingRankingService} filters candidates to posts younger than ~6 hours
 * and sorts by {@code risingScore DESC}.
 */
public record Post(
        String postId,
        // userId of the person who submitted this post.
        String author,
        // ISO-8601 UTC timestamp of when the post was created.
        String createdAt,
        // Denormalized net vote score: upvotes − downvotes. Updated on every vote.
        long karma,
        /*
         * Denormalized Reddit hot score. Updated on every vote.
         * Combines karma (log-compressed) with post age so that recency and quality
         * are both reflected — no separate query against 'user-votes' is needed at read time.
         */
        double hotScore,
        /*
         * Denormalized rising score: {@code karma / max(age_seconds, 1)}.
         * Measures vote velocity — how fast the post is accumulating karma relative
         * to how long it has existed. Updated on every vote using the vote timestamp
         * as {@code now}, so it captures velocity up to the most recent vote.
         * Zero at submission (karma = 0); negative if the post is net-downvoted.
         */
        double risingScore
) {}
