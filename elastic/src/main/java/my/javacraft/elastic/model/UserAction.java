package my.javacraft.elastic.model;

import my.javacraft.elastic.service.VoteService;

/**
 * Enum of valid user interaction types for the 'user-votes' index.
 * Used as the {@code action} keyword field — must stay uppercase to match ES keyword values.
 * Validated at the API layer via {@code @ValueOfEnum} on {@link VoteRequest#action}.
 *
 * <p>State-machine semantics enforced by
 * {@link VoteService}:
 * <ul>
 *   <li>{@code UPVOTE}  — cast or switch to an upvote; deduplication via Painless script.</li>
 *   <li>{@code DOWNVOTE} — cast or switch to a downvote; deduplication via Painless script.</li>
 *   <li>{@code NOVOTE}  — cancel any existing vote; issues a {@code DeleteRequest} for the
 *       {@code (userId, postId)} document. Returns {@code Deleted} if a vote existed,
 *       {@code NotFound} if there was nothing to remove. No document is ever stored.</li>
 * </ul>
 */
public enum UserAction {
    UPVOTE,
    DOWNVOTE,
    NOVOTE
}
