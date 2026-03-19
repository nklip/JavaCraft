package my.javacraft.elastic.scheduler;

import my.javacraft.elastic.service.SchedulerService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class SchedulerJobsTest {

    @Test
    public void testCleanUpUserVotes() {
        SchedulerService schedulerService = Mockito.mock(SchedulerService.class);
        SchedulerJobs schedulerJobs = new SchedulerJobs(schedulerService);

        Mockito.when(schedulerService.removeOldUserVotes()).thenReturn(42L);

        schedulerJobs.cleanUpUserVotes();

        Mockito.verify(
                schedulerService,
                Mockito.atLeast(1)
        ).removeOldUserVotes();

    }
}
