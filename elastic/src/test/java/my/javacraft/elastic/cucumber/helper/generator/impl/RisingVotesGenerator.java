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
 * 2) Ranked by velocity — upvote rate (upvotes per unit time) rather than raw count
 * 3) A post with 50 upvotes in 30 minutes beats one with 200 upvotes over 5 hours
 * 4) Acts as an early-warning signal before a post reaches Hot
 * 5) Drops off once velocity slows or the post ages out of the candidate window
 *
 */
public class RisingVotesGenerator implements VoteGenerator {
    private static final String VOTES_RISING_FILE = "votes-rising.csv";
    private static final String POSTS_RISING_FILE = "posts-rising.csv";

    /*
     * MUST HAVE: Top 10 'Rising' postIds MUST have postIds from 31 to 40
     *
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 31 → upvote% 71 → karma 42
     *   postId 32 → upvote% 72 → karma 44
     *   ...
     *   postId 40 → upvote% 80 → karma 60
     *
     * karma = 2 × upvotePercent − 100  (exact because isUpvote() produces a full
     * permutation of 0-99 over 100 users when gcd(31, 100) = 1).
     *
     * Date pattern: all events are 8–29 days old.
     *   - 8-day floor keeps every event outside the 7-day Top WEEK window.
     *   - Ceiling capped at 28 days so that even with 22 h file staleness + 23 h hoursAgo
     *     + 59 min minutesAgo the oldest event is at most 28d + 45h 59min = 29d 21h 59min,
     *     safely inside the 30-day Top MONTH window.
     *   - max karma (60) > max NewVotesGenerator karma (40) → posts 31-40 win Top MONTH.
     */
    @Override
    public void generatePostVotesInCsv() {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * CsvSupport.USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        int postCount = 0;
        for (int postId = 31; postId <= 40; postId++) {
            int upvotePercent = 71 + (postId - 31);    // 71% → 80%, unique per post
            int authorUserId = ++postCount;             // unique author per post: user-001 … user-010

            Instant createdAt = Instant.MAX;
            for (int userId = 1; userId <= CsvSupport.USERS_PER_POST; userId++) {
                long daysAgo  = 8 + Math.floorMod(postId * 11 + userId * 7, 21); // 8-28 days
                long hoursAgo = Math.floorMod(postId * 13 + userId * 5, 24);

                boolean upvote = CsvSupport.isUpvote(userId, postId, upvotePercent);
                long minutesAgo = Math.floorMod(postId * 23 + userId * 29, 60);
                Instant eventTime = now.minus(daysAgo, ChronoUnit.DAYS)
                        .minus(hoursAgo, ChronoUnit.HOURS)
                        .minus(minutesAgo, ChronoUnit.MINUTES);

                if (eventTime.isBefore(createdAt)) {
                    createdAt = eventTime;
                }
                eventRows.add(CsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
            postRows.add(CsvSupport.postCsvLine(postId, createdAt, authorUserId));
        }

        CsvSupport.writeVotesCsv(VOTES_RISING_FILE, eventRows);
        CsvSupport.writePostsCsv(POSTS_RISING_FILE, postRows);
    }
}
