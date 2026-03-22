package my.javacraft.elastic.data.csv;

/*
 * Every posts-*.csv file should contain CSV columns - postId,createdAt,author
 * Every votes-*.csv file should contain CSV columns - userId,postId,action,date
 *
 * For example:
 * 1) userId,postId,action,date
 * 2) user-001,post-91,UPVOTE,2025-09-23T06:27:36Z
 * 3) user-002,post-91,DOWNVOTE,2026-01-16T16:38:37Z
 *
 * Should have next implementations: Best / Hot / New / Rising / Top (day, week, month, year, all)
 */
public interface VoteGenerator {
    void generatePostVotesInCsv();
}
