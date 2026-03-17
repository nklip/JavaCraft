package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.EventCsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.EventGenerator;

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
public class RisingEvents implements EventGenerator {
    private static final String EVENTS_RISING_FILE = "events-rising.csv";

    /*
     * Should update postIds from 31 to 40
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
     *   - max karma (60) > max NewEvents karma (40) → posts 31-40 win Top MONTH.
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 31; postId <= 40; postId++) {
            int upvotePercent = 71 + (postId - 31);    // 71% → 80%, unique per post
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                long daysAgo  = 8 + Math.floorMod(postId * 11 + userId * 7, 21); // 8-28 days
                long hoursAgo = Math.floorMod(postId * 13 + userId * 5, 24);

                boolean upvote = EventCsvSupport.isUpvote(userId, postId, upvotePercent);
                long minutesAgo = Math.floorMod(postId * 23 + userId * 29, 60);
                Instant eventTime = now.minus(daysAgo, ChronoUnit.DAYS)
                        .minus(hoursAgo, ChronoUnit.HOURS)
                        .minus(minutesAgo, ChronoUnit.MINUTES);

                rows.add(EventCsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
        }

        EventCsvSupport.writeCsv(EVENTS_RISING_FILE, rows);
    }
}
