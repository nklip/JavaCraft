package dev.nklip.javacraft.elastic.api.config;

public final class ApiLimits {
    private ApiLimits() {
    }

    // ElasticSearch real limit is 10000, 1000 is for extraction in our cases
    public static final int MAX_ES_LIMIT = 1000;

    public static final int MAX_USER_ID_LENGTH = 16;
    public static final int MAX_POST_ID_LENGTH = 16;

    public static final int MAX_SEARCH_PATTERN_LENGTH = 128;
    public static final int MAX_ENUM_INPUT_LENGTH = 32;
    public static final int MAX_TIMESTAMP_LENGTH = 40;
}
