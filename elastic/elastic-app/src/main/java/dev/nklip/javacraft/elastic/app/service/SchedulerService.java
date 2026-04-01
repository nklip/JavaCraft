package dev.nklip.javacraft.elastic.app.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.elastic.app.config.ElasticsearchConstants;
import dev.nklip.javacraft.elastic.app.config.SchedulerDefaults;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final ElasticsearchClient esClient;
    private final DateService dateService;

    public Long removeOldUserVotes() {
        try {
            RangeQuery rangeQuery = RangeQuery.of(r -> r
                    .date(d -> d
                            .field(ElasticsearchConstants.TIMESTAMP)
                            .lte(dateService.getNDaysBeforeDate(SchedulerDefaults.RETENTION_DAYS))
                    )
            );
            DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest.Builder()
                    .index(ElasticsearchConstants.INDEX_USER_VOTES)
                    .query(rangeQuery._toQuery())
                    .build();

            // use -Dlogging.level.tracer=TRACE to print a full curl statement
            DeleteByQueryResponse deleteByQueryResponse = esClient.deleteByQuery(deleteByQueryRequest);
            return deleteByQueryResponse.deleted();
        } catch (IOException ioe) {
            log.error("Failed to remove outdated user-votes records.", ioe);
            throw new IllegalStateException("Failed to remove outdated user-votes records.", ioe);
        }
    }
}
