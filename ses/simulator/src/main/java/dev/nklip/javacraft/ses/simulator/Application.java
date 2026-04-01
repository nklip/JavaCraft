package dev.nklip.javacraft.ses.simulator;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Created by nikilipa on 7/23/16.
 */
public class Application {

    public static final String CONTEXT = "application.xml";

    public static void main(String[] args) {
        ApplicationContext context = new ClassPathXmlApplicationContext(CONTEXT);

        WorkerLauncher launcher = context.getBean(WorkerLauncher.class);
        launcher.launch();
    }
}
