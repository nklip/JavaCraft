package my.javacraft.elastic.cucumber.helper.generator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/*
 * 🏆 Top (day / week / month / year / all)
 *
 * Raw net score within a time window — no decay.
 *
 * top_score = upvotes - downvotes
 *            WHERE submission_time >= (now - window)
 * ORDER BY top_score DESC
 *
 * |--------|---------------|
 * | Filter | Window        |
 * |--------|---------------|
 * | Day    | last 24 hours |
 * | Week   | last 7 days   |
 * | Month  | last 30 days  |
 * | Year   | last 365 days |
 * | All    | no filter     |
 * |--------|---------------|
 *
 * Key properties:
 *
 * 1) No time decay within the window — a post from 6 days ago competes equally with one from today (for "week")
 * 2) Best/Top are the same algorithm (Best is just an alias Reddit uses for "Top — All Time" on some views)
 * 3) Rewards sustained quality; a niche post with a dedicated community can still win "month" or "year"
 */
public class TopEvents implements EventGenerator {
    private static final String EVENTS_TOP_FILE = "events-top.csv";

    /*
     * Should update postIds from 41 to 50
     * The amount of users which would UPVOTE or DOWNVOTE - 300
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 41; postId <= 50; postId++) {
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = EventCsvSupport.isUpvote(userId, postId, 82);
                long daysAgo = Math.floorMod(postId * 13 + userId * 17, 365);
                long hoursAgo = Math.floorMod(postId * 19 + userId * 23, 24);
                long minutesAgo = Math.floorMod(postId * 7 + userId * 29, 60);
                Instant eventTime = now.minus(daysAgo, ChronoUnit.DAYS)
                        .minus(hoursAgo, ChronoUnit.HOURS)
                        .minus(minutesAgo, ChronoUnit.MINUTES);

                rows.add(EventCsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
        }

        EventCsvSupport.writeCsv(EVENTS_TOP_FILE, rows);
    }
}
