package my.javacraft.elastic.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdGeneratorTest {

    @Mock
    ElasticsearchClient esClient;

    private IdGenerator generator() {
        return new IdGenerator(esClient);
    }

    @Test
    void randomIdHasCorrectLengthAndAlphabet() {
        for (int i = 0; i < 1_000; i++) {
            String id = IdGenerator.randomId();
            Assertions.assertEquals(IdGenerator.ID_LENGTH, id.length(),
                    "ID must be exactly " + IdGenerator.ID_LENGTH + " characters");
            Assertions.assertTrue(id.matches("[a-z0-9]+"),
                    "ID must only contain lowercase letters and digits: " + id);
        }
    }

    @Test
    void generateUniquePostIdReturnsIdWhenNoCollision() throws IOException {
        when(esClient.exists(any(ExistsRequest.class))).thenReturn(new BooleanResponse(false));

        String id = generator().generateUniquePostId();

        Assertions.assertTrue(id.matches("[a-z0-9]{6}"),
                "generated ID must be 6-char alphanumeric: " + id);
        verify(esClient, times(1)).exists(any(ExistsRequest.class));
    }

    @Test
    void generateUniquePostIdRetriesUntilFreeIdFound() throws IOException {
        // first two candidates collide, third is free
        when(esClient.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(true))
                .thenReturn(new BooleanResponse(true))
                .thenReturn(new BooleanResponse(false));

        String id = generator().generateUniquePostId();

        Assertions.assertTrue(id.matches("[a-z0-9]{6}"),
                "returned ID must still be valid after retries: " + id);
        verify(esClient, times(3)).exists(any(ExistsRequest.class));
    }
}
