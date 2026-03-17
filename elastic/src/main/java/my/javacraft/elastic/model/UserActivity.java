package my.javacraft.elastic.model;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import my.javacraft.elastic.service.activity.UserActivityService;

/**
 * Vote document stored in the 'user-activity' index.
 * Document ID is {@code userId_postId} — one document per (user, post) pair.
 *
 * <p>The document represents the user's <em>current</em> vote on a post.
 * {@link UserActivityService} enforces
 * the following state machine:
 * <ul>
 *   <li>First vote → document created ({@code Result.Created})</li>
 *   <li>Same action repeated → no write ({@code Result.NoOp})</li>
 *   <li>Opposite action → action and timestamp updated ({@code Result.Updated})</li>
 *   <li>{@code NOVOTE} → document deleted, no {@code UserActivity} is created
 *       ({@code Result.Deleted} or {@code Result.NotFound})</li>
 * </ul>
 *
 * <p>The {@code action} field is always stored in uppercase (e.g. {@code UPVOTE}, {@code DOWNVOTE})
 * to match the ES keyword values queried by {@link my.javacraft.elastic.service.activity.TopService}
 * and {@link my.javacraft.elastic.service.activity.HotService}.
 */
@Data
@ToString
@NoArgsConstructor
public class UserActivity {

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

    public UserActivity(UserPostEvent userPostEvent, String timestamp) {
        this.postId = userPostEvent.getPostId();
        this.userId = userPostEvent.getUserId();
        this.action = userPostEvent.getAction().toUpperCase();
        this.timestamp = timestamp;
    }
}
