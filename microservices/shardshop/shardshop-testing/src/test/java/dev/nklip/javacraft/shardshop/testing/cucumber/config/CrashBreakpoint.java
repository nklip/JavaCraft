package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only line barrier in a packaged JVM. The VM stays suspended until it is forcibly killed. */
public final class CrashBreakpoint implements AutoCloseable {
    private final String className;
    private final int line;
    private final String lineMethod;
    private final int port = CatalogDatabase.freePort();
    private final CountDownLatch hit = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile VirtualMachine machine;
    private volatile Thread events;

    private CrashBreakpoint(String className, int line, String lineMethod) {
        this.className = className;
        this.line = line;
        this.lineMethod = lineMethod;
    }

    /**
     * Resolve a unique sequence of complete source lines; stop before its final executable line.
     * Context lines disambiguate repeated statements and method-closing braces without fixed offsets.
     * Blank lines and standalone // comments may move without changing the boundary.
     */
    public static CrashBreakpoint atSourceAnchor(String module, String className, String method,
                                                 String... sourceAnchor) throws IOException {
        String sourceClass = className.split("\\$")[0];
        Path source = ShardShopFiles.resolve(module + "/src/main/java/" + sourceClass.replace('.', '/') + ".java");
        return new CrashBreakpoint(className, resolveSourceLine(source, sourceAnchor), method);
    }

    static int resolveSourceLine(Path source, String... sourceAnchor) throws IOException {
        if (sourceAnchor.length == 0 || java.util.Arrays.stream(sourceAnchor).anyMatch(String::isBlank))
            throw new IOException("Empty crash source anchor in " + source);
        List<String> lines = Files.readAllLines(source);
        List<Integer> statements = java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> !lines.get(index).isBlank() && !lines.get(index).stripLeading().startsWith("//"))
                .boxed().toList();
        int resolved = -1;
        for (int start = 0; start + sourceAnchor.length <= statements.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < sourceAnchor.length; offset++) {
                if (!lines.get(statements.get(start + offset)).trim().equals(sourceAnchor[offset].trim())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (resolved != -1) throw new IOException("Ambiguous crash source anchor in " + source
                        + ": " + List.of(sourceAnchor));
                resolved = statements.get(start + sourceAnchor.length - 1) + 1;
            }
        }
        if (resolved == -1) throw new IOException("Missing crash source anchor in " + source
                + ": " + List.of(sourceAnchor));
        return resolved;
    }

    public List<String> options() {
        return List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port);
    }

    /** Attach before startup resumes, so an early worker cannot pass the selected boundary. */
    public void attach() throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow(() -> new IllegalStateException("JDI socket connector unavailable"));
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(Integer.toString(port));
        args.get("timeout").setValue("1000");
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                machine = connector.attach(args);
                break;
            } catch (IOException unavailable) {
                last = unavailable;
                Thread.sleep(25);
            }
        }
        if (machine == null) throw new IOException("Packaged debug JVM did not listen on loopback", last);
        ClassPrepareRequest prepare = machine.eventRequestManager().createClassPrepareRequest();
        prepare.addClassFilter(className);
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
        for (ReferenceType loaded : machine.classesByName(className)) install(loaded);
        events = Thread.ofPlatform().daemon().name("packaged-crash-breakpoint").start(this::pump);
        machine.resume();
    }

    private void install(ReferenceType type) {
        try {
            var locations = type.locationsOfLine(line);
            if (lineMethod != null) locations = locations.stream()
                    .filter(location -> location.method().name().equals(lineMethod)).toList();
            if (locations.isEmpty()) throw new IllegalStateException("No executable bytecode at " + className + ':' + line);
            BreakpointRequest breakpoint = machine.eventRequestManager().createBreakpointRequest(locations.getFirst());
            breakpoint.addCountFilter(1);
            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            breakpoint.enable();
        } catch (Exception failed) {
            failure.compareAndSet(null, failed);
            hit.countDown();
        }
    }

    private void pump() {
        VirtualMachine active = machine;
        try {
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                EventSet set = active.eventQueue().remove();
                boolean matched = false;
                for (Event event : set) {
                    if (event instanceof ClassPrepareEvent prepared) install(prepared.referenceType());
                    if (event instanceof BreakpointEvent) matched = true;
                }
                if (matched) {
                    hit.countDown();
                    return;
                }
                set.resume();
            }
        } catch (Exception stopped) {
            if (!closed.get()) {
                failure.compareAndSet(null, stopped);
                hit.countDown();
            }
        }
    }

    public void awaitHit(PackagedApplication process, Duration timeout) throws Exception {
        if (!hit.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
            throw new AssertionError("No crash breakpoint at " + className + ':' + line + ":\n" + process.tail());
        if (failure.get() != null) throw new AssertionError("Crash breakpoint failed", failure.get());
    }

    public void killAtHit(PackagedApplication process, Duration timeout) throws Exception {
        awaitHit(process, timeout);
        process.kill();
        close();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        VirtualMachine current = machine;
        machine = null;
        Thread pump = events;
        if (pump != null) pump.interrupt();
        if (current != null) {
            try { current.dispose(); } catch (RuntimeException ignored) { /* the killed VM already disconnected */ }
        }
    }
}
