package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.CsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.VoteGenerator;

/*
 * ⭐ Best (Controversial's opposite)
 *
 * Bayesian confidence interval on upvote ratio.
 *
 * Reddit uses the Wilson score lower bound:
 *
 * best_score = lower bound of 95% confidence interval
 *              for the true upvote proportion p̂
 *
 * p̂    = upvotes / total_votes
 * z    = 1.96  (95% confidence)
 *
 * best = (p̂ + z²/2n − z√(p̂(1−p̂)/n + z²/4n²)) / (1 + z²/n)
 *
 * Key properties:
 *
 * 1) Penalises posts/comments with few votes (high uncertainty)
 * 2) A comment with 10 upvotes / 0 downvotes scores lower than one with 1000/50
 * 3) Designed for comment sorting — surfaces reliably good comments, not just lucky early ones
 * 4) Used as the default comment sort ("Best" in the UI)
 */
public class BestVoteGenerator implements VoteGenerator {
    private static final String VOTES_BEST_FILE = "votes-best.csv";
    private static final String POSTS_BEST_FILE = "posts-best.csv";

    /*
     * MUST HAVE: Top 10 'Best' postIds MUST have postIds from 01 to 10
     *
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 01 → upvote% 81 → karma 62
     *   postId 02 → upvote% 82 → karma 64
     *   ...
     *   postId 10 → upvote% 90 → karma 80
     *
     * karma = 2 × upvotePercent − 100  (exact because isUpvote() produces a full
     * permutation of 0-99 over 100 users when gcd(31, 100) = 1).
     *
     * Date pattern: all events are 31–364 days old.
     *   - 31-day floor keeps every event outside the 30-day Top MONTH window.
     *   - Ceiling capped at 362 days so that even with 22 h file staleness + 23 h hoursAgo
     *     + 59 min minutesAgo the oldest event is at most 362d + 45h 59min = 363d 21h 59min,
     *     safely inside the 365-day Top YEAR window.
     *   - max karma (80) > max RisingVotesGenerator karma (60) → posts 01-10 win Top YEAR.
     *   - first_seen ≈ 364 days old → large time penalty in Hot, so Hot is NOT won.
     */
    @Override
    public void generatePostVotesInCsv() {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * CsvSupport.USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        int postCount = 0;
        for (int postId = 1; postId <= 10; postId++) {
            int upvotePercent = 81 + (postId - 1);    // 81% → 90%, unique per post
            int authorUserId = ++postCount;            // unique author per post: user-001 … user-010

            Instant createdAt = Instant.MAX;
            for (int userId = 1; userId <= CsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = CsvSupport.isUpvote(userId, postId, upvotePercent);
                long daysAgo    = 31 + Math.floorMod(postId * 19 + userId * 7, 332); // 31-362 days
                long hoursAgo   = Math.floorMod(postId * 11 + userId * 3, 24);
                long minutesAgo = Math.floorMod(postId * 5 + userId * 13, 60);
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

        CsvSupport.writeVotesCsv(VOTES_BEST_FILE, eventRows);
        CsvSupport.writePostsCsv(POSTS_BEST_FILE, postRows);
    }
}
