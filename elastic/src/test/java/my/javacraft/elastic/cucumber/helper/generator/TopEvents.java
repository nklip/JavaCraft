package my.javacraft.elastic.cucumber.helper.generator;

/*
 * 🏆 Top (day / week / month / year / all)
 *
 * Raw net score within a time window — no decay.
 *
 * top_score = upvotes - downvotes
 *            WHERE submission_time >= (now - window)
 * ORDER BY top_score DESC
 *
 * |--------|---------------|
 * | Filter | Window        |
 * |--------|---------------|
 * | Day    | last 24 hours |
 * | Week   | last 7 days   |
 * | Month  | last 30 days  |
 * | Year   | last 365 days |
 * | All    | no filter     |
 * |--------|---------------|
 *
 * Key properties:
 *
 * 1) No time decay within the window — a post from 6 days ago competes equally with one from today (for "week")
 * 2) Best/Top are the same algorithm (Best is just an alias Reddit uses for "Top — All Time" on some views)
 * 3) Rewards sustained quality; a niche post with a dedicated community can still win "month" or "year"
 */
public class TopEvents {

}
