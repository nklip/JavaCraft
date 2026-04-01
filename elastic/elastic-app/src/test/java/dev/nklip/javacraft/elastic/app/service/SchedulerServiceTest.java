package dev.nklip.javacraft.elastic.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import java.io.IOException;
import dev.nklip.javacraft.elastic.app.config.ElasticsearchConstants;
import dev.nklip.javacraft.elastic.app.config.SchedulerDefaults;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private DateService dateService;

    @Test
    void testRemoveOldUserVotesShouldReturnDeletedCount() throws IOException {
        SchedulerService schedulerService = new SchedulerService(esClient, dateService);
        DeleteByQueryResponse response = new DeleteByQueryResponse.Builder().deleted(42L).build();

        when(dateService.getNDaysBeforeDate(SchedulerDefaults.RETENTION_DAYS)).thenReturn("2024-01-01T00:00:00Z");
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(response);

        Long deleted = schedulerService.removeOldUserVotes();

        Assertions.assertEquals(42L, deleted);
        ArgumentCaptor<DeleteByQueryRequest> requestCaptor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient).deleteByQuery(requestCaptor.capture());
        Assertions.assertNotNull(requestCaptor.getValue());
        Assertions.assertEquals(ElasticsearchConstants.INDEX_USER_VOTES, requestCaptor.getValue().index().getFirst());
        Assertions.assertNotNull(requestCaptor.getValue().query());
    }

    @Test
    void testRemoveOldUserVotesShouldThrowWhenDeleteByQueryFails() throws IOException {
        SchedulerService schedulerService = new SchedulerService(esClient, dateService);

        when(dateService.getNDaysBeforeDate(SchedulerDefaults.RETENTION_DAYS)).thenReturn("2024-01-01T00:00:00Z");
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenThrow(new IOException("cluster unavailable"));

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                schedulerService::removeOldUserVotes
        );

        Assertions.assertEquals("Failed to remove outdated user-votes records.", exception.getMessage());
        Assertions.assertNotNull(exception.getCause());
        Assertions.assertEquals("cluster unavailable", exception.getCause().getMessage());
    }
}
