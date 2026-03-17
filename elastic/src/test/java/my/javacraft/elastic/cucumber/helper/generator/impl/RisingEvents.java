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
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 31; postId <= 40; postId++) {
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                int upvotePercent;
                long daysAgo;
                long hoursAgo;

                if (userId <= 60) {
                    upvotePercent = 72;
                    daysAgo = 120 + Math.floorMod(postId * 7 + userId * 3, 60);
                    hoursAgo = Math.floorMod(postId * 5 + userId * 11, 24);
                } else if (userId <= 80) {
                    upvotePercent = 85;
                    daysAgo = 15 + Math.floorMod(postId * 11 + userId * 5, 45);
                    hoursAgo = Math.floorMod(postId * 13 + userId * 7, 24);
                } else {
                    upvotePercent = 95;
                    daysAgo = Math.floorMod(postId * 3 + userId * 2, 7);
                    hoursAgo = Math.floorMod(postId * 17 + userId * 19, 24);
                }

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
