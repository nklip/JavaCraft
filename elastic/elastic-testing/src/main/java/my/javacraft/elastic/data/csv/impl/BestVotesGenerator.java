package my.javacraft.elastic.data.csv.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import my.javacraft.elastic.data.csv.CsvSupport;
import my.javacraft.elastic.data.csv.VoteGenerator;

/*
 * ⭐ Best (Wilson score) + 🏆 Top YEAR
 *
 * Posts 01-10 win BOTH the Best (Wilson score) sort AND the Top YEAR window.
 *
 * ── Best (Wilson score lower bound, 95 % confidence) ─────────────────────────
 *
 * Wilson score rewards the highest UPVOTE RATIO backed by a statistically
 * significant sample. Posts 01-10 use 83-92 % upvote ratios at n=100 voters,
 * giving Wilson scores 0.745–0.850.
 *
 * TopVotesGenerator uses 61-70 % at n=500 voters, which gives Wilson scores
 * 0.567–0.658 — below even the worst post here (0.745).
 * Posts 01-10 therefore win the Best sort across the entire dataset:
 *
 *   post-10 ( 92 %, n=100): Wilson ≈ 0.850  ← best post, highest Wilson
 *   post-01 ( 83 %, n=100): Wilson ≈ 0.745  ← weakest post, still beats TopVotesGenerator
 *   post-50 ( 70 %, n=500): Wilson ≈ 0.658  ← TopVotesGenerator best, loses Best
 *
 * Wilson score formula:
 *   p̂    = upvotes / total_votes
 *   z    = 1.96  (95 % confidence)
 *   best = (p̂ + z²/2n − z√(p̂(1−p̂)/n + z²/4n²)) / (1 + z²/n)
 *
 * ── Top YEAR ─────────────────────────────────────────────────────────────────
 *
 * Top YEAR filters to posts whose createdAt is within the last 365 days.
 * TopVotesGenerator events are 366–730 days old and fall outside that window.
 * Within the YEAR window, posts 01-10 (karma 66-84) have the highest karma.
 */
public class BestVotesGenerator implements VoteGenerator {
    private static final String VOTES_BEST_FILE = "votes-best.csv";
    private static final String POSTS_BEST_FILE = "posts-best.csv";

    /*
     * MUST HAVE: Top 10 'Best' (Wilson score) postIds MUST have postIds from 01 to 10
     *            Top 10 'Top YEAR' postIds MUST have postIds from 01 to 10
     *
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 01 → upvote% 83 → karma 66 → Wilson ≈ 0.745
     *   postId 02 → upvote% 84 → karma 68 → Wilson ≈ 0.757
     *   ...
     *   postId 10 → upvote% 92 → karma 84 → Wilson ≈ 0.850
     *
     * karma = 2 × upvotePercent − 100  (exact because isUpvote() produces a full
     * permutation of 0-99 over 100 users when gcd(31, 100) = 1).
     *
     * Starts at 83 % so karma values (66-84) stay far above
     * RisingVotesGenerator's current range (12-20).
     *
     * Date pattern: all events are 31–364 days old.
     *   - 31-day floor keeps every event outside the 30-day Top MONTH window.
     *   - Ceiling capped at 362 days so that even with 22 h file staleness + 23 h hoursAgo
     *     + 59 min minutesAgo the oldest event is at most 362d + 45h 59min = 363d 21h 59min,
     *     safely inside the 365-day Top YEAR window.
     *   - max karma (84) > RisingVotesGenerator max karma (20) → posts 01-10 win Top YEAR.
     *   - first_seen ≈ 364 days old → large time penalty in Hot, so Hot is NOT won.
     *   - Wilson 0.745–0.850 > TopVotesGenerator 0.567–0.658 → posts 01-10 WIN Best sort.
     */
    @Override
    public void generatePostVotesInCsv(Path outputDirectory) {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * CsvSupport.USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        int postCount = 0;
        for (int postId = 1; postId <= 10; postId++) {
            int upvotePercent = 83 + (postId - 1);    // 83% → 92%, unique per post
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

        CsvSupport.writeVotesCsv(VOTES_BEST_FILE, eventRows, outputDirectory);
        CsvSupport.writePostsCsv(POSTS_BEST_FILE, postRows, outputDirectory);
    }
}
