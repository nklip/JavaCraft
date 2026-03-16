package my.javacraft.elastic.cucumber.helper.generator;

/*
 * ⬆️ Rising
 *
 * New posts gaining momentum faster than baseline.
 *
 * Not a published formula; behaviorally it is:
 *
 * 1) Candidates: posts younger than ~6 hours
 * 2) Ranked by velocity — upvote rate (upvotes per unit time) rather than raw count
 * 3) A post with 50 upvotes in 30 minutes beats one with 200 upvotes over 5 hours
 * 4) Acts as an early-warning signal before a post reaches Hot
 * 5) Drops off once velocity slows or the post ages out of the candidate window
 *
 */
public class RisingEvents implements EventGenerator {
    private static final String EVENTS_RISING_FILE = "events-rising.csv";

    /*
     * Should update postIds from 31 to 40
     */
    @Override
    public void generateEventsInCsv() {

    }
}
