package my.javacraft.elastic.config;

public final class Constants {
    // we keep 365 days or close to 12 months of data in the index
    public static final int YEAR = 365;
    public static final int MAX_VALUES = 10000; // Elasticsearch limit
    public static final String INDEX_USER_VOTE = "user-vote";
    public static final String TIMESTAMP = "timestamp";
    public static final String POST_ID = "postId";
    public static final String ACTION = "action";
}
