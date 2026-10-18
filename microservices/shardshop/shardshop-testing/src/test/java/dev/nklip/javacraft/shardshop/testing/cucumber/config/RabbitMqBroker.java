package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.picocontainer.Disposable;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ResourceReaper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The chart's broker configuration and startup command, on retained storage with an arbitrary non-image UID. */
public final class RabbitMqBroker implements Disposable {
    private static final String FILES = "infra/helm/shardshop/files/rabbitmq/";
    private static final String HOSTNAME = "shardshop-rabbitmq-0";
    private static final String DOMAIN = "shardshop-rabbitmq-headless.shardshop.svc.cluster.local";
    private static final Duration READY = Duration.ofSeconds(90);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, String> passwords = Map.of("admin", UUID.randomUUID().toString(),
            "order", UUID.randomUUID().toString(), "ledger", UUID.randomUUID().toString());
    private final String cookie = UUID.randomUUID().toString().replace("-", "");
    private GenericContainer<?> container;
    private String volume;
    private Path users;
    private Path directory;
    private Map<String, Object> specification;

    public void start() throws IOException, InterruptedException {
        specification = brokerSpecification();
        users = createUsers();
        prepareProjection();
        Map<String, String> labels = Map.of("shardshop.rabbitmq.acceptance", UUID.randomUUID().toString());
        ResourceReaper.instance().registerLabelsFilterForCleanup(labels);
        volume = DockerClientFactory.instance().client().createVolumeCmd().withLabels(labels)
                .withName("shardshop-rabbitmq-test-" + UUID.randomUUID()).exec().getName();
        initializeVolume();
        startContainer();
    }

    /** Container replacement models a Pod replacement retaining its PVC, rather than merely restarting a process. */
    public void replaceContainer() throws IOException, InterruptedException {
        container.stop();
        startContainer();
    }

    public Connection connect(String role) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(container.getMappedPort(5672));
        factory.setVirtualHost("shardshop");
        factory.setUsername("shardshop-" + role);
        factory.setPassword(passwords.get(role));
        factory.setConnectionTimeout(5_000);
        factory.setHandshakeTimeout(5_000);
        factory.setChannelRpcTimeout(5_000);
        factory.setShutdownTimeout(1_000);
        factory.setRequestedHeartbeat(5);
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection("shardshop-broker-acceptance");
    }

    public int port() {
        return container.getMappedPort(5672);
    }

    public String orderPassword() {
        return passwords.get("order");
    }

    public JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = request(path, "admin", passwords.get("admin"));
        if (response.statusCode() != 200) {
            throw new AssertionError("RabbitMQ management GET " + path + " returned " + response.statusCode());
        }
        return Json.parse(response.body());
    }

    public int managementStatus(String path, String role) throws IOException, InterruptedException {
        return request(path, role, passwords.get(role)).statusCode();
    }

    public int guestStatus() throws IOException, InterruptedException {
        return request("/api/overview", "guest", "guest").statusCode();
    }

    public JsonNode queue(String name) throws IOException, InterruptedException {
        return get("/api/queues/shardshop/" + name);
    }

    public void assertReadiness() throws IOException, InterruptedException {
        Map<String, Object> readiness = map(specification.get("readinessProbe"));
        List<String> command = strings(map(readiness.get("exec")).get("command"));
        var result = container.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new AssertionError("The chart's RabbitMQ readiness probe failed: " + result.getStderr());
        }
    }

    public static void await(String requirement, Duration timeout, Check check) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!check.satisfied()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("RabbitMQ did not satisfy within " + timeout + ": " + requirement);
            }
            Thread.sleep(50);
        }
    }

    @FunctionalInterface
    public interface Check {
        boolean satisfied() throws IOException, InterruptedException;
    }

    private HttpResponse<byte[]> request(String path, String user, String password) throws IOException, InterruptedException {
        String login = user.startsWith("shardshop-") || user.equals("guest") ? user : "shardshop-" + user;
        String authorization = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + container.getMappedPort(15672) + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Basic " + authorization).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private Path createUsers() throws IOException, InterruptedException {
        directory = ShardShopFiles.newWorkDirectory("rabbitmq-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        for (var entry : passwords.entrySet()) {
            Path secret = directory.resolve(entry.getKey());
            Files.writeString(secret, entry.getValue());
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        }
        Path output = directory.resolve("users.json");
        users = output;
        Process process = new ProcessBuilder("jq", "-n", "--rawfile", "admin", directory.resolve("admin").toString(),
                "--rawfile", "order", directory.resolve("order").toString(), "--rawfile", "ledger",
                directory.resolve("ledger").toString(), "-f", ShardShopFiles.resolve(FILES + "users.jq").toString())
                .redirectOutput(output.toFile()).redirectError(directory.resolve("jq.stderr").toFile()).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("RabbitMQ test user definitions generation timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("RabbitMQ test user definitions generation failed");
        }
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
        return output;
    }

    private void prepareProjection() throws IOException {
        Path definitions = Files.createDirectories(directory.resolve("definitions"));
        Path metadata = Files.createDirectories(definitions.resolve("..2026-acceptance"));
        Path credentials = Files.createDirectories(directory.resolve("credentials"));
        for (Path readable : List.of(definitions, metadata, credentials)) {
            Files.setPosixFilePermissions(readable, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Files.copy(ShardShopFiles.resolve(FILES + "definitions.json"), definitions.resolve("10-topology.json"));
        Path mountedUsers = Files.copy(users, definitions.resolve("20-users.json"));
        Path mountedCookie = Files.writeString(credentials.resolve("erlang-cookie"), cookie);
        // The parent directory is private on the host; read-only bind mounts model Kubernetes projections.
        for (Path readable : List.of(mountedUsers, mountedCookie)) {
            Files.setPosixFilePermissions(readable, PosixFilePermissions.fromString("r--r--r--"));
        }
        Files.writeString(metadata.resolve("metadata"), "projected-volume metadata");
    }

    private void initializeVolume() {
        // Kubernetes applies fsGroup to a fresh PVC; Docker named volumes need the same one-time ownership setup.
        try (GenericContainer<?> initializer = new GenericContainer<>(DockerImageName.parse(image()))
                .withCreateContainerCmdModifier(command -> {
                    command.withUser("0:0").withEntrypoint("/bin/sh");
                    Objects.requireNonNull(command.getHostConfig()).withBinds(dataBind());
                })
                .withCommand("-ec", "chown 0:10001 /var/lib/rabbitmq; chmod 2770 /var/lib/rabbitmq")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(20)))) {
            initializer.start();
        }
    }

    @SuppressWarnings("resource")
    private void startContainer() throws IOException, InterruptedException {
        List<String> command = strings(specification.get("command"));
        container = new GenericContainer<>(DockerImageName.parse(image()))
                .withExposedPorts(5672, 15672)
                .withCreateContainerCmdModifier(create -> {
                    create.withUser("1000780001:10001").withHostName(HOSTNAME).withDomainName(DOMAIN)
                            .withEntrypoint(command.toArray(String[]::new));
                    Objects.requireNonNull(create.getHostConfig()).withBinds(dataBind(),
                                    new Bind(ShardShopFiles.resolve(FILES).toString(),
                                            new Volume("/etc/rabbitmq/config"), AccessMode.ro),
                                    new Bind(directory.resolve("definitions").toString(),
                                            new Volume("/etc/rabbitmq/definitions"), AccessMode.ro),
                                    new Bind(directory.resolve("credentials").toString(),
                                            new Volume("/etc/rabbitmq/credentials"), AccessMode.ro))
                            .withReadonlyRootfs(true)
                            .withCapDrop(Capability.ALL).withSecurityOpts(List.of("no-new-privileges"))
                            .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,mode=1777"))
                            .withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(5672)),
                                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(15672)));
                })
                .waitingFor(Wait.forListeningPort().withStartupTimeout(READY));
        for (Object item : list(specification.get("env"))) {
            Map<String, Object> variable = map(item);
            container.withEnv(variable.get("name").toString(), variable.get("value").toString());
        }
        container.start();
        await("management and imported topology are ready", READY, () -> {
            try {
                JsonNode queues = get("/api/queues/shardshop");
                if (queues.size() != 4) {
                    return false;
                }
                for (JsonNode queue : queues) {
                    if (!queue.path("effective_policy_definition").isObject()
                            || queue.path("effective_policy_definition").isEmpty()) {
                        return false;
                    }
                }
                return true;
            } catch (IOException | AssertionError starting) {
                return false;
            }
        });
        assertReadiness();
        var identity = container.execInContainer("id", "-u");
        if (identity.getExitCode() != 0 || !identity.getStdout().strip().equals("1000780001")) {
            throw new AssertionError("RabbitMQ must run as the arbitrary test UID");
        }
        var passwd = container.execInContainer("awk", "-F:",
                "$3 == 1000780001 { found = 1 } END { exit found ? 1 : 0 }", "/etc/passwd");
        if (passwd.getExitCode() != 0) {
            throw new AssertionError("The arbitrary test UID must have no image passwd entry");
        }
    }

    private Bind dataBind() {
        return new Bind(volume, new Volume("/var/lib/rabbitmq"));
    }

    private String image() {
        String image = System.getProperty("tc.image.rabbitmq");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Run the scenarios with Maven Failsafe, which sets tc.image.rabbitmq");
        }
        String chartImage = specification.get("image").toString();
        if (!image.contains("@sha256:") || !chartImage.contains("@sha256:")
                || !image.substring(image.indexOf('@')).equals(chartImage.substring(chartImage.indexOf('@')))
                || !imageRepository(image).equals(imageRepository(chartImage))) {
            throw new IllegalStateException("The chart and Testcontainers RabbitMQ image digests differ");
        }
        return image;
    }

    private static String imageRepository(String image) {
        String reference = image.substring(0, image.indexOf('@'));
        int tag = reference.lastIndexOf(':');
        return tag > reference.lastIndexOf('/') ? reference.substring(0, tag) : reference;
    }

    private static Map<String, Object> brokerSpecification() throws IOException {
        Path manifest = ShardShopFiles.generatorAllocation().resolveSibling("rabbitmq.yaml");
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(Files.readString(manifest))) {
            Map<String, Object> resource = map(document);
            if ("StatefulSet".equals(resource.get("kind"))) {
                Map<String, Object> pod = map(map(map(resource.get("spec")).get("template")).get("spec"));
                return map(list(pod.get("containers")).getFirst());
            }
        }
        throw new IllegalStateException("The integration build did not render the RabbitMQ StatefulSet");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object object) {
        return (Map<String, Object>) object;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object object) {
        return (List<Object>) object;
    }

    private static List<String> strings(Object object) {
        return list(object).stream().map(Object::toString).toList();
    }

    @Override
    public void dispose() {
        try {
            if (container != null) {
                container.stop();
            }
        } finally {
            if (volume != null) {
                DockerClientFactory.instance().client().removeVolumeCmd(volume).exec();
            }
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                } catch (IOException failure) {
                    throw new IllegalStateException("Could not delete RabbitMQ test credentials", failure);
                }
            }
            http.close();
        }
    }
}
