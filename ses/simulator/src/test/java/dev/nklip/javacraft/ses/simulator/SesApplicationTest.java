package dev.nklip.javacraft.ses.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SesApplicationTest {

    @Test
    public void testRunRegistersShutdownHookAndLaunchesWorkers() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        WorkerLauncher workerLauncher = mock(WorkerLauncher.class);
        when(context.getBean(WorkerLauncher.class)).thenReturn(workerLauncher);

        SesApplication.run(context);

        verify(context).registerShutdownHook();
        verify(context).getBean(WorkerLauncher.class);
        verify(workerLauncher).launch();
    }
}
