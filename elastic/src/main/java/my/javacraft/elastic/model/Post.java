package my.javacraft.elastic.model;

/**
 * Post document stored in the 'posts' index.
 * Document ID is {@code postId} — one document per post.
 *
 * <p>{@code createdAt} drives the 'New' feed sort order.
 * {@code karma} is denormalized from the 'user-votes' index and updated on every vote,
 * so the 'New' feed can be served with a single ES query.
 */
public record Post(
        String postId,
        /** userId of the person who submitted this post. */
        String author,
        /** ISO-8601 UTC timestamp of when the post was created. */
        String createdAt,
        /** Denormalized net vote score: upvotes − downvotes. Updated on every vote. */
        long karma
) {}
