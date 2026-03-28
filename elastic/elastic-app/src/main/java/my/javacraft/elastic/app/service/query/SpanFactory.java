package my.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.SpanNearQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.SpanQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.SpanTermQuery;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import my.javacraft.elastic.app.config.SearchProperties;
import my.javacraft.elastic.app.service.SearchService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpanFactory implements QueryFactory {

    private final SearchProperties searchProperties;

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
                .slop(searchProperties.span().slop())
                // Controls whether matches are required to be in-order.
                .inOrder(true)
                .clauses(spanQueries)
                .build()
                ._toQuery();
    }
}
