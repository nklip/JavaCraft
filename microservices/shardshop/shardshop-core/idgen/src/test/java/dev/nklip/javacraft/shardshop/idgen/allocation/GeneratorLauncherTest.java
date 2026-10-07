package dev.nklip.javacraft.shardshop.idgen.allocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class GeneratorLauncherTest {

    private static final String UID = "f6e5d4c3-b2a1-4012-9234-56789abcdef0";
    private static final String JAVA_HOME = "/opt/example-jdk";
    private static final String FAILURE =
            "Generator launcher failed; check application path, registry state, context and access.";

    @TempDir
    Path directory;

    private final GeneratorRegistry registry = mock(GeneratorRegistry.class);
    private final GeneratorLauncher.ApplicationRunner application = mock(GeneratorLauncher.ApplicationRunner.class);
    private final AtomicReference<List<String>> kubectl = new AtomicReference<>();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final GeneratorLauncher launcher = new GeneratorLauncher(prefix -> {
        kubectl.set(prefix);
        return registry;
    }, application);

    @BeforeEach
    void createApplicationJar() throws IOException {
        assertTrue(Files.createFile(directory.resolve("quarkus-run.jar")).toFile().isFile());
    }

    @Test
    void reservesBeforeEveryApplicationStartAndPropagatesItsExitCode() throws Exception {
        RegistryState first = new RegistryState(UID, "10", 7);
        RegistryState second = new RegistryState(UID, "11", 8);
        when(registry.read(any())).thenReturn(first, second);
        when(registry.reserve(any(), anyInt(), any())).thenReturn(true);
        when(application.run(anyList(), anyMap())).thenReturn(0, 19);

        assertEquals(0, run("product", environment()));
        assertEquals(19, run("order", environment()));

        InOrder calls = inOrder(registry, application);
        calls.verify(registry).read(any());
        calls.verify(registry).reserve(eq(first), eq(8), any());
        calls.verify(application).run(eq(command(8)), eq(environment()));
        calls.verify(registry).read(any());
        calls.verify(registry).reserve(eq(second), eq(9), any());
        calls.verify(application).run(eq(command(9)), eq(environment()));
        calls.verifyNoMoreInteractions();
        assertEquals(List.of("kubectl", "--context", "kind-shardshop", "--namespace", "shardshop"), kubectl.get());
        assertEquals("Reserved generator ID 8 for product JVM startup." + System.lineSeparator()
                + "Reserved generator ID 9 for order JVM startup." + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void clearsJvmAndGeneratorOverridesButPreservesApplicationConfiguration() throws Exception {
        reserve(1);
        Map<String, String> environment = environment();
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "SHARDSHOP_ID_GENERATOR_ID")) {
            environment.put(key, "malicious override");
        }
        environment.put("SHARDSHOP_ROUTING_CONFIG", "/config/routing.yaml");

        assertEquals(0, run("product", environment));

        ArgumentCaptor<Map<String, String>> childEnvironment = ArgumentCaptor.captor();
        verify(application).run(eq(command(2)), childEnvironment.capture());
        Map<String, String> expected = environment();
        expected.put("SHARDSHOP_ROUTING_CONFIG", "/config/routing.yaml");
        assertEquals(expected, childEnvironment.getValue());
        assertEquals("malicious override", environment.get("SHARDSHOP_ID_GENERATOR_ID"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void inClusterLaunchesUseThePodCredentialsWithoutAHostContext(String context) throws Exception {
        reserve(3);
        when(registry.read(any())).thenAnswer(invocation -> {
            assertPrivatePodKubeconfig();
            return new RegistryState(UID, "17", 3);
        });
        Map<String, String> environment = environment();
        environment.put("KUBERNETES_SERVICE_HOST", "10.96.0.1");
        environment.put("KUBECONFIG", "/untrusted/kubeconfig");
        environment.put("HOME", "/untrusted/home");
        if (context == null) {
            environment.remove("SHARDSHOP_KUBE_CONTEXT");
        } else {
            environment.put("SHARDSHOP_KUBE_CONTEXT", context);
        }

        assertEquals(0, run("order", environment));
        assertFalse(Files.exists(Path.of(kubectl.get().get(2))));
        verify(application).run(eq(command(4)), eq(environment));
    }

    @Test
    void failedInClusterAllocationRemovesItsPrivateKubeconfig() throws Exception {
        when(registry.read(any())).thenAnswer(invocation -> {
            assertPrivatePodKubeconfig();
            throw new IOException("allocation failed");
        });

        assertFailure(new String[]{"product", directory.toString()}, inClusterEnvironment());
        assertFalse(Files.exists(Path.of(kubectl.get().get(2))));
    }

    @Test
    void interruptedInClusterAllocationRemovesItsPrivateKubeconfig() throws Exception {
        when(registry.read(any())).thenThrow(new InterruptedException("interrupted"));

        try {
            assertEquals(130, run("order", inClusterEnvironment()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(Path.of(kubectl.get().get(2))));
            verifyNoInteractions(application);
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void invalidInClusterIdentityRemovesItsPrivateKubeconfigBeforeAnyRequest() {
        Map<String, String> environment = inClusterEnvironment();
        environment.remove("SHARDSHOP_GENERATOR_REGISTRY_UID");

        assertFailure(new String[]{"product", directory.toString()}, environment,
                "A pinned generator registry UID is required; set SHARDSHOP_GENERATOR_REGISTRY_UID from the identity ConfigMap.");
        assertFalse(Files.exists(Path.of(kubectl.get().get(2))));
        verifyNoInteractions(registry);
    }

    @Test
    void failureToRemoveTheKubeconfigFailsStartupAfterBurningTheReservation() throws Exception {
        reserve(1);
        when(registry.read(any())).thenAnswer(invocation -> {
            Files.delete(Path.of(kubectl.get().get(2)));
            return new RegistryState(UID, "17", 1);
        });

        assertFailure(new String[]{"product", directory.toString()}, inClusterEnvironment());
        verify(registry).reserve(eq(new RegistryState(UID, "17", 1)), eq(2), any());
        assertFalse(Files.exists(Path.of(kubectl.get().get(2))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"production", " kind-shardshop", "kind-shardshop\n"})
    void hostLaunchesRequireTheExplicitLabContext(String context) {
        Map<String, String> environment = environment();
        environment.remove("SHARDSHOP_KUBE_CONTEXT");
        if (context != null) {
            environment.put("SHARDSHOP_KUBE_CONTEXT", context);
        }

        assertFailure(new String[]{"product", directory.toString()}, environment,
                "Host launches require SHARDSHOP_KUBE_CONTEXT=kind-shardshop; correct the launcher environment.");
        verifyNoInteractions(registry);
    }

    @Test
    void inClusterLaunchesRejectAHostContext() {
        Map<String, String> environment = environment();
        environment.put("KUBERNETES_SERVICE_HOST", "10.96.0.1");

        assertFailure(new String[]{"product", directory.toString()}, environment,
                "In-cluster launches use the pod credentials; unset SHARDSHOP_KUBE_CONTEXT.");
        verifyNoInteractions(registry);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "F6E5D4C3-B2A1-4012-9234-56789ABCDEF0", "f6e5d4c3-b2a1-4012-9234-56789abcdef0\n"})
    void missingOrNoncanonicalPinnedRegistryIdentityStopsBeforeAllocation(String uid) {
        Map<String, String> environment = environment();
        environment.remove("SHARDSHOP_GENERATOR_REGISTRY_UID");
        if (uid != null) {
            environment.put("SHARDSHOP_GENERATOR_REGISTRY_UID", uid);
        }

        assertFailure(new String[]{"order", directory.toString()}, environment,
                "A pinned generator registry UID is required; set SHARDSHOP_GENERATOR_REGISTRY_UID from the identity ConfigMap.");
        verifyNoInteractions(registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledger", "product-seeder", "product-reader", "order-producer", "PRODUCT", ""})
    void otherServicesCannotUseTheLauncher(String service) {
        assertFailure(new String[]{service, directory.toString()}, environment());
        verifyNoInteractions(registry);
    }

    @Test
    void commandLineCannotSupplyExtraOptionsOrAnId() {
        assertFailure(new String[]{"product", directory.toString(), "7"}, environment());
        verifyNoInteractions(registry);
    }

    @Test
    void missingCommandLineArgumentsFailBeforeAllocation() {
        assertFailure(new String[0], environment());
        verifyNoInteractions(registry);
    }

    @Test
    void missingApplicationJarFailsBeforeAllocation() {
        assertFailure(new String[]{"order", directory.resolve("missing").toString()}, environment());
        verifyNoInteractions(registry);
    }

    @Test
    void directoryInPlaceOfApplicationJarFailsBeforeAllocation() throws IOException {
        Files.delete(directory.resolve("quarkus-run.jar"));
        assertTrue(Files.createDirectory(directory.resolve("quarkus-run.jar")).toFile().isDirectory());

        assertFailure(new String[]{"order", directory.toString()}, environment());
        verifyNoInteractions(registry);
    }

    @Test
    void failedAllocationNeverStartsAnApplicationOrPrintsExternalErrors() throws Exception {
        when(registry.read(any())).thenThrow(new IOException("secret credential from external command"));

        assertFailure(new String[]{"product", directory.toString()}, environment());
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret"));
    }

    @Test
    void exhaustedRegistryNeverStartsAnApplication() throws Exception {
        when(registry.read(any())).thenReturn(new RegistryState(UID, "17", 1023));

        assertFailure(new String[]{"order", directory.toString()}, environment(),
                "Generator IDs exhausted; stop ID-producing services and retire all prior emitters, data, backups and replay inputs before a fresh-lab reset.");
    }

    @Test
    void replacedRegistryNeverStartsAnApplication() throws Exception {
        when(registry.read(any())).thenReturn(new RegistryState("12345678-1234-1234-1234-123456789abc", "17", 0));

        assertFailure(new String[]{"product", directory.toString()}, environment(),
                "Generator registry is stale or has been replaced; verify SHARDSHOP_GENERATOR_REGISTRY_UID against the identity ConfigMap and investigate rollback before restarting.");
    }

    @Test
    void deadlineFailureReportsHowToInvestigateWithoutStartingTheApplication() throws Exception {
        AtomicInteger clockReads = new AtomicInteger();
        GeneratorAllocator allocator = new GeneratorAllocator(registry, UID,
                () -> clockReads.getAndIncrement() * Duration.ofSeconds(30).toNanos(),
                mock(GeneratorAllocator.RetryDelay.class));
        IOException deadline = assertThrows(IOException.class, allocator::allocate);
        when(registry.read(any())).thenThrow(deadline);

        assertFailure(new String[]{"product", directory.toString()}, environment(),
                "Generator allocation exceeded its 30-second deadline; check Kubernetes API availability, registry PATCH permission and competing starts before retrying.");
    }

    @Test
    void unexpectedRuntimeFailureDoesNotPrintExternalDetails() throws Exception {
        when(registry.read(any())).thenThrow(new IllegalStateException("secret runtime content"));

        assertFailure(new String[]{"product", directory.toString()}, environment());
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret"));
    }

    @Test
    void registryAccessFailureReportsReadRemediationWithoutCommandOutput() throws Exception {
        CommandRunner runner = mock(CommandRunner.class);
        when(runner.run(anyList(), any())).thenReturn(new CommandResult(1, "secret authorization error"));
        GeneratorLauncher launcher = new GeneratorLauncher(prefix -> new KubectlRegistry(prefix, runner), application);

        assertEquals(1, launcher.run(new String[]{"product", directory.toString()}, environment(),
                JAVA_HOME, new PrintStream(errors)));
        assertEquals("Generator registry could not be read; check registry existence, state, Kubernetes API access"
                + " and GET permission." + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret"));
        verifyNoInteractions(application);
    }

    @Test
    void missingKubectlReportsInstallationRemediationInsteadOfRegistryAccess() throws Exception {
        ProcessCommandRunner.ProcessStarter starter = mock(ProcessCommandRunner.ProcessStarter.class);
        ProcessCommandRunner.OutputReader reader = mock(ProcessCommandRunner.OutputReader.class);
        when(starter.start(anyList())).thenThrow(new IOException("secret executable path"));
        CommandRunner runner = new ProcessCommandRunner(starter, reader, () -> 0L);
        GeneratorLauncher launcher = new GeneratorLauncher(prefix -> new KubectlRegistry(prefix, runner), application);

        assertEquals(1, launcher.run(new String[]{"order", directory.toString()}, environment(),
                JAVA_HOME, new PrintStream(errors)));
        assertEquals("kubectl could not be started; install it on PATH and check executable permissions."
                + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
        assertFalse(errors.toString(StandardCharsets.UTF_8).contains("secret"));
        verifyNoInteractions(application, reader);
    }

    @Test
    void failedApplicationForkCannotReuseItsReservation() throws Exception {
        RegistryState first = new RegistryState(UID, "10", 7);
        RegistryState second = new RegistryState(UID, "11", 8);
        when(registry.read(any())).thenReturn(first, second);
        when(registry.reserve(any(), anyInt(), any())).thenReturn(true);
        when(application.run(anyList(), anyMap())).thenThrow(new IOException("secret child error")).thenReturn(0);

        assertEquals(1, run("product", environment()));
        assertEquals(0, run("product", environment()));
        verify(application).run(eq(command(8)), anyMap());
        verify(application).run(eq(command(9)), anyMap());
        assertEquals("Reserved generator ID 8 for product JVM startup." + System.lineSeparator()
                + FAILURE + System.lineSeparator()
                + "Reserved generator ID 9 for product JVM startup." + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void interruptedAllocationNeverStartsTheApplicationAndPreservesInterruptStatus() throws Exception {
        when(registry.read(any())).thenThrow(new InterruptedException("secret transport error"));

        try {
            assertEquals(130, run("order", environment()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("Generator launcher interrupted; application startup or execution stopped." + System.lineSeparator(),
                    errors.toString(StandardCharsets.UTF_8));
            verifyNoInteractions(application);
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void publicEntrypointReportsFailureAndExitsWithoutAStackTrace() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Path classes = Path.of(GeneratorLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        command.addAll(List.of("-cp", classes.toString(), GeneratorLauncher.class.getName()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertEquals(FAILURE + System.lineSeparator(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            assertSame(process, process.destroyForcibly());
        }
    }

    @Test
    void mainHandsTheExitCodeToTheProcessExitHandler() {
        AtomicInteger exitCode = new AtomicInteger(-1);
        GeneratorLauncher main = new GeneratorLauncher(prefix -> registry, application, exitCode::set, new PrintStream(errors));

        main.main(new String[0]);

        assertEquals(1, exitCode.get());
        assertEquals(FAILURE + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
        verifyNoInteractions(registry, application);
    }

    @Test
    void defaultLauncherRejectsInvalidArgumentsBeforeExternalIo() {
        assertEquals(1, new GeneratorLauncher().run(new String[0], environment(), JAVA_HOME, new PrintStream(errors)));
        assertEquals(FAILURE + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
    }

    private void reserve(int highWaterMark) throws Exception {
        when(registry.read(any())).thenReturn(new RegistryState(UID, "17", highWaterMark));
        when(registry.reserve(any(), anyInt(), any())).thenReturn(true);
    }

    private int run(String service, Map<String, String> environment) {
        return launcher.run(new String[]{service, directory.toString()}, environment, JAVA_HOME, new PrintStream(errors));
    }

    private void assertFailure(String[] args, Map<String, String> environment) {
        assertFailure(args, environment, FAILURE);
    }

    private void assertFailure(String[] args, Map<String, String> environment, String message) {
        assertEquals(1, launcher.run(args, environment, JAVA_HOME, new PrintStream(errors)));
        assertEquals(message + System.lineSeparator(), errors.toString(StandardCharsets.UTF_8));
        verifyNoInteractions(application);
    }

    private List<String> command(int generatorId) {
        return List.of(Path.of(JAVA_HOME, "bin", "java").toString(), "-Dquarkus.profile=prod",
                "-Dshardshop.id.generator-id=" + generatorId, "-Dshardshop.launcher.reserved-generator-id=" + generatorId,
                "-jar", directory.resolve("quarkus-run.jar").toString());
    }

    private static Map<String, String> environment() {
        return new HashMap<>(Map.of("SHARDSHOP_KUBE_CONTEXT", "kind-shardshop", "SHARDSHOP_GENERATOR_REGISTRY_UID", UID));
    }

    private static Map<String, String> inClusterEnvironment() {
        return new HashMap<>(Map.of("KUBERNETES_SERVICE_HOST", "10.96.0.1", "SHARDSHOP_GENERATOR_REGISTRY_UID", UID));
    }

    private void assertPrivatePodKubeconfig() throws IOException {
        List<String> prefix = kubectl.get();
        assertEquals(List.of("kubectl", "--kubeconfig", prefix.get(2), "--namespace", "shardshop"), prefix);
        Path kubeconfig = Path.of(prefix.get(2));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(kubeconfig));
        String config = Files.readString(kubeconfig);
        assertTrue(config.contains("\"server\":\"https://kubernetes.default.svc\""));
        assertTrue(config.contains("\"certificate-authority\":\"/var/run/secrets/kubernetes.io/serviceaccount/ca.crt\""));
        assertTrue(config.contains("\"tokenFile\":\"/var/run/secrets/kubernetes.io/serviceaccount/token\""));
        assertTrue(config.contains("\"namespace\":\"shardshop\""));
        assertTrue(config.contains("\"current-context\":\"in-cluster\""));
        assertFalse(config.contains("\"token\":"));
        assertFalse(config.contains("untrusted"));
    }

}
