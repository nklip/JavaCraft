package my.javacraft.elastic.cucumber.helper.generator;

/*
 * ⭐ Best (Controversial's opposite)
 *
 * Bayesian confidence interval on upvote ratio.
 *
 * Reddit uses the Wilson score lower bound:
 *
 * best_score = lower bound of 95% confidence interval
 *              for the true upvote proportion p̂
 *
 * p̂    = upvotes / total_votes
 * z    = 1.96  (95% confidence)
 *
 * best = (p̂ + z²/2n − z√(p̂(1−p̂)/n + z²/4n²)) / (1 + z²/n)
 *
 * Key properties:
 *
 * 1) Penalises posts/comments with few votes (high uncertainty)
 * 2) A comment with 10 upvotes / 0 downvotes scores lower than one with 1000/50
 * 3) Designed for comment sorting — surfaces reliably good comments, not just lucky early ones
 * 4) Used as the default comment sort ("Best" in the UI)
 */
public class BestEvents implements EventGenerator {
    private static final String EVENTS_BEST_FILE = "events-best.csv";

    /*
     * Should update postIds from 01 to 10
     */
    @Override
    public void generateEventsInCsv() {

    }
}
