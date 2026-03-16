package my.javacraft.elastic.cucumber.helper.generator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

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
     * The amount of users which would UPVOTE or DOWNVOTE - 300
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 21; postId <= 30; postId++) {
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = EventCsvSupport.isUpvote(userId, postId, 85);
                long minutesAgo = Math.floorMod(postId * 43 + userId * 11, 24 * 60L);
                Instant eventTime = now.minus(minutesAgo, ChronoUnit.MINUTES);

                rows.add(EventCsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
        }

        EventCsvSupport.writeCsv(EVENTS_NEW_FILE, rows);
    }
}
