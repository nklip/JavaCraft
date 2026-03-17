package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.EventCsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.EventGenerator;

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
public class BestEvents implements EventGenerator {
    private static final String EVENTS_BEST_FILE = "events-best.csv";

    /*
     * Should update postIds from 01 to 10
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
     *   - 364-day ceiling keeps every event inside the 365-day Top YEAR window.
     *   - max karma (80) > max RisingEvents karma (60) → posts 01-10 win Top YEAR.
     *   - first_seen ≈ 364 days old → large time penalty in Hot, so Hot is NOT won.
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 1; postId <= 10; postId++) {
            int upvotePercent = 81 + (postId - 1);    // 81% → 90%, unique per post
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = EventCsvSupport.isUpvote(userId, postId, upvotePercent);
                long daysAgo    = 31 + Math.floorMod(postId * 19 + userId * 7, 334); // 31-364 days
                long hoursAgo   = Math.floorMod(postId * 11 + userId * 3, 24);
                long minutesAgo = Math.floorMod(postId * 5 + userId * 13, 60);
                Instant eventTime = now.minus(daysAgo, ChronoUnit.DAYS)
                        .minus(hoursAgo, ChronoUnit.HOURS)
                        .minus(minutesAgo, ChronoUnit.MINUTES);

                rows.add(EventCsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
        }

        EventCsvSupport.writeCsv(EVENTS_BEST_FILE, rows);
    }
}
