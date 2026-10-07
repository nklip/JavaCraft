package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Kubernetes ConfigMap access with an atomic, resource-version-qualified JSON patch. */
public final class KubectlRegistry implements GeneratorRegistry {
    private static final String REGISTRY = "shardshop-snowflake-generators";
    private static final String OUTPUT = "go-template={{.metadata.uid}}{{\"\\t\"}}{{.metadata.resourceVersion}}"
            + "{{\"\\t\"}}{{index .data \"highWaterMark\"}}";
    private static final Pattern HIGH_WATER = Pattern.compile("0|[1-9][0-9]{0,3}");

    private final List<String> prefix;
    private final CommandRunner runner;

    public KubectlRegistry(List<String> kubectlCommandPrefix) {
        this(kubectlCommandPrefix, new ProcessCommandRunner());
    }

    KubectlRegistry(List<String> kubectlCommandPrefix, CommandRunner runner) {
        prefix = List.copyOf(kubectlCommandPrefix);
        if (prefix.isEmpty() || prefix.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("kubectl command must not be empty");
        }
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public RegistryState read(Duration timeout) throws IOException, InterruptedException {
        try {
            CommandResult result = runner.run(command(timeout, "get", "configmap", REGISTRY, "-o", OUTPUT), timeout);
            if (result.exitCode() != 0) {
                throw new IOException("Generator registry is unavailable");
            }
            return parse(result.output());
        } catch (AllocationFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new AllocationFailure(AllocationFailure.Reason.REGISTRY_READ);
        }
    }

    @Override
    public boolean reserve(RegistryState expected, int next, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(expected, "expected");
        if (next <= expected.highWaterMark() || next > 1023) {
            throw new IllegalArgumentException("Reservation must advance the live generator high-water mark");
        }
        try {
            // RegistryState permits only safe UID/version tokens, so values cannot alter the JSON structure.
            String patch = "[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":\"" + expected.uid()
                    + "\"},{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":\""
                    + expected.resourceVersion()
                    + "\"},{\"op\":\"test\",\"path\":\"/data/highWaterMark\",\"value\":\""
                    + expected.highWaterMark()
                    + "\"},{\"op\":\"replace\",\"path\":\"/data/highWaterMark\",\"value\":\"" + next + "\"}]";
            CommandResult result = runner.run(command(timeout, "patch", "configmap", REGISTRY,
                    "--type=json", "-p", patch, "-o", OUTPUT), timeout);
            if (result.exitCode() != 0) {
                return false;
            }
            RegistryState reserved = parse(result.output());
            return expected.uid().equals(reserved.uid()) && reserved.highWaterMark() == next
                    && !expected.resourceVersion().equals(reserved.resourceVersion());
        } catch (AllocationFailure failure) {
            throw failure;
        } catch (IOException uncertainOutcome) {
            return false;
        }
    }

    private List<String> command(Duration timeout, String... arguments) {
        long nanos = ProcessCommandRunner.timeoutNanos(timeout);
        List<String> command = new ArrayList<>(prefix);
        command.add("--request-timeout=" + nanos + "ns");
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private static RegistryState parse(String output) throws IOException {
        String[] fields = output.split("\t", -1);
        if (fields.length != 3 || !HIGH_WATER.matcher(fields[2]).matches()) {
            throw new IOException("Generator registry contains invalid state");
        }
        try {
            return new RegistryState(fields[0], fields[1], Integer.parseInt(fields[2]));
        } catch (IllegalArgumentException invalidState) {
            throw new IOException("Generator registry contains invalid state");
        }
    }
}

@FunctionalInterface
interface CommandRunner {
    CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
}

record CommandResult(int exitCode, String output) {
}
