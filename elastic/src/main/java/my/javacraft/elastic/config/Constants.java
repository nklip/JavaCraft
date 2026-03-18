package my.javacraft.elastic.config;

public final class Constants {
    // we keep 365 days or close to 12 months of data in the index
    public static final int YEAR = 365;
    public static final int MAX_VALUES = 10000; // Elasticsearch limit
    public static final String INDEX_USER_VOTES = "user-votes";
    public static final String INDEX_POSTS = "posts";
    public static final String TIMESTAMP = "timestamp";
    public static final String CREATED_AT = "createdAt";
    public static final String POST_ID = "postId";
    public static final String ACTION = "action";
    public static final String KARMA = "karma";
    public static final String HOT_SCORE = "hotScore";
    public static final String AUTHOR = "author";
}
