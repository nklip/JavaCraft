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
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 1; postId <= 10; postId++) {
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = EventCsvSupport.isUpvote(userId, postId, 80);
                long daysAgo = Math.floorMod(postId * 19 + userId * 7, 180);
                long hoursAgo = Math.floorMod(postId * 11 + userId * 3, 24);
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
