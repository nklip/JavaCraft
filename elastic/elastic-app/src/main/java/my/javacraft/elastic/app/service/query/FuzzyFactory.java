package my.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import my.javacraft.elastic.app.service.SearchService;
import org.springframework.stereotype.Service;

@Service
public class FuzzyFactory implements QueryFactory {

    @Override
    public Query createQuery(String field, String value) {
        return new MatchQuery.Builder()
                .boost(SearchService.NEUTRAL_VALUE)
                // Maximum edit distance allowed for matching.
                .fuzziness("2")
                // If true, edits for fuzzy matching include transpositions of two adjacent characters (for example, ab to ba).
                .fuzzyTranspositions(true)
                .operator(Operator.And)
                .field(field)
                .query(value)
                .build()
                ._toQuery();
    }

}
