package my.javacraft.elastic.cucumber.helper.generator;

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
public class NewEvents implements EventGenerator {
    private static final String EVENTS_NEW_FILE = "events-new.csv";

    /*
     * Should update postIds from 21 to 30
     */
    @Override
    public void generateEventsInCsv() {

    }
}
