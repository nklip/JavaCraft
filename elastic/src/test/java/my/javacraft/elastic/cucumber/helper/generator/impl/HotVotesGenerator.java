package my.javacraft.elastic.cucumber.helper.generator.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.cucumber.helper.generator.CsvSupport;
import my.javacraft.elastic.cucumber.helper.generator.VoteGenerator;

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
public class HotVotesGenerator implements VoteGenerator {
    private static final String VOTES_HOT_FILE = "votes-hot.csv";
    private static final String POSTS_HOT_FILE = "posts-hot.csv";

    /*
     * MUST HAVE: Top 10 'Hot' postIds MUST have postIds from 11 to 20
     *
     * The amount of users which would UPVOTE or DOWNVOTE - 100
     *
     * Correctness guarantee (Reddit Hot formula):
     *   hot_score = log₁₀(net) + (firstSeenSec − anchor) / 45_000
     *   Time dominates: every 12.5 h of age costs ~1 hot_score point.
     *
     * Strategy: all events land within the last 60 minutes so that
     *   min(timestamp) — used as submission_time proxy — is at most ~60 min old.
     *   NewVotesGenerator events are 2+ days old → time penalty ≥ 3.84 hot_score points,
     *   which dwarfs any karma log₁₀ difference → posts 11-20 always win Hot.
     *
     * Each postId has a unique upvote percentage so that karma is unique per post:
     *
     *   postId 11 → upvote% 51 → karma  2
     *   postId 12 → upvote% 52 → karma  4
     *   ...
     *   postId 20 → upvote% 60 → karma 20
     *
     * karma = 2 × upvotePercent − 100  (exact because isUpvote() produces a full
     * permutation of 0-99 over 100 users when gcd(31, 100) = 1).
     *
     * Top window: all 100 votes within the last 60 min → fully counted in Top DAY.
     * max karma (20) < min NewVotesGenerator karma (22) → posts 11-20 win only Top DAY.
     */
    @Override
    public void generatePostVotesInCsv() {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * CsvSupport.USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        int postCount = 0;
        for (int postId = 11; postId <= 20; postId++) {
            int upvotePercent = 51 + (postId - 11);    // 51% → 60%, unique per post
            int authorUserId = ++postCount;             // unique author per post: user-001 … user-010

            /*
             * createdAt = 7–10 days ago — these are older posts that received a burst of
             * fresh votes within the last 60 min (simulates a viral "second wind").
             * Setting createdAt well before NewVotesGenerator (2–5 days old) ensures posts 11-20
             * never appear in the top-10 of the 'New' feed, which is sorted by createdAt DESC.
             */
            int createdDaysAgo = 7 + Math.floorMod(postId * 11, 4);  // 7–10 days

            for (int userId = 1; userId <= CsvSupport.USERS_PER_POST; userId++) {
                boolean upvote = CsvSupport.isUpvote(userId, postId, upvotePercent);
                // Keep all events within a 60-minute window so first_seen stays very recent
                long minutesAgo = Math.floorMod(postId * 7 + userId * 5, 60L);
                long secondsAgo = Math.floorMod(postId * 13 + userId * 17, 60L);
                Instant eventTime = now.minus(minutesAgo, ChronoUnit.MINUTES)
                        .minus(secondsAgo, ChronoUnit.SECONDS);

                eventRows.add(CsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
            postRows.add(CsvSupport.postCsvLine(postId, now.minus(createdDaysAgo, ChronoUnit.DAYS), authorUserId));
        }

        CsvSupport.writeVotesCsv(VOTES_HOT_FILE, eventRows);
        CsvSupport.writePostsCsv(POSTS_HOT_FILE, postRows);
    }
}
