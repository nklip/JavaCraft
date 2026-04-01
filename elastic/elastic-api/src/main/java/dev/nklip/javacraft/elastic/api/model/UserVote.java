package dev.nklip.javacraft.elastic.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import dev.nklip.javacraft.elastic.api.config.ApiLimits;

/**
 * Vote document stored in the 'user-votes' index.
 * Document ID is {@code userId_postId} — one document per (user, post) pair.
 *
 * <p>The document represents the user's <em>current</em> vote on a post.
 * Vote-processing service logic enforces
 * the following state machine:
 * <ul>
 *   <li>First vote → document created ({@code VoteResult.Created})</li>
 *   <li>Same action repeated → no write ({@code VoteResult.NoOp})</li>
 *   <li>Opposite action → action and timestamp updated ({@code VoteResult.Updated})</li>
 *   <li>{@code NOVOTE} → document deleted, no {@code UserVote} is created
 *       ({@code VoteResult.Deleted} or {@code VoteResult.NotFound})</li>
 * </ul>
 *
 * <p>The {@code action} field is always stored in uppercase (e.g. {@code UPVOTE}, {@code DOWNVOTE})
 * to match the ES keyword values used by ranking queries.
 */
@Data
@ToString
@NoArgsConstructor
public class UserVote {

    @NotBlank
    @Size(max = ApiLimits.MAX_POST_ID_LENGTH)
    private String postId;
    @NotBlank
    @Size(max = ApiLimits.MAX_USER_ID_LENGTH)
    private String userId;
    @NotBlank
    @Size(max = ApiLimits.MAX_ENUM_INPUT_LENGTH)
    private String action;
    /**
     * ISO-8601 UTC timestamp of the click event.
     * Must be mapped as 'date' type in the ES index mapping.
     */
    @NotBlank
    @Size(max = ApiLimits.MAX_TIMESTAMP_LENGTH)
    @Pattern(
            regexp = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z$",
            message = "must be ISO-8601 UTC timestamp"
    )
    private String timestamp;

    public UserVote(VoteRequest voteRequest, String timestamp) {
        this.postId = voteRequest.getPostId();
        this.userId = voteRequest.getUserId();
        this.action = voteRequest.getAction().toUpperCase(Locale.ROOT);
        this.timestamp = timestamp;
    }
}
