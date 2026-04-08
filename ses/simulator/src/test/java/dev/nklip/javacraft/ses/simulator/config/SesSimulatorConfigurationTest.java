package dev.nklip.javacraft.ses.simulator.config;

import dev.nklip.javacraft.ses.events.EventNotifier;
import dev.nklip.javacraft.ses.events.EventsMonitor;
import dev.nklip.javacraft.ses.simulator.WorkerLauncher;
import dev.nklip.javacraft.ses.simulator.db.FinanceDao;
import dev.nklip.javacraft.ses.simulator.service.EventNotifierWrapper;
import dev.nklip.javacraft.ses.simulator.service.FinanceService;
import dev.nklip.javacraft.ses.simulator.service.QueueService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class SesSimulatorConfigurationTest {

    @Test
    public void testComponentScanRegistersCoreBeans() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SesSimulatorConfiguration.class)) {
            Assertions.assertNotNull(context.getBean(FinanceDao.class));
            Assertions.assertNotNull(context.getBean(FinanceService.class));
            Assertions.assertNotNull(context.getBean(QueueService.class));
            Assertions.assertNotNull(context.getBean(EventNotifier.class));
            Assertions.assertNotNull(context.getBean(EventNotifierWrapper.class));
            Assertions.assertNotNull(context.getBean(EventsMonitor.class));
            Assertions.assertNotNull(context.getBean(WorkerLauncher.class));
        }
    }
}
