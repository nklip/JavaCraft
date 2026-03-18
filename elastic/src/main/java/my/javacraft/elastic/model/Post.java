package my.javacraft.elastic.model;

/**
 * Post document stored in the 'posts' index.
 * Document ID is {@code postId} — one document per post.
 *
 * <p>{@code createdAt} drives the 'New' feed sort order.
 * {@code karma} and {@code hotScore} are denormalized from the 'user-votes' index
 * and updated atomically on every vote, so all three ranking feeds (New, Hot, Top)
 * can be served with a single query against this index.
 *
 * <p>Hot score formula (Reddit open-source):
 * <pre>
 *   order     = log₁₀(max(|karma|, 1)) × sign(karma)
 *   hotScore  = order + (createdAt_epoch − 1 134 028 003) / 45 000
 * </pre>
 */
public record Post(
        String postId,
        /** userId of the person who submitted this post. */
        String author,
        /** ISO-8601 UTC timestamp of when the post was created. */
        String createdAt,
        /** Denormalized net vote score: upvotes − downvotes. Updated on every vote. */
        long karma,
        /**
         * Denormalized Reddit hot score. Updated on every vote.
         * Combines karma (log-compressed) with post age so that recency and quality
         * are both reflected — no separate query against 'user-votes' is needed at read time.
         */
        double hotScore
) {}
