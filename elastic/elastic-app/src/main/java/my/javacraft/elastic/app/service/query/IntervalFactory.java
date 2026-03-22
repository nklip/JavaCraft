package my.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.*;
import org.springframework.stereotype.Service;

@Service
public class IntervalFactory implements QueryFactory {

    @Override
    public Query createQuery (String field, String value) {
        IntervalsMatch intervalsMatch = new IntervalsMatch.Builder()
                .query(value)
                .ordered(true)
                .maxGaps(3)
                .build();

        IntervalsQuery intervalsQuery = new IntervalsQuery.Builder()
                .field(field)
                .match(intervalsMatch)
                .build();

        // Build the search query
        return Query.of(q -> q.intervals(intervalsQuery));
    }
}
