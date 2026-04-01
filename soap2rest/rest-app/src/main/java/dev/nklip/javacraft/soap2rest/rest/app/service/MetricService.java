package dev.nklip.javacraft.soap2rest.rest.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import org.springframework.stereotype.Service;

@Service
public class MetricService {

    /**
     * Initial values should be sorted by date and values,
     *      i.e. each next value should have bigger value and bigger date.
     * @param metricList Metric list out of the DB with empty extra values.
     * @return Metric list with calculated values.
     */
    public List<Metric> calculateExtraFields(List<Metric> metricList) {
        Map<Long, MetricState> stateByMeterId = new HashMap<>();
        for (Metric metric : metricList) {
            MetricState meterState = stateByMeterId.get(metric.getMeterId());
            if (meterState != null) {
                metric.setUsageSinceLastRead(metric.getReading().subtract(meterState.lastReading()));
                metric.setPeriodSinceLastRead(differenceInDays(meterState.lastDate(), metric.getDate()));
                metric.setAvgDailyUsage(
                        averageDailyUsage(meterState.initialReading(), meterState.initialDate(), metric)
                );
                meterState.update(metric.getReading(), metric.getDate());
            } else {
                stateByMeterId.put(metric.getMeterId(), new MetricState(metric.getReading(), metric.getDate()));
            }
        }
        return metricList;
    }

    /**
     * Stores per-meter boundaries required for usage and average calculations.
     * Each meter must be processed independently to avoid cross-meter deltas.
     */
    private static final class MetricState {
        private final BigDecimal initialReading;
        private final Date initialDate;
        private BigDecimal lastReading;
        private Date lastDate;

        private MetricState(BigDecimal initialReading, Date initialDate) {
            this.initialReading = initialReading;
            this.initialDate = initialDate;
            this.lastReading = initialReading;
            this.lastDate = initialDate;
        }

        private BigDecimal initialReading() {
            return initialReading;
        }

        private Date initialDate() {
            return initialDate;
        }

        private BigDecimal lastReading() {
            return lastReading;
        }

        private Date lastDate() {
            return lastDate;
        }

        private void update(BigDecimal reading, Date date) {
            this.lastReading = reading;
            this.lastDate = date;
        }
    }

    private Long differenceInDays(Date lastDate, Date currDate) {
        return ChronoUnit.DAYS.between(
                LocalDate.parse(lastDate.toString()),
                LocalDate.parse(currDate.toString())
        );
    }

    private BigDecimal averageDailyUsage(
            BigDecimal initialValue, Date initialDate, Metric metric) {

        BigDecimal absoluteValueDiff = metric.getReading().subtract(initialValue);
        Long absoluteDaysDiff = differenceInDays(initialDate, metric.getDate());

        return absoluteValueDiff.divide(
                new BigDecimal(absoluteDaysDiff),
                3, // three digits after decimal point
                RoundingMode.HALF_EVEN
        );

    }
}
