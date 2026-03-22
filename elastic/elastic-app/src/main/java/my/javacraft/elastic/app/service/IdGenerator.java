package my.javacraft.elastic.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.ExistsRequest;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.app.config.ElasticsearchConstants;
import org.springframework.stereotype.Component;

/**
 * Generates unique 6-character alphanumeric post IDs (Reddit-style base-36).
 *
 * <p>ID space: {@code [a-z0-9]^6} = 36^6 ≈ 2.18 billion values.
 *
 * <p>Each candidate is checked against the {@code posts} index before being returned.
 * Collisions are astronomically rare in practice; retrying on the off-chance one
 * occurs prevents silent data loss from overwriting an existing document.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdGenerator {

    /** Characters used for ID generation: lowercase letters + digits (base-36). */
    static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** Length of a generated ID — gives 36^6 ≈ 2.18 billion unique values. */
    static final int ID_LENGTH = 6;

    private final ElasticsearchClient esClient;

    /**
     * Returns a post ID that is guaranteed not to exist in the {@code posts} index.
     * Retries automatically on collision, logging a WARN each time.
     */
    public String generateUniquePostId() throws IOException {
        while (true) {
            String candidate = randomId();

            ExistsRequest existsRequest = new ExistsRequest.Builder()
                    .index(ElasticsearchConstants.INDEX_POSTS)
                    .id(candidate)
                    .build();
            boolean taken = esClient.exists(existsRequest).value();
            if (!taken) {
                return candidate;
            }
            log.warn("postId collision on '{}', retrying", candidate);
        }
    }

    static String randomId() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_ALPHABET.charAt(rng.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
