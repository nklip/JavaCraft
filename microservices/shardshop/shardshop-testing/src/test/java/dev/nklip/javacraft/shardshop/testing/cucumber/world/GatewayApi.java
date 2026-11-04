package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A Kubernetes API server with the CRDs of the vendored Envoy Gateway chart: an unprivileged k3s server without an
 * agent, as in {@link KubernetesApi}. It runs no controller, thus it checks the schemas and the validation rules of
 * the webpage resources, not their behavior.
 */
public final class GatewayApi implements Disposable {
    public static final String CHART = "infra/helm/envoy-gateway/gateway-helm-v1.9.3.tgz";
    private static final int API_PORT = 6443;
    private static final Duration READY = Duration.ofSeconds(60);

    private final Path directory;
    private GenericContainer<?> container;

    public GatewayApi() throws IOException {
        directory = ShardShopFiles.newWorkDirectory("gateway-api-");
    }

    public Path directory() {
        return directory;
    }

    /** Starts k3s and installs the Gateway API and Envoy Gateway CRDs of the vendored chart. */
    @SuppressWarnings("resource")
    public void start(Path source) throws IOException, InterruptedException {
        String image = System.getProperty("tc.image.k3s");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Run the scenarios with Maven Failsafe, which sets tc.image.k3s");
        }
        container = new GenericContainer<>(DockerImageName.parse(image))
                .withCommand("server", "--disable-agent", "--disable=traefik,servicelb,metrics-server,local-storage,coredns")
                .withExposedPorts(API_PORT)
                .withCreateContainerCmdModifier(command -> Objects.requireNonNull(command.getHostConfig())
                        .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(API_PORT))))
                .waitingFor(Wait.forLogMessage(".*k3s is up and running.*", 1).withStartupTimeout(READY));
        container.start();
        await(() -> exec("kubectl", "get", "--raw", "/readyz").getExitCode() == 0);
        // The chart keeps the CRDs in its subchart. Argo CD renders them with --include-crds, as here.
        String chart = helm("template", "envoy-gateway", source.resolve(CHART).toString(), "--namespace", "envoy-gateway-system",
                "--include-crds");
        Pattern crd = Pattern.compile("(?m)^kind: CustomResourceDefinition$");
        List<String> crds = Arrays.stream(chart.split("\n---\n")).filter(document -> crd.matcher(document).find()).toList();
        if (crds.size() < 2) throw new IllegalStateException("The vendored chart has no Gateway API and Envoy Gateway CRDs");
        copy(String.join("\n---\n", crds), "/tmp/crds.yaml");
        kubectl("apply", "--server-side", "-f", "/tmp/crds.yaml");
        kubectl("wait", "--for=condition=Established", "crd", "--all", "--timeout=60s");
        kubectl("create", "namespace", "shardshop");
    }

    /** A server-side dry run of the manifest: the API server validates it as for a real apply and stores nothing. */
    public ExecResult dryRun(String manifest) throws IOException, InterruptedException {
        copy(manifest, "/tmp/dry-run.yaml");
        return exec("kubectl", "apply", "--dry-run=server", "-f", "/tmp/dry-run.yaml");
    }

    @Override
    public void dispose() {
        if (container != null) container.stop();
    }

    private void copy(String content, String path) {
        container.copyFileToContainer(Transferable.of(content.getBytes(StandardCharsets.UTF_8)), path);
    }

    private void kubectl(String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "kubectl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        ExecResult result = exec(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + result.getStderr());
        }
    }

    private ExecResult exec(String... command) throws IOException, InterruptedException {
        return container.execInContainer(command);
    }

    private String helm(String... arguments) throws IOException, InterruptedException {
        Path output = Files.createTempFile(directory, "helm", ".out");
        String[] command = new String[arguments.length + 1];
        command[0] = "helm";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("helm exceeded its deadline");
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException("helm failed: " + text);
            return text;
        } finally {
            process.destroyForcibly();
        }
    }

    private static void await(Probe probe) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + READY.toNanos();
        while (!probe.passes()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("After " + READY + ", the API server is not ready");
            Thread.sleep(200);
        }
    }

    @FunctionalInterface
    private interface Probe {
        boolean passes() throws IOException, InterruptedException;
    }
}
