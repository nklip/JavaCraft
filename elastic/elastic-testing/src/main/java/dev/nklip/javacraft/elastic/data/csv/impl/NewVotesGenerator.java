package dev.nklip.javacraft.elastic.data.csv.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import dev.nklip.javacraft.elastic.data.csv.CsvSupport;
import dev.nklip.javacraft.elastic.data.csv.VoteGenerator;

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
public class NewVotesGenerator implements VoteGenerator {
    private static final String VOTES_NEW_FILE = "votes-new.csv";
    private static final String POSTS_NEW_FILE = "posts-new.csv";
    private static final int FIRST_POST_ID = 21;
    private static final int LAST_POST_ID = 30;
    private static final int MAX_USERS_PER_POST = 10;
    private static final int MINUTES_WINDOW = 10;

    /*
     * MUST HAVE: Top 10 'New' postIds MUST have postIds from 21 to 30
     *
     * All generated timestamps are within the last 10 minutes from generation time.
     *
     * Only up to 10 users can vote for a post.
     * User participation is variable per post (not every eligible user has to vote).
     */
    @Override
    public void generatePostVotesInCsv(Path outputDirectory) {
        Instant now = CsvSupport.now();
        List<String> eventRows = new ArrayList<>(10 * MAX_USERS_PER_POST);
        List<String> postRows  = new ArrayList<>(10);

        int postCount = 0;
        for (int postId = FIRST_POST_ID; postId <= LAST_POST_ID; postId++) {
            int upvotePercent = 55 + Math.floorMod(postId, 10); // deterministic 55..64%
            int authorUserId = ++postCount;                     // user-001 … user-010
            int votersForPost = 1 + Math.floorMod(postId * 7, MAX_USERS_PER_POST); // deterministic 1..10

            // Keep all "new" posts in a very recent band so they stay ahead of Rising fixtures
            // in the New feed (secondary sort is postId ASC when timestamps tie).
            // post-30 -> 33s ago, post-21 -> 96s ago.
            long createdSecondsAgo = 33L + (LAST_POST_ID - postId) * 7L;
            Instant createdAt = now.minus(createdSecondsAgo, ChronoUnit.SECONDS);

            for (int userId = 1; userId <= votersForPost; userId++) {
                boolean upvote = CsvSupport.isUpvote(userId, postId, upvotePercent);
                long minutesAgo = Math.floorMod(postId * 5 + userId * 3, MINUTES_WINDOW);
                long secondsAgo = Math.floorMod(postId * 11 + userId * 7, 60);
                Instant eventTime = now.minus(minutesAgo, ChronoUnit.MINUTES)
                        .minus(secondsAgo, ChronoUnit.SECONDS);
                eventRows.add(CsvSupport.csvLine(userId, postId, upvote, eventTime));
            }
            postRows.add(CsvSupport.postCsvLine(postId, createdAt, authorUserId));
        }

        CsvSupport.writeVotesCsv(VOTES_NEW_FILE, eventRows, outputDirectory);
        CsvSupport.writePostsCsv(POSTS_NEW_FILE, postRows, outputDirectory);
    }
}
