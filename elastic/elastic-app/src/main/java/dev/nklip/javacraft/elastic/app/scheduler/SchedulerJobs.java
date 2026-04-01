package dev.nklip.javacraft.elastic.app.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.elastic.app.service.SchedulerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Cron expression: second, minute, hour, day of month, month, day(s) of week
 * ┏━━━━━━━━━━━━ Second (0-59)
 * ┃ ┏━━━━━━━━━━ Minute (0-59)
 * ┃ ┃ ┏━━━━━━━━ Hour (0-23)
 * ┃ ┃ ┃ ┏━━━━━━ Day of month (1-31)
 * ┃ ┃ ┃ ┃ ┏━━━━ Month (1-12)
 * ┃ ┃ ┃ ┃ ┃ ┏━━ Day(s) of week (1-7, Monday to Sunday)
 * * * * * * *
 * <p/>
 * Examples:
 * 0 0    *    *  *  *      - the top of every hour of every day
 * 0 0    8-10 *  *  *      - 8,9,10 o'clock of every day
 * 0 0    8,10 *  *  *      - 8 and 10 o'clock of every day
 * 0 0/30 8-10 *  *  *      - 8:00, 8:30, 9:00, and 10 o'clock every day
 * 0 0    9-17 *  * MON-FRI - on the hour nine-to-five weekdays
 * 0 0    0    25 12 ?      - every Christmas Day at midnight
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.enabled", matchIfMissing = true)
public class SchedulerJobs {

    private final SchedulerService schedulerService;

    /**
     * The first 0 represents the second at which the task will run (0th second).
     * The second 0 represents the minute at which the task will run (0th minute).
     * The * in the other positions represents any value, so it will run every hour regardless of the day of the month, month, day of the week, or year.
     */
    @Scheduled(cron = "0 0 * * * *") // Runs at the top of every hour
    public void cleanUpUserVotes() {
        log.info("cleaning up user votes..");

        log.info("removed old user votes = '{}'",
                schedulerService.removeOldUserVotes()
        );
    }
}
