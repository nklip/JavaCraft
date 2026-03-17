package my.javacraft.elastic.model;

/**
 * Lightweight projection returned by the ranking endpoints (Hot, Top).
 *
 * <p>Contains just enough information for a feed card. In a real application this
 * would be extended with author, thumbnail, short-text excerpt, etc. — but the
 * ranking layer only needs the post identifier and its net vote score.
 *
 * @param postId unique post identifier
 * @param karma  net vote score: upvotes − downvotes
 */
public record PostPreview(String postId, long karma) {

    /** Convenience getter for frameworks / templates that expect JavaBean-style access. */
    public String getPostId() {
        return postId;
    }

    /** Convenience getter for frameworks / templates that expect JavaBean-style access. */
    public long getKarma() {
        return karma;
    }
}
