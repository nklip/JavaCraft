package my.javacraft.elastic.app.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

@Service
public class DateService {

    private final DateTimeFormatter isoInstant;

    public DateService() {
        this.isoInstant = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendInstant(3)
                .toFormatter();
    }

    /**
     * @return 2024-01-08T18:16:41.531Z
     */
    public String getCurrentDate() {
        return isoInstant.format(Instant.now());
    }

    public String getNDaysBeforeDate(int n) {
        return isoInstant.format(Instant.now().minus(n, ChronoUnit.DAYS));
    }

    public String getNHoursBeforeDate(int n) {
        return isoInstant.format(Instant.now().minus(n, ChronoUnit.HOURS));
    }

}
