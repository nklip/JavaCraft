package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Kubernetes API server of one scenario: an unprivileged k3s server without an agent, with its port bound to
 * 127.0.0.1. It has the namespace shardshop with the generator RBAC and retention policy of the chart, which the
 * integration build renders with helm template. The registry is created with the same fresh-cluster
 * create-then-pin sequence used by up.sh. The container stops at the end of the scenario, thus each scenario
 * starts at an empty API server.
 */
public final class KubernetesApi implements Disposable {
    public static final String NAMESPACE = "shardshop";
    public static final String REGISTRY = "shardshop-snowflake-generators";
    private static final int API_PORT = 6443;
    private static final String TLS = "/var/lib/rancher/k3s/server/tls/";
    private static final Duration READY = Duration.ofSeconds(60);

    private final Path directory;
    private GenericContainer<?> container;
    private boolean paused;
    private String registryUid;

    /** One authorization question: may the service account use the verb on ConfigMaps, or on one named ConfigMap? */
    public record Access(String account, String verb, String name) {
    }

    public KubernetesApi() throws IOException {
        directory = ShardShopFiles.newWorkDirectory("kubernetes-");
    }

    /** Starts k3s and applies the rendered generator objects. Then the retention policy denies an invalid registry. */
    @SuppressWarnings("resource")
    public void start() throws IOException, InterruptedException {
        String image = System.getProperty("tc.image.k3s");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Run the scenarios with Maven Failsafe, which sets tc.image.k3s");
        }
        // Without an agent, k3s runs no kubelet and no containerd, thus the container needs no privileges.
        container = new GenericContainer<>(DockerImageName.parse(image))
                .withCommand("server", "--disable-agent",
                        "--disable=traefik,servicelb,metrics-server,local-storage,coredns")
                .withExposedPorts(API_PORT)
                .withCreateContainerCmdModifier(command -> Objects.requireNonNull(command.getHostConfig())
                        .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(API_PORT))))
                .waitingFor(Wait.forLogMessage(".*k3s is up and running.*", 1).withStartupTimeout(READY));
        container.start();
        await("the API server is not ready", () -> exec("kubectl", "get", "--raw", "/readyz").getExitCode() == 0);
        kubectl("create", "namespace", NAMESPACE);
        container.copyFileToContainer(MountableFile.forHostPath(ShardShopFiles.generatorAllocation()),
                "/tmp/generator-allocation.yaml");
        kubectl("apply", "-f", "/tmp/generator-allocation.yaml");
        // An admission policy takes effect after a short delay. The registry must not exist before the policy works.
        await("the retention policy does not deny an invalid registry", () -> deniedByThePolicy(exec("kubectl",
                "-n", NAMESPACE, "create", "configmap", REGISTRY, "--from-literal=highWaterMark=-1",
                "--dry-run=server")));
    }

    /** Creates the fresh-cluster registry at high-water 0, then advances it if necessary. */
    public void createRegistry(int highWater) throws IOException, InterruptedException {
        registryUid = kubectl("-n", NAMESPACE, "create", "configmap", REGISTRY, "--from-literal=highWaterMark=0",
                "-o", "jsonpath={.metadata.uid}");
        if (highWater > 0) {
            kubectl("-n", NAMESPACE, "patch", "configmap", REGISTRY, "--type=merge",
                    "-p", "{\"data\":{\"highWaterMark\":\"" + highWater + "\"}}");
        }
    }

    public String registryUid() {
        if (registryUid == null) {
            throw new IllegalStateException("The scenario created no registry");
        }
        return registryUid;
    }

    public int highWater() throws IOException, InterruptedException {
        return Integer.parseInt(kubectl("-n", NAMESPACE, "get", "configmap", REGISTRY,
                "-o", "jsonpath={.data.highWaterMark}"));
    }

    /** A service account that may only get the registry, as a Role with only that rule allows. */
    public void createRegistryReader(String account) throws IOException, InterruptedException {
        kubectl("-n", NAMESPACE, "create", "serviceaccount", account);
        kubectl("-n", NAMESPACE, "create", "role", account, "--verb=get", "--resource=configmaps",
                "--resource-name=" + REGISTRY);
        kubectl("-n", NAMESPACE, "create", "rolebinding", account, "--role=" + account,
                "--serviceaccount=" + NAMESPACE + ":" + account);
    }

    /** A token of the service account for one hour, in a file that only the test user can read. */
    public Path tokenFile(String account) throws IOException, InterruptedException {
        // The service account controller creates the account default shortly after the namespace.
        await("the service account " + account + " does not exist",
                () -> exec("kubectl", "-n", NAMESPACE, "get", "serviceaccount", account).getExitCode() == 0);
        String token = kubectl("-n", NAMESPACE, "create", "token", account, "--duration=1h").strip();
        Path file = Files.createTempFile(directory, "token-" + account + "-", "",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, token, StandardCharsets.US_ASCII);
        return file;
    }

    /** The CA of the API server certificate, which a pod gets in its service account volume. */
    public Path serverCa() {
        return copy("server-ca.crt");
    }

    /** The CA of the client certificates: a real CA of the cluster, but not the CA of the server certificate. */
    public Path clientCa() {
        return copy("client-ca.crt");
    }

    public String url() {
        return url("127.0.0.1");
    }

    /** The URL of this API server with another host name, which must point to 127.0.0.1. */
    public String url(String host) {
        return "https://" + host + ":" + container.getMappedPort(API_PORT);
    }

    /** The JVM options that make product reserve its generator ID in this API server. */
    public List<String> productOptions(Path tokenFile, String uid, Path caFile) {
        return productOptions(url(), tokenFile, uid, caFile);
    }

    /** As {@link #productOptions(Path, String, Path)}, with a URL of {@link #url(String)}. */
    public List<String> productOptions(String url, Path tokenFile, String uid, Path caFile) {
        return List.of("-Dshardshop.id.registry.uid=" + uid, "-Dshardshop.id.registry.url=" + url,
                "-Dshardshop.id.registry.token-file=" + tokenFile, "-Dshardshop.id.registry.ca-file=" + caFile);
    }

    /** The decisions of the API server's authorizer, in the order of the questions, from SubjectAccessReviews. */
    public List<Boolean> allowed(List<Access> questions) throws IOException, InterruptedException {
        ObjectNode reviews = Json.newObject().put("apiVersion", "v1").put("kind", "List");
        ArrayNode items = reviews.putArray("items");
        for (Access question : questions) {
            ObjectNode spec = items.addObject().put("apiVersion", "authorization.k8s.io/v1")
                    .put("kind", "SubjectAccessReview").putObject("spec");
            spec.put("user", "system:serviceaccount:" + NAMESPACE + ":" + question.account());
            spec.putArray("groups").add("system:serviceaccounts").add("system:serviceaccounts:" + NAMESPACE)
                    .add("system:authenticated");
            ObjectNode attributes = spec.putObject("resourceAttributes").put("namespace", NAMESPACE)
                    .put("verb", question.verb()).put("resource", "configmaps");
            if (question.name() != null) {
                attributes.put("name", question.name());
            }
        }
        container.copyFileToContainer(Transferable.of(Json.bytes(reviews)), "/tmp/reviews.json");
        List<Boolean> allowed = new ArrayList<>();
        for (JsonNode review : Json.documents(kubectl("create", "-f", "/tmp/reviews.json", "-o", "json"))) {
            allowed.add(review.path("status").path("allowed").asBoolean(false));
        }
        return allowed;
    }

    /** Runs kubectl as the cluster administrator without a check of the result, for changes that must fail. */
    public ExecResult tryKubectl(String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "kubectl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        return exec(command);
    }

    /** The retention policy of the chart denied the request; RBAC or a network error is not sufficient. */
    public static boolean deniedByThePolicy(ExecResult result) {
        return result.getExitCode() != 0
                && result.getStderr().contains("ValidatingAdmissionPolicy 'shardshop-generator-retention'")
                && result.getStderr().contains("denied request");
    }

    /** Freezes the k3s processes: connections to the API port get no answer until the end of the scenario. */
    public void pause() {
        DockerClientFactory.instance().client().pauseContainerCmd(container.getContainerId()).exec();
        paused = true;
    }

    @Override
    public void dispose() {
        if (container == null) {
            return;
        }
        if (paused) {
            DockerClientFactory.instance().client().unpauseContainerCmd(container.getContainerId()).exec();
        }
        container.stop();
    }

    private String kubectl(String... arguments) throws IOException, InterruptedException {
        ExecResult result = tryKubectl(arguments);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("kubectl " + String.join(" ", arguments) + " failed: " + result.getStderr());
        }
        return result.getStdout();
    }

    private ExecResult exec(String... command) throws IOException, InterruptedException {
        return container.execInContainer(command);
    }

    private Path copy(String certificate) {
        Path file = directory.resolve(certificate);
        container.copyFileFromContainer(TLS + certificate, file.toString());
        return file;
    }

    private static void await(String failure, Probe probe) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + READY.toNanos();
        while (!probe.passes()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("After " + READY + ", " + failure);
            }
            Thread.sleep(200);
        }
    }

    @FunctionalInterface
    private interface Probe {
        boolean passes() throws IOException, InterruptedException;
    }
}
