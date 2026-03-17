package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.EventCsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.EventGenerator;

/*
 * 🔥 Hot
 *
 * Time-decayed net score — the "front page" algorithm.
 *
 * Reddit's actual formula (open-sourced):
 *
 * score     = upvotes - downvotes
 * order     = log₁₀(max(|score|, 1)) × sign(score)
 * seconds   = submission_time - 1134028003   # epoch anchor (Dec 2005)
 * hot_score = order + seconds / 45000
 *
 * Key properties:
 *
 * 1) log₁₀ compresses the vote gap — going from 1→10 votes is as valuable as 10→100
 * 2) Time contribution grows linearly: every ~12.5 hours a post gains +1 to the score
 * 3) Early votes matter more than late votes (a post that reaches 100 votes in hour 1 beats one that reaches 1000
 * votes in hour 10)
 * 4) No hard cutoff — old posts with massive scores can still appear, just very slowly pushed down
 */
public class HotEvents implements EventGenerator {
    private static final String EVENTS_HOT_FILE = "events-hot.csv";

    /*
     * Should update postIds from 11 to 20
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Correctness guarantee (Reddit Hot formula):
     *   hot_score = log₁₀(net) + (firstSeenSec − anchor) / 45_000
     *   Time dominates: every 12.5 h of age costs ~1 hot_score point.
     *
     * Strategy: all events land within the last 60 minutes so that
     *   min(timestamp) — used as submission_time proxy — is at most ~60 min old.
     *   This is ~23 h more recent than NewEvents (first_seen ≤ 24 h),
     *   translating to ~1.84 hot_score units of separation:
     *
     *   posts 11-20 hot_score ≈ 13 516  (first_seen ~60 min, net = 80)
     *   posts 21-30 hot_score ≈ 13 514  (first_seen ~24 h,  net = 70)  ← 2nd place
     */
    @Override
    public void generateEventsInCsv() {
        Instant now = EventCsvSupport.now();
        List<String> rows = new ArrayList<>(10 * EventCsvSupport.USERS_PER_POST);

        for (int postId = 11; postId <= 20; postId++) {
            for (int userId = 1; userId <= EventCsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = EventCsvSupport.isUpvote(userId, postId, 90);
                // Keep all events within a 60-minute window so first_seen stays very recent
                long minutesAgo = Math.floorMod(postId * 7 + userId * 5, 60L);
                long secondsAgo = Math.floorMod(postId * 13 + userId * 17, 60L);
                Instant eventTime = now.minus(minutesAgo, ChronoUnit.MINUTES)
                        .minus(secondsAgo, ChronoUnit.SECONDS);

                rows.add(EventCsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
        }

        EventCsvSupport.writeCsv(EVENTS_HOT_FILE, rows);
    }
}
