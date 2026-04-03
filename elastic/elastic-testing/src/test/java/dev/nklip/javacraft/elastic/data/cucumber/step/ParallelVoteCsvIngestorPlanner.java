package dev.nklip.javacraft.elastic.data.cucumber.step;

import dev.nklip.javacraft.elastic.api.model.VoteRequest;
import dev.nklip.javacraft.elastic.app.service.VoteService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepares deterministic parallel ingestion tasks for the generated vote CSV fixtures used by
 * {@code PostRankingController.feature}.
 *
 * <p>The ranking datasets are large enough that a fully serial vote replay is noticeably slow.
 * At the same time, replaying votes completely in parallel is unsafe because many rows update the
 * same post. Each vote eventually changes the aggregate scores stored for that post, so concurrent
 * writes for the same {@code postId} would introduce unnecessary contention and could make the
 * ranking setup harder to reason about.
 *
 * <p>This planner solves that by partitioning work by {@code postId}:
 *
 * <ul>
 *     <li>votes for different posts can be replayed in parallel</li>
 *     <li>votes for the same post stay serial and preserve CSV order</li>
 * </ul>
 *
 * <p>The end result is the same logical dataset as the old serial replay, but with better
 * throughput because independent posts no longer block each other.
 */
final class ParallelVoteCsvIngestorPlanner {

    private static final String EXPECTED_HEADER = "userId,postId,action,date";

    private ParallelVoteCsvIngestorPlanner() {
    }

    /**
     * Reads one or more vote CSV files and converts them into stable per-post ingestion tasks.
     *
     * <p>Why plan first instead of ingesting while reading:
     *
     * <ol>
     *     <li>CSV parsing remains single-threaded and easy to validate.</li>
     *     <li>All rows for the same {@code postId} are collected together before execution.</li>
     *     <li>The caller can then parallelize over independent posts without worrying about
     *         cross-post ordering bugs.</li>
     * </ol>
     *
     * <p>A {@link LinkedHashMap} is used to preserve the first-seen order of posts, and each
     * per-post list preserves row order inside that post.
     */
    static List<VoteIngestionTask> plan(List<Path> csvFiles) throws IOException {
        Map<String, List<VoteCsvRow>> rowsByPostId = new LinkedHashMap<>();

        for (Path csvFile : csvFiles) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(csvFile), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                validateHeader(header, csvFile);

                String line;
                int lineNumber = 1;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }

                    VoteCsvRow row = parseRow(line, lineNumber, csvFile);
                    rowsByPostId.computeIfAbsent(row.postId(), ignored -> new ArrayList<>()).add(row);
                }
            }
        }

        return rowsByPostId.entrySet().stream()
                .map(entry -> new VoteIngestionTask(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    /**
     * Verifies that the CSV file matches the exact test fixture format expected by the planner.
     * Failing fast here keeps bad fixture files from producing confusing downstream vote errors.
     */
    private static void validateHeader(String header, Path csvFile) {
        if (header == null) {
            throw new IllegalStateException("CSV file is empty: " + csvFile);
        }
        if (!EXPECTED_HEADER.equals(header.trim())) {
            throw new IllegalStateException("Unexpected CSV header in " + csvFile + ": " + header);
        }
    }

    private static VoteCsvRow parseRow(String line, int lineNumber, Path csvFile) {
        String[] values = line.split(",", -1);
        if (values.length != 4) {
            throw new IllegalStateException(
                    "Invalid CSV row at line %d in %s".formatted(lineNumber, csvFile)
            );
        }
        return new VoteCsvRow(
                values[0].trim(),
                values[1].trim(),
                values[2].trim(),
                values[3].trim()
        );
    }

    /**
     * Immutable replay task for all votes belonging to one post.
     *
     * <p>The caller is free to run different {@code VoteIngestionTask}s in parallel, but each
     * task itself must stay serial. That serial replay guarantees that:
     *
     * <ul>
     *     <li>vote processing for a single post is deterministic</li>
     *     <li>score updates on that post follow the same order as the CSV fixture</li>
     *     <li>the final ranking data matches the expected feature assertions</li>
     * </ul>
     */
    record VoteIngestionTask(String postId, List<VoteCsvRow> rows) {

        /**
         * Replays all rows for one post through the real {@link VoteService}.
         *
         * <p>Using the production vote service here is intentional: the Cucumber scenario is
         * validating the full score-update path, not just CSV parsing.
         *
         * @return number of CSV rows replayed for this post
         */
        int ingest(VoteService voteService) throws IOException {
            int ingestedRows = 0;

            for (VoteCsvRow row : rows) {
                VoteRequest voteRequest = new VoteRequest();
                voteRequest.setUserId(row.userId());
                voteRequest.setPostId(row.postId());
                voteRequest.setAction(row.action());

                voteService.processVoteRequest(voteRequest, row.timestamp());
                ingestedRows++;
            }

            return ingestedRows;
        }
    }

    /**
     * Parsed representation of one line from a vote CSV fixture.
     *
     * @param userId    voter identifier
     * @param postId    target post identifier
     * @param action    vote action forwarded to {@link VoteService}
     * @param timestamp fixture timestamp string used during replay
     */
    record VoteCsvRow(String userId, String postId, String action, String timestamp) {
    }
}
