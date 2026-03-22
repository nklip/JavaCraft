package my.javacraft.elastic.api.model;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Vote document stored in the 'user-votes' index.
 * Document ID is {@code userId_postId} — one document per (user, post) pair.
 *
 * <p>The document represents the user's <em>current</em> vote on a post.
 * Vote-processing service logic enforces
 * the following state machine:
 * <ul>
 *   <li>First vote → document created ({@code Result.Created})</li>
 *   <li>Same action repeated → no write ({@code Result.NoOp})</li>
 *   <li>Opposite action → action and timestamp updated ({@code Result.Updated})</li>
 *   <li>{@code NOVOTE} → document deleted, no {@code UserVote} is created
 *       ({@code Result.Deleted} or {@code Result.NotFound})</li>
 * </ul>
 *
 * <p>The {@code action} field is always stored in uppercase (e.g. {@code UPVOTE}, {@code DOWNVOTE})
 * to match the ES keyword values used by ranking queries.
 */
@Data
@ToString
@NoArgsConstructor
public class UserVote {

    @NotEmpty
    String postId;
    @NotEmpty
    String userId;
    @NotEmpty
    String action;
    /**
     * ISO-8601 UTC timestamp of the click event.
     * Must be mapped as 'date' type in the ES index mapping.
     */
    @NotEmpty
    String timestamp;

    public UserVote(VoteRequest voteRequest, String timestamp) {
        this.postId = voteRequest.getPostId();
        this.userId = voteRequest.getUserId();
        this.action = voteRequest.getAction().toUpperCase();
        this.timestamp = timestamp;
    }
}
