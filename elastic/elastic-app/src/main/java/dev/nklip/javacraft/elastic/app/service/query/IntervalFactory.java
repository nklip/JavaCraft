package dev.nklip.javacraft.elastic.app.service.query;

import co.elastic.clients.elasticsearch._types.query_dsl.*;
import lombok.RequiredArgsConstructor;
import dev.nklip.javacraft.elastic.app.config.SearchProperties;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IntervalFactory implements QueryFactory {

    private final SearchProperties searchProperties;

    @Override
    public Query createQuery(String field, String value) {
        IntervalsMatch intervalsMatch = new IntervalsMatch.Builder()
                .query(value)
                .ordered(true)
                // Maximum number of intervening unmatched positions permitted between interval terms.
                .maxGaps(searchProperties.interval().maxGaps())
                .build();

        IntervalsQuery intervalsQuery = new IntervalsQuery.Builder()
                .field(field)
                .match(intervalsMatch)
                .build();

        return Query.of(q -> q.intervals(intervalsQuery));
    }
}
