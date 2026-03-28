package my.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import lombok.RequiredArgsConstructor;
import my.javacraft.elastic.app.config.SearchProperties;
import my.javacraft.elastic.app.service.SearchService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FuzzyFactory implements QueryFactory {

    private final SearchProperties searchProperties;

    @Override
    public Query createQuery(String field, String value) {
        return new MatchQuery.Builder()
                .boost(SearchService.NEUTRAL_VALUE)
                // Maximum edit distance allowed for matching.
                .fuzziness(searchProperties.fuzzy().fuzziness())
                // If true, edits for fuzzy matching include transpositions of two adjacent characters (for example, ab to ba).
                .fuzzyTranspositions(true)
                .operator(Operator.And)
                .field(field)
                .query(value)
                .build()
                ._toQuery();
    }
}
