package my.javacraft.elastic.app.config;

public final class ElasticsearchConstants {
    private ElasticsearchConstants() {
    }

    public static final String INDEX_USER_VOTES = "user-votes";
    public static final String INDEX_POSTS = "posts";

    public static final String TIMESTAMP = "timestamp";
    public static final String CREATED_AT = "createdAt";
    public static final String POST_ID = "postId";
    public static final String ACTION = "action";

    public static final String KARMA = "karma";
    public static final String HOT_SCORE = "hotScore";
    public static final String RISING_SCORE = "risingScore";
    public static final String BEST_SCORE = "bestScore";
}
