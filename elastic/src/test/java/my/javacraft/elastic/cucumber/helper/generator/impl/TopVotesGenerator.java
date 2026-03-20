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
 * 2) Rewards sustained quality; a niche post with a dedicated community can still win "month" or "year"
 *
 * ── Why this generator uses n=500 voters at 61-70 % (not 91-100 % like before) ──
 *
 * The ⭐ Best sort uses the Wilson score lower bound (95 % confidence interval on
 * the upvote ratio). Wilson score is bounded asymptotically by the upvote ratio:
 * no matter how many voters you add, Wilson(90 %) < 0.90 < Wilson(91 %) for
 * large n. So BestVotesGenerator (81-90 %) can NEVER beat TopVotesGenerator
 * (91-100 %) in Wilson score when both use the same voter count.
 *
 * To let posts 01-10 (BestVotesGenerator, 83-92 %) win Best, we must bring
 * TopVotesGenerator's Wilson scores BELOW BestVotesGenerator's Wilson scores.
 * That means TopVotesGenerator must use a LOWER upvote ratio (61-70 %).
 *
 * To keep posts 41-50 winning Top ALL (highest karma, no time filter), the
 * reduced ratio is compensated by more voters: n=500 gives
 *   karma = 500 × (2 × upvote% − 1): 110, 120, …, 200
 * which is above BestVotesGenerator's maximum (84).
 *
 * Wilson score comparison (deciding the Best winner):
 *   BestVotesGenerator  post-01 ( 83 %, n=100): Wilson ≈ 0.745  ← lowest, still wins
 *   BestVotesGenerator  post-10 ( 92 %, n=100): Wilson ≈ 0.850  ← highest BestVotesGenerator
 *   TopVotesGenerator  post-41 ( 61 %, n=500): Wilson ≈ 0.567  ← highest TopVotesGenerator …loses
 *   TopVotesGenerator  post-50 ( 70 %, n=500): Wilson ≈ 0.658  ← loses to even post-01
 */
public class TopVotesGenerator implements VoteGenerator {
    private static final String VOTES_TOP_FILE = "votes-top.csv";
    private static final String POSTS_TOP_FILE  = "posts-top.csv";

    /**
     * 500 voters per post — 5× the shared constant — so that karma (110-200) exceeds
     * BestVotesGenerator's ceiling (80) at the lower 61-70 % upvote ratios used here.
     * karma = n × (2 × upvote% − 1), e.g. 500 × 0.22 = 110 for post-41 (61 %).
     */
    private static final int USERS_PER_POST = 500;

    /*
     * MUST HAVE: Top 10 'Top ALL' postIds MUST have postIds from 41 to 50
     *            Top 10 'Best' (Wilson score) MUST NOT include these postIds
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 41 → upvote% 61 → karma 110 → Wilson ≈ 0.567
     *   postId 42 → upvote% 62 → karma 120 → Wilson ≈ 0.579
     *   ...
     *   postId 50 → upvote% 70 → karma 200 → Wilson ≈ 0.658
     *
     * karma = n × (2 × upvotePercent − 1) = 500 × (2p − 1)
     * (isUpvote() cycles through all 100 buckets every 100 users, so n=500 gives
     *  exactly 5 × upvotePercent upvotes and 5 × (100 − upvotePercent) downvotes.)
     *
     * All karma groups across all generators form a non-overlapping sequence:
     *
     *   posts 21-30  karma   1-3     (NewVotesGenerator,   55-64%, n<=10)    → New feed
     *   posts 11-20  karma  24–  42  (HotVotesGenerator,    62-71%, n=100)  → Hot + Top DAY + Top WEEK
     *   posts 31-40  karma  12-20    (RisingVotesGenerator, 70-83%, n=30)    → Rising top-10
     *   posts 01-10  karma  66–  84  (BestVotesGenerator,    83-92%, n=100)  → Top YEAR + Best ⭐
     *   posts 41-50  karma 110– 200  (TopVotesGenerator,    61-70%, n=500)  → Top ALL
     *
     * Date pattern: all events are 366–730 days old.
     *   - 366-day floor puts every event outside the 365-day Top YEAR window.
     *   - Top ALL has no time filter, so all votes count → posts 41-50 win Top ALL.
     *   - first_seen > 365 days old → massive time penalty in Hot, so Hot is NOT won.
     */
    @Override
    public void generatePostVotesInCsv() {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        int postCount = 0;
        for (int postId = 41; postId <= 50; postId++) {
            int upvotePercent = 61 + (postId - 41);    // 61% → 70%, unique per post
            int authorUserId = ++postCount;             // unique author per post: user-001 … user-010

            Instant createdAt = Instant.MAX;
            for (int userId = 1; userId <= USERS_PER_POST; userId++) {
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
            postRows.add(CsvSupport.postCsvLine(postId, createdAt, authorUserId));
        }

        CsvSupport.writeVotesCsv(VOTES_TOP_FILE, eventRows);
        CsvSupport.writePostsCsv(POSTS_TOP_FILE, postRows);
    }
}
