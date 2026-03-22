package my.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.*;
import java.util.List;
import my.javacraft.elastic.app.service.SearchService;
import org.springframework.stereotype.Service;

@Service
public class WildcardFactory implements QueryFactory {

    @Override
    public Query createQuery(String field, String value) {
        Query wildcardQuery = new WildcardQuery.Builder()
                .boost(SearchService.NEUTRAL_VALUE)
                .field(field)
                .wildcard(value)
                .build()
                ._toQuery();

        Query simpleQuery = new SimpleQueryStringQuery.Builder()
                .boost(SearchService.NEUTRAL_VALUE)
                .analyzeWildcard(true) // if true, the query attempts to analyze wildcard terms in the query string.
                .defaultOperator(Operator.And)
                .fields(field)
                .query(value)
                .build()
                ._toQuery();

        return new BoolQuery.Builder()
                .should(List.of(wildcardQuery, simpleQuery))
                .build()
                ._toQuery();
    }
}
