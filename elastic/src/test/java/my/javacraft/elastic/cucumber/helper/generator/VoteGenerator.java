package my.javacraft.elastic.cucumber.helper.generator;

/*
 * Every CSV file should contain CSV columns - userId,postId,action,date
 *
 * For example:
 * 1) userId,postId,action,date
 * 2) user-001,post-91,UPVOTE,2025-09-23T06:27:36Z
 * 3) user-002,post-91,DOWNVOTE,2026-01-16T16:38:37Z
 *
 * Each generateEventsInCsv should generate between 1000-6000 events, depends on necessity.
 *
 * Events should be generated within the last 6 months.
 */
public interface VoteGenerator {
    void generatePostVotesInCsv();
}
