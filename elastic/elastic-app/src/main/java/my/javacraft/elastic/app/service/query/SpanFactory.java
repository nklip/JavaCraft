package my.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.SpanNearQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.SpanQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.SpanTermQuery;
import java.util.ArrayList;
import java.util.List;
import my.javacraft.elastic.app.service.SearchService;
import org.springframework.stereotype.Service;

@Service
public class SpanFactory implements QueryFactory {

    @Override
    public Query createQuery(String field, String value) {
        List<SpanQuery> spanQueries = new ArrayList<>();
        String[] searchTokens = value.split(" ", -1);
        for (String token : searchTokens) {
            spanQueries.add(new SpanQuery.Builder()
                    .spanTerm(
                            new SpanTermQuery.Builder()
                                    .boost(SearchService.NEUTRAL_VALUE)
                                    .field(field)
                                    .value(token)
                                    .build()
                    ).build()
            );
        }

        return new SpanNearQuery.Builder()
                .boost(SearchService.NEUTRAL_VALUE)
                // Controls the maximum number of intervening unmatched positions permitted.
                .slop(3)
                // Controls whether matches are required to be in-order.
                .inOrder(true)
                .clauses(spanQueries)
                .build()
                ._toQuery();
    }
}
