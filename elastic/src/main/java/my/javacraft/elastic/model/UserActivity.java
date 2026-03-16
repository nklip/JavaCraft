package my.javacraft.elastic.model;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Immutable event document stored in the 'user-activity' index.
 * Each user click produces a new document — no updates, no counters.
 * Analytics (popular, trending, top, rising) are derived via ES aggregations.
 */
@Data
@ToString
@NoArgsConstructor
public class UserActivity {

    @NotEmpty
    String recordId;
    @NotEmpty
    String userId;
    @NotEmpty
    String searchType;
    @NotEmpty
    String searchValue;
    /**
     * ISO-8601 UTC timestamp of the click event.
     * Must be mapped as 'date' type in the ES index mapping.
     */
    @NotEmpty
    String timestamp;

    public UserActivity(UserClick userClick, String timestamp) {
        this.recordId = userClick.getRecordId();
        this.userId = userClick.getUserId();
        this.searchType = userClick.getSearchType();
        this.searchValue = userClick.getSearchPattern();
        this.timestamp = timestamp;
    }
}
