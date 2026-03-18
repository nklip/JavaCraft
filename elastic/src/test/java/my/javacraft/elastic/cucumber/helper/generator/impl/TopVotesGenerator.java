package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.CsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.VoteGenerator;

/*
 * 🏆 Top (day / week / month / year / all)
 *
 * Raw net score within a time window — no decay.
 *
 * top_score = upvotes - downvotes
 *            WHERE submission_time >= (now - window)
 * ORDER BY top_score DESC
 *
 * Text table:
 * ┌────────┬───────────────┐
 * │ Filter │ Window        │
 * ├────────┼───────────────┤
 * │ Day    │ last 24 hours │
 * │ Week   │ last 7 days   │
 * │ Month  │ last 30 days  │
 * │ Year   │ last 365 days │
 * │ All    │ no filter     │
 * └────────┴───────────────┘
 *
 * Key properties:
 *
 * 1) No time decay within the window — a post from 6 days ago competes equally with one from today (for "week")
 * 2) Best/Top are the same algorithm (Best is just an alias Reddit uses for "Top — All Time" on some views)
 * 3) Rewards sustained quality; a niche post with a dedicated community can still win "month" or "year"
 */
public class TopVotesGenerator implements VoteGenerator {
    private static final String VOTES_TOP_FILE = "votes-top.csv";
    private static final String POSTS_TOP_FILE = "posts-top.csv";

    /*
     * MUST HAVE: Top 10 'Top for ALL' postIds MUST have postIds from 41 to 50
     *
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 41 → upvote% 91 → karma 82
     *   postId 42 → upvote% 92 → karma 84
     *   ...
     *   postId 50 → upvote% 100 → karma 100
     *
     * karma = 2 × upvotePercent − 100  (exact because isUpvote() produces a full
     * permutation of 0-99 over 100 users when gcd(31, 100) = 1).
     *
     * All karma groups across all generators form a non-overlapping sequence:
     *
     *   posts 11-20  karma  2-20  (HotVotesGenerator,    51-60%)   → Top DAY
     *   posts 21-30  karma 22-40  (NewVotesGenerator,    61-70%)   → Top WEEK
     *   posts 31-40  karma 42-60  (RisingVotesGenerator, 71-80%)   → Top MONTH
     *   posts 01-10  karma 62-80  (BestVoteGenerator,   81-90%)   → Top YEAR
     *   posts 41-50  karma 82-100 (TopVotesGenerator,    91-100%)  → Top ALL
     *
     * Date pattern: all events are 366–730 days old.
     *   - 366-day floor puts every event outside the 365-day Top YEAR window.
     *   - Top ALL has no time filter, so all votes count → posts 41-50 win Top ALL.
     *   - first_seen > 365 days old → massive time penalty in Hot, so Hot is NOT won.
     */
    @Override
    public void generatePostVotesInCsv() {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * CsvSupport.USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        for (int postId = 41; postId <= 50; postId++) {
            int upvotePercent = 91 + (postId - 41);    // 91% → 100%, unique per post

            Instant createdAt = Instant.MAX;
            for (int userId = 1; userId <= CsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = CsvSupport.isUpvote(userId, postId, upvotePercent);
                long daysAgo    = 366 + Math.floorMod(postId * 13 + userId * 17, 365); // 366-730 days
                long hoursAgo   = Math.floorMod(postId * 19 + userId * 23, 24);
                long minutesAgo = Math.floorMod(postId * 7 + userId * 29, 60);
                Instant eventTime = now.minus(daysAgo, ChronoUnit.DAYS)
                        .minus(hoursAgo, ChronoUnit.HOURS)
                        .minus(minutesAgo, ChronoUnit.MINUTES);

                if (eventTime.isBefore(createdAt)) {
                    createdAt = eventTime;
                }
                eventRows.add(CsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
            postRows.add(CsvSupport.postCsvLine(postId, createdAt));
        }

        CsvSupport.writeVotesCsv(VOTES_TOP_FILE, eventRows);
        CsvSupport.writePostsCsv(POSTS_TOP_FILE, postRows);
    }
}
