package my.javacraft.elastic.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.service.activity.UserActivityService;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final ElasticsearchClient esClient;
    private final DateService dateService;

    public Long removeOldActivityRecords() {
        try {
            RangeQuery rangeQuery = RangeQuery.of(r -> r
                    .date(d -> d
                            .field(UserActivityService.TIMESTAMP)
                            .lte(dateService.getNDaysBeforeDate(UserActivityService.SIX_MONTHS))
                    )
            );
            DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest.Builder()
                    .index(UserActivityService.INDEX_USER_ACTIVITY)
                    .query(rangeQuery._toQuery())
                    .build();

            // use -Dlogging.level.tracer=TRACE to print a full curl statement
            DeleteByQueryResponse deleteByQueryResponse = esClient.deleteByQuery(deleteByQueryRequest);
            return deleteByQueryResponse.deleted();
        } catch (IOException ioe) {
            log.error("Failed to remove outdated user-activity records.", ioe);
            throw new IllegalStateException("Failed to remove outdated user-activity records.", ioe);
        }
    }
}
