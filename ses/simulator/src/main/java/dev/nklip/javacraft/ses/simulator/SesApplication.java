package dev.nklip.javacraft.ses.simulator;

import dev.nklip.javacraft.ses.simulator.config.SesSimulatorConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Command-line entry point for the simulator module.
 *
 * <p>This class is intentionally tiny. Its only job is to bootstrap the Spring context,
 * fetch the top-level orchestrator ({@link WorkerLauncher}), and start the simulator.
 * All real runtime behavior lives below this entry point.
 *
 * <p>Sequence:
 * <pre>{@code
 * main(args)
 *     -> new AnnotationConfigApplicationContext(SesSimulatorConfiguration)
 *     -> run(context)
 *     -> context.registerShutdownHook()
 *     -> context.getBean(WorkerLauncher)
 *     -> workerLauncher.launch()
 * }</pre>
 */
public class SesApplication {

    @SuppressWarnings("unused")
    static void main(String[] args) {
        run(new AnnotationConfigApplicationContext(SesSimulatorConfiguration.class));
    }

    static void run(ConfigurableApplicationContext context) {
        context.registerShutdownHook();

        WorkerLauncher launcher = context.getBean(WorkerLauncher.class);
        launcher.launch();
    }
}
