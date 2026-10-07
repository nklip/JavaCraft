package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;

/** Reserves a fresh live generator identity before starting a product or order JVM. */
public final class GeneratorLauncher {

    private static final String IN_CLUSTER_CONFIG = """
            {"apiVersion":"v1","kind":"Config",
             "clusters":[{"name":"in-cluster","cluster":{
               "server":"https://kubernetes.default.svc",
               "certificate-authority":"/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"}}],
             "users":[{"name":"service-account","user":{
               "tokenFile":"/var/run/secrets/kubernetes.io/serviceaccount/token"}}],
             "contexts":[{"name":"in-cluster","context":{
               "cluster":"in-cluster","user":"service-account","namespace":"shardshop"}}],
             "current-context":"in-cluster"}
            """;

    private final Function<List<String>, GeneratorRegistry> registries;
    private final ApplicationRunner application;
    private final IntConsumer exit;
    private final PrintStream errorOutput;

    public GeneratorLauncher() {
        this(KubectlRegistry::new, new ApplicationProcess()::run);
    }

    GeneratorLauncher(Function<List<String>, GeneratorRegistry> registries, ApplicationRunner application) {
        this(registries, application, System::exit, System.err);
    }

    GeneratorLauncher(Function<List<String>, GeneratorRegistry> registries, ApplicationRunner application,
                      IntConsumer exit, PrintStream errorOutput) {
        this.registries = registries;
        this.application = application;
        this.exit = exit;
        this.errorOutput = errorOutput;
    }

    public void main(String[] args) {
        exit.accept(run(args, System.getenv(), System.getProperty("java.home"), errorOutput));
    }

    int run(String[] args, Map<String, String> environment, String javaHome, PrintStream errors) {
        try {
            if (args.length != 2 || !("product".equals(args[0]) || "order".equals(args[0]))) {
                throw new IllegalArgumentException("Expected <product|order> <quarkus-app-directory>");
            }
            Path jar = Path.of(args[1]).toAbsolutePath().resolve("quarkus-run.jar");
            if (!Files.isRegularFile(jar)) {
                throw new IllegalArgumentException("Application JAR is missing");
            }
            int generatorId = allocate(environment);
            errors.println("Reserved generator ID " + generatorId + " for " + args[0] + " JVM startup.");
            List<String> command = List.of(Path.of(javaHome, "bin", "java").toString(),
                    "-Dquarkus.profile=prod", "-Dshardshop.id.generator-id=" + generatorId,
                    "-Dshardshop.launcher.reserved-generator-id=" + generatorId,
                    "-jar", jar.toString());
            Map<String, String> childEnvironment = new HashMap<>(environment);
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                    "SHARDSHOP_ID_GENERATOR_ID")) {
                childEnvironment.remove(key);
            }
            return application.run(command, Map.copyOf(childEnvironment));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            errors.println("Generator launcher interrupted; application startup or execution stopped.");
            return 130;
        } catch (AllocationFailure failure) {
            errors.println(failure.getMessage());
            return 1;
        } catch (IOException | RuntimeException failure) {
            errors.println("Generator launcher failed; check application path, registry state, context and access.");
            return 1;
        }
    }

    private int allocate(Map<String, String> environment) throws IOException, InterruptedException {
        String context = environment.get("SHARDSHOP_KUBE_CONTEXT");
        String uid = environment.get("SHARDSHOP_GENERATOR_REGISTRY_UID");
        if (environment.getOrDefault("KUBERNETES_SERVICE_HOST", "").isBlank()) {
            if (!"kind-shardshop".equals(context)) {
                throw new AllocationFailure(AllocationFailure.Reason.HOST_CONTEXT);
            }
            return allocate(List.of("kubectl", "--context", "kind-shardshop", "--namespace", "shardshop"), uid);
        }
        if (context != null && !context.isEmpty()) {
            throw new AllocationFailure(AllocationFailure.Reason.IN_CLUSTER_CONTEXT);
        }
        Path kubeconfig = Files.createTempFile("shardshop-allocator-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(kubeconfig, IN_CLUSTER_CONFIG);
            return allocate(List.of("kubectl", "--kubeconfig", kubeconfig.toString(), "--namespace", "shardshop"), uid);
        } finally {
            Files.delete(kubeconfig);
        }
    }

    private int allocate(List<String> kubectl, String uid) throws IOException, InterruptedException {
        GeneratorRegistry registry = registries.apply(kubectl);
        try {
            RegistryState.requireUid(uid);
        } catch (IllegalArgumentException invalidPin) {
            throw new AllocationFailure(AllocationFailure.Reason.PINNED_UID);
        }
        return new GeneratorAllocator(registry, uid).allocate();
    }

    @FunctionalInterface
    interface ApplicationRunner {
        int run(List<String> command, Map<String, String> environment) throws IOException, InterruptedException;
    }
}
