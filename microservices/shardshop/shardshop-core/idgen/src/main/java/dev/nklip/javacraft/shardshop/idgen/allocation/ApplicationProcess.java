package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Supervises one application JVM, including termination when its launcher shuts down. */
final class ApplicationProcess {

    private final ProcessStarter starter;
    private final Consumer<Thread> registerShutdown;
    private final Predicate<Thread> removeShutdown;

    ApplicationProcess() {
        this(ProcessBuilder::start, Runtime.getRuntime()::addShutdownHook, Runtime.getRuntime()::removeShutdownHook);
    }

    ApplicationProcess(ProcessStarter starter, Consumer<Thread> registerShutdown, Predicate<Thread> removeShutdown) {
        this.starter = starter;
        this.registerShutdown = registerShutdown;
        this.removeShutdown = removeShutdown;
    }

    int run(List<String> command, Map<String, String> environment) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.environment().clear();
        builder.environment().putAll(environment);
        Child child = new Child();
        Thread shutdown = new Thread(child::stop, "shardshop-application-shutdown");
        registerShutdown.accept(shutdown);
        try {
            return child.start(builder).waitFor();
        } catch (InterruptedException failure) {
            child.stop();
            throw failure;
        } finally {
            try {
                removeShutdown.test(shutdown);
            } catch (IllegalStateException shutdownStarted) {
                child.stop();
            }
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    private final class Child {
        private Process process;
        private boolean stopping;

        synchronized Process start(ProcessBuilder builder) throws IOException {
            if (stopping) {
                throw new IOException("Application startup cancelled");
            }
            process = starter.start(builder);
            return process;
        }

        synchronized void stop() {
            stopping = true;
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException failure) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
