package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.CsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.VoteGenerator;

/*
 * ⬆️ Rising
 *
 * New posts gaining momentum faster than baseline.
 *
 * Not a published formula; behaviorally it is:
 *
 * 1) Candidates: posts younger than ~6 hours
 * 2) Ranked by velocity — net vote velocity (karma / ageSeconds)
 * 3) A post with 50 upvotes in 30 minutes beats one with 200 upvotes over 5 hours
 * 4) Acts as an early-warning signal before a post reaches Hot
 * 5) Drops off once velocity slows or the post ages out of the candidate window
 *
 */
public class RisingVotesGenerator implements VoteGenerator {
    private static final String VOTES_RISING_FILE = "votes-rising.csv";
    private static final String POSTS_RISING_FILE = "posts-rising.csv";
    private static final int FIRST_POST_ID = 31;
    private static final int LAST_POST_ID = 40;
    private static final int VOTERS_PER_POST = 30;
    private static final int OLDEST_CREATED_SECONDS_AGO = 130;
    private static final int CREATED_SECONDS_STEP = 3;

    /*
     * MUST HAVE: posts 31-40 are inside the Rising candidate window (< 6 h),
     *            while not winning New or Hot scenarios.
     *
     * Deterministic high-velocity strategy:
     *   - 30 votes per post (users 1..30)
     *   - upvotes vary 21..25 out of 30 -> karma varies 12..20
     *   - post.createdAt is staggered in a narrow 103..130 s band to keep deterministic
     *     risingScore ordering inside posts 31..40
     *
     * Why:
     *   - all posts 31-40 outrank Hot/New in risingScore (karma / ageSeconds)
     *   - too old for New top-10 (New posts are generated younger than this band)
     *   - still weaker than Hot winners in hotScore:
     *       * Rising karma <= 20
     *       * Hot winners karma=24..42
     *   - does not dominate Best (avoids near-100% tiny-sample Wilson scores)
     */
    @Override
    public void generatePostVotesInCsv() {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>((LAST_POST_ID - FIRST_POST_ID + 1) * VOTERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);
        int postCount = 0;
        for (int postId = FIRST_POST_ID; postId <= LAST_POST_ID; postId++) {
            int authorUserId = ++postCount; // unique author per post: user-001 … user-010
            int upvotesForPost = 21 + Math.min((postId - FIRST_POST_ID) / 2, 4); // 21..25 -> karma 12..20
            int createdSecondsAgo = OLDEST_CREATED_SECONDS_AGO - (postId - FIRST_POST_ID) * CREATED_SECONDS_STEP;
            Instant createdAt = now.minus(createdSecondsAgo, ChronoUnit.SECONDS);
            for (int userId = 1; userId <= VOTERS_PER_POST; userId++) {
                boolean upvote = userId <= upvotesForPost;
                Instant eventTime = createdAt.plusSeconds(30L + userId);
                eventRows.add(CsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
            postRows.add(CsvSupport.postCsvLine(postId, createdAt, authorUserId));
        }

        CsvSupport.writeVotesCsv(VOTES_RISING_FILE, eventRows);
        CsvSupport.writePostsCsv(POSTS_RISING_FILE, postRows);
    }
}
