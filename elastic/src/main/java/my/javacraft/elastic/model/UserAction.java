package my.javacraft.elastic.model;

/**
 * Enum of valid user interaction types stored in the 'user-activity' index.
 * Used as the {@code action} keyword field — must stay uppercase to match ES keyword values.
 * Validated at the API layer via {@code @ValueOfEnum} on {@link UserClick#action}.
 */
public enum UserAction {
    UPVOTE,
    DOWNVOTE
}
