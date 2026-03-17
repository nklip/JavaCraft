package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.EventCsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.EventGenerator;

/*
 * 🆕 New
 *
 * Purely chronological — no ranking.
 *
 * 1) Sort key: submission_timestamp DESC
 * 2) Every post appears immediately regardless of votes
 * 3) No decay, no score influence
 * 4) Use case: see the firehose; catch posts before they get buried
 */
public class NewEvents implements EventGenerator {
    private static final String EVENTS_NEW_FILE = "events-new.csv";

    /*
     * Should update postIds from 21 to 30
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 21 → upvote% 61 → karma 22
     *   postId 22 → upvote% 62 → karma 24
     *   ...
     *   postId 30 → upvote% 70 → karma 40
     *
     * karma = 2 × upvotePercent − 100  (exact because isUpvote() produces a full
     * permutation of 0-99 over 100 users when gcd(31, 100) = 1).
     *
     * Date pattern: all events are 2–5 days old.
     *   - 2-day floor guarantees no event falls inside the 24-h Top DAY window.
     *   - Ceiling capped at 5 days so that even with 22 h file staleness + 23 h hoursAgo
     *     the oldest event is at most 5d + 22h + 23h = 6d 21h, safely inside the 7-day WEEK window.
     *   - max karma (40) > max HotEvents karma (20) → posts 21-30 win Top WEEK.
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 21; postId <= 30; postId++) {
            int upvotePercent = 61 + (postId - 21);    // 61% → 70%, unique per post
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = EventCsvSupport.isUpvote(userId, postId, upvotePercent);
                long daysAgo  = 2 + Math.floorMod(postId * 43 + userId * 11, 4); // 2-5 days
                long hoursAgo = Math.floorMod(postId * 7 + userId * 13, 24);
                Instant eventTime = now.minus(daysAgo, ChronoUnit.DAYS)
                        .minus(hoursAgo, ChronoUnit.HOURS);

                rows.add(EventCsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
        }

        EventCsvSupport.writeCsv(EVENTS_NEW_FILE, rows);
    }
}
