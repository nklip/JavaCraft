package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Run the production lifecycle scripts with real chart snapshots and isolated native-command fixtures. */
class KindStartupScriptTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODES = "shardshop-control-plane\nshardshop-worker\nshardshop-worker2\nshardshop-worker3\n";
    @TempDir Path temporary;
    private String helm;
    private int invocation;

    @BeforeEach
    void installFixtures() throws Exception {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        helm = commandPath("helm");
        for (String directory : List.of("scripts", "bin", "tmp")) Files.createDirectories(temporary.resolve(directory));
        for (String script : List.of("cluster-create.sh", "up.sh")) {
            Files.copy(source.resolve("scripts").resolve(script), temporary.resolve("scripts").resolve(script));
        }
        if (Files.isDirectory(source.resolve("scripts/lib"))) {
            copy(source.resolve("scripts/lib"), temporary.resolve("scripts/lib"));
        }
        for (String directory : List.of("infra", "database")) {
            copy(source.resolve(directory), temporary.resolve(directory));
        }
        Files.copy(source.resolve("skaffold.yaml"), temporary.resolve("skaffold.yaml"));
        state("nodes", "");
        state("events", "");
        state("failure", "none");
        state("running", "true");
        state("random-digit", "a");
        executable("kind", """
                #!/bin/bash
                set -eu
                printf 'kind %s\n' "$*" >> "$FIXTURE/events"
                case "$*" in
                  'get nodes --name shardshop') cat "$FIXTURE/nodes";;
                  'create cluster --name shardshop --config '* ) cp "$FIXTURE/created-nodes" "$FIXTURE/nodes";;
                  'export kubeconfig --name shardshop'|'load docker-image '*) ;;
                  *) exit 99;;
                esac
                """);
        state("created-nodes", NODES);
        executable("mvn", """
                #!/bin/bash
                set -eu
                phase=build
                for argument in "$@"; do
                  case "$argument" in -Dshardshop.kind.tags=*) phase=${argument#*=};; esac
                done
                printf 'mvn %s %s\n' "$phase" "$*" >> "$FIXTURE/events"
                [[ "$(cat "$FIXTURE/failure")" != "$phase" ]] || exit 71
                """);
        executable("docker", """
                #!/bin/bash
                set -eu
                printf 'docker %s\n' "$*" >> "$FIXTURE/events"
                case "$1" in
                  buildx) exit 0;;
                  info) echo 12884901888;;
                  scout)
                    [[ "$(cat "$FIXTURE/failure")" != scan || "$*" != *--exit-code* ]] || exit 73;;
                  inspect) cat "$FIXTURE/running";;
                  start) echo true > "$FIXTURE/running";;
                  image) printf 'sha256:%064d\n' 0;;
                  exec)
                    case "$*" in
                      *'images ls '*) printf 'REF TYPE DIGEST\nfixture manifest sha256:%064d\n' 1;;
                      *'content get '*) printf '{"config":{"digest":"sha256:%064d"}}\n' 0;;
                      *'images tag '*) ;;
                      *) exit 99;;
                    esac;;
                  *) exit 99;;
                esac
                """);
        executable("kubectl", """
                #!/bin/bash
                set -eu
                printf 'kubectl %s\n' "$*" >> "$FIXTURE/events"
                case "$*" in
                  *'create configmap shardshop-snowflake-generators '*) echo 12345678-1234-4234-8234-123456789abc;;
                  *'get secret '*)
                    arguments="$*"
                    secret=${arguments#*get secret }
                    secret=${secret%% *}
                    if [[ -f "$FIXTURE/secret-$secret" ]]; then
                      if [[ "$*" == *' -o json' ]]; then cat "$FIXTURE/secret-$secret"
                      else echo "secret/$secret"; fi
                    fi;;
                  *'create secret generic '*)
                    arguments="$*"
                    secret=${arguments#*create secret generic }
                    secret=${secret%% *}
                    if [[ "$secret" == shardshop-rabbitmq ]]; then
                      for argument in "$@"; do
                        case "$argument" in --from-file=users.json=*) cp "${argument#--from-file=users.json=}" "$FIXTURE/rabbitmq-users.json";; esac
                      done
                      echo '{"data":{"users.json":"eA==","admin-password":"eA==","order-password":"eA==","ledger-password":"eA==","erlang-cookie":"eA=="}}' > "$FIXTURE/secret-$secret"
                    else touch "$FIXTURE/secret-$secret"; fi;;
                  *'get statefulset/shardshop-rabbitmq persistentvolumeclaim/data-shardshop-rabbitmq-0 '*)
                    if [[ -f "$FIXTURE/rabbitmq-retained" ]]; then cat "$FIXTURE/rabbitmq-retained"; fi;;
                  *'get --raw /readyz'|*'wait --for=condition=Ready nodes '*|*'rollout status '*|*'rollout restart '*) ;;
                  *'apply -f '*|*'create -f '*|*'delete -f '*) ;;
                  *) exit 99;;
                esac
                """);
        executable("helm", """
                #!/bin/bash
                set -eu
                printf 'helm %s\n' "$*" >> "$FIXTURE/events"
                exec "$REAL_HELM" "$@"
                """);
        executable("helmfile", """
                #!/bin/bash
                set -eu
                printf 'helmfile fresh=%s %s\n' "$SHARDSHOP_FRESH" "$*" >> "$FIXTURE/events"
                if [[ "$*" == *stage=application* ]]; then
                  cp "$SHARDSHOP_SNAPSHOT/images.json" "$FIXTURE/application-values.json"
                fi
                """);
        executable("skaffold", """
                #!/bin/bash
                set -eu
                printf 'skaffold %s\n' "$*" >> "$FIXTURE/events"
                [[ "$(cat "$FIXTURE/failure")" != image-build ]] || exit 72
                for argument in "$@"; do
                  case "$argument" in
                    --cache-file=*)
                      cache=${argument#*=}
                      [[ "${cache%/*}" == "$SHARDSHOP_SNAPSHOT" ]] || { echo 'Cache is outside the invocation snapshot' >&2; exit 75; }
                      [[ ! -e "$cache" ]] || { echo 'Reused artifact cache' >&2; exit 74; }
                      printf '{}' > "$cache";;
                    --file-output=*) printf '{"builds":[{"imageName":"docker.io/library/shardshop-product","tag":"docker.io/library/shardshop-product:fixture"}]}' > "${argument#*=}";;
                  esac
                done
                """);
        executable("openssl", """
                #!/bin/bash
                set -eu
                [[ "$1" == rand && "$2" == -hex ]] || exit 99
                digit=a
                if [[ "$3" == 12 ]]; then digit=$(cat "$FIXTURE/random-digit"); fi
                for ((i=0; i < $3 * 2; i++)); do printf '%s' "$digit"; done
                printf '\n'
                """);
    }

    @Test
    void createBuildsOnlyTheFoundationAndChecksReadiness() throws Exception {
        Result result = run("cluster-create.sh");
        assertEquals(0, result.exit(), result.output());
        List<String> events = events();
        assertEquals(List.of("kind-config", "kind-nodes", "kind-retention", "kind-dns",
                "kind-admission-bootstrap", "kind-allocator", "kind-infrastructure"), phases());
        assertEquals(NODES, Files.readString(temporary.resolve("nodes")));
        assertTrue(events.stream().anyMatch(line -> line.startsWith("mvn build ")
                && line.contains("-pl shardshop-testing -am -Dshardshop.infrastructureOnly=true clean install -DinstallAtEnd=true")), events.toString());
        assertEquals(2, events.stream().filter(line -> line.contains(" install:install-file ")).count());
        assertEquals(8, events.stream().filter(line -> line.contains("create secret generic ")).count());
        JsonNode users = JSON.readTree(Files.readString(temporary.resolve("rabbitmq-users.json")));
        assertEquals(List.of("shardshop-admin", "shardshop-order", "shardshop-ledger"),
                users.path("users").findValuesAsText("name"));
        assertEquals("^ledger\\.commands$", users.path("permissions").get(1).path("write").asText());
        assertEquals("^order\\.ledger-results$", users.path("permissions").get(1).path("read").asText());
        assertEquals("^order\\.saga-results$", users.path("permissions").get(2).path("write").asText());
        assertEquals("^ledger\\.record-order$", users.path("permissions").get(2).path("read").asText());
        assertTrue(events.stream().anyMatch(line -> line.startsWith("helmfile fresh=true ") && line.contains("stage=infrastructure")));
        assertBefore("mvn kind-admission-bootstrap ", "kubectl --context kind-shardshop --request-timeout=10s -n shardshop create configmap shardshop-snowflake-generators ");
        assertTrue(events.stream().anyMatch(line -> line.contains("create -f ") && line.contains("generator-identity.yaml")));
        assertNoApplicationDelivery();
        assertFalse(Files.exists(lock()));
    }

    @Test
    void createResumesAndReconcilesExistingInfrastructureWithoutResettingAllocator() throws Exception {
        state("nodes", NODES);
        state("running", "false");
        Result result = run("cluster-create.sh");
        assertEquals(0, result.exit(), result.output());
        assertEquals("true\n", Files.readString(temporary.resolve("running")));
        assertTrue(events().stream().anyMatch(line -> line.startsWith("docker start ")));
        assertTrue(events().stream().anyMatch(line -> line.contains("rollout restart daemonset/kindnet")));
        assertBefore("mvn kind-retention ", "kubectl --context kind-shardshop --request-timeout=30s apply -f ");
        assertBefore("mvn kind-allocator ", "kubectl --context kind-shardshop --request-timeout=30s apply -f ");
        assertBefore("mvn kind-allocator ", "helmfile fresh=false ", "stage=infrastructure");
        assertTrue(phases().contains("kind-infrastructure"));
        assertEquals(8, events().stream().filter(line -> line.contains("create secret generic ")).count());
        assertNoAllocatorBootstrap();
        assertNoApplicationDelivery();
        assertFalse(Files.exists(lock()));
    }

    @Test
    void repeatedCreateReconcilesWithoutRecreatingClusterCredentialsOrAllocator() throws Exception {
        Result fresh = run("cluster-create.sh");
        assertEquals(0, fresh.exit(), fresh.output());
        state("events", "");
        Result retained = run("cluster-create.sh");
        assertEquals(0, retained.exit(), retained.output());
        assertTrue(events().stream().anyMatch(line -> line.startsWith("helmfile fresh=false ")
                && line.contains("stage=infrastructure")));
        assertTrue(events().stream().noneMatch(line -> line.contains("create secret generic ") || line.startsWith("docker start ")));
        assertNoAllocatorBootstrap();
        assertNoApplicationDelivery();
        assertFalse(Files.exists(lock()));
    }

    @Test
    void retainedRabbitMqWithoutCredentialsStopsBeforeReconciliation() throws Exception {
        state("nodes", NODES);
        state("rabbitmq-retained", "persistentvolumeclaim/data-shardshop-rabbitmq-0\n");
        Result result = run("cluster-create.sh");
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("Restore shardshop-rabbitmq from backup"), result.output());
        assertTrue(events().stream().noneMatch(line -> line.contains("create secret generic shardshop-rabbitmq")
                || line.startsWith("helmfile ")));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void incompleteRabbitMqSecretStopsBeforeReconciliationWithoutPrintingIt() throws Exception {
        state("nodes", NODES);
        state("secret-shardshop-rabbitmq", "{\"data\":{\"admin-password\":\"private-value\"}}");
        Result result = run("cluster-create.sh");
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("RabbitMQ Secret is incomplete"), result.output());
        assertFalse(result.output().contains("private-value"), result.output());
        assertTrue(events().stream().noneMatch(line -> line.startsWith("helmfile ")));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void retainedCreateSafetyGuardsFailBeforeProvisioningOrResettingAnything() throws Exception {
        state("nodes", NODES);
        for (String failure : List.of("kind-retention", "kind-allocator")) {
            state("failure", failure);
            state("events", "");
            Result result = run("cluster-create.sh");
            assertEquals(71, result.exit(), result.output());
            assertTrue(events().stream().noneMatch(line -> line.contains("apply -f ") || line.contains("create secret ")
                    || line.contains("create configmap ") || line.startsWith("helmfile ")), events().toString());
            assertNoAllocatorBootstrap();
            assertNoApplicationDelivery();
            assertFalse(Files.exists(lock()));
        }
    }

    @Test
    void upWithoutAClusterFailsBeforeBuildingOrMutatingAnything() throws Exception {
        Result result = run("up.sh");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("cluster-create.sh"), result.output());
        assertEquals(List.of("docker buildx version", "docker scout version", "kind get nodes --name shardshop"), events());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void createBuildAndConfigurationFailuresPreventClusterCreation() throws Exception {
        for (String failure : List.of("build", "kind-config")) {
            state("failure", failure);
            state("events", "");
            Result result = run("cluster-create.sh");
            assertEquals(71, result.exit(), result.output());
            assertEquals("", Files.readString(temporary.resolve("nodes")));
            assertTrue(events().stream().noneMatch(line -> line.startsWith("kind create ") || line.startsWith("kubectl ") || line.startsWith("helmfile ")));
            assertNoApplicationDelivery();
            assertFalse(Files.exists(lock()));
        }
    }

    @Test
    void failedBuildImageBuildOrScanCannotResumeOrDeployApplications() throws Exception {
        state("nodes", NODES);
        state("running", "false");
        for (String failure : List.of("build", "kind-config", "image-build", "scan")) {
            state("failure", failure);
            state("events", "");
            Result result = run("up.sh");
            assertNotEquals(0, result.exit(), result.output());
            assertEquals("false", Files.readString(temporary.resolve("running")));
            assertTrue(events().stream().noneMatch(line -> line.startsWith("kind create ") || line.startsWith("kind load ")
                    || line.startsWith("kubectl ") || line.startsWith("helmfile ")));
            assertFalse(Files.exists(lock()));
        }
    }

    @Test
    void failedRetentionGuardPreventsFoundationAndApplicationMutations() throws Exception {
        for (String script : List.of("cluster-create.sh", "up.sh")) {
            state("nodes", script.equals("up.sh") ? NODES : "");
            state("failure", "kind-retention");
            state("events", "");
            Result result = run(script);
            assertEquals(71, result.exit(), result.output());
            assertTrue(events().stream().noneMatch(line -> line.contains("apply -f ") || line.contains("create secret ")
                    || line.contains("create configmap ") || line.startsWith("helmfile ") || line.startsWith("kind load ")));
            assertFalse(Files.exists(lock()));
        }
    }

    @Test
    void upResumesRetainedNodesAndRestartsApplicationsWithoutReinitializingAllocator() throws Exception {
        state("nodes", NODES);
        state("running", "false");
        Result result = run("up.sh");
        assertEquals(0, result.exit(), result.output());
        assertTrue(events().stream().anyMatch(line -> line.startsWith("docker start ")));
        assertTrue(events().stream().anyMatch(line -> line.contains("rollout restart daemonset/kindnet")));
        assertTrue(events().stream().anyMatch(line -> line.startsWith("mvn build ")
                && line.contains(" clean install -DinstallAtEnd=true")
                && !line.contains("-Dshardshop.infrastructureOnly=true")), events().toString());
        assertEquals(List.of("kind-config", "kind-nodes", "kind-retention", "kind-dns", "kind-allocator"), phases());
        assertBefore("mvn kind-retention ", "helmfile fresh=false ");
        assertBefore("mvn kind-allocator ", "kind load ");
        assertBefore("kind load ", "helmfile fresh=false ", "stage=application");
        assertEquals("restart-" + "a".repeat(24), applicationValues().path("restartToken").asText());
        assertTrue(events().stream().noneMatch(line -> line.contains("get deployment shardshop-product")));
        assertEquals("docker.io/library/shardshop-product@sha256:" + "0".repeat(63) + "1",
                applicationValues().path("images").path("product").asText());
        assertNoAllocatorBootstrap();
        assertNoInfrastructureProvisioning();
        assertFalse(Files.exists(lock()));
    }

    @Test
    void everyUpRotatesTheRestartToken() throws Exception {
        state("nodes", NODES);
        Result first = run("up.sh");
        assertEquals(0, first.exit(), first.output());
        assertEquals("restart-" + "a".repeat(24), applicationValues().path("restartToken").asText());
        state("random-digit", "b");
        Result unchanged = run("up.sh");
        assertEquals(0, unchanged.exit(), unchanged.output());
        assertEquals("restart-" + "b".repeat(24), applicationValues().path("restartToken").asText());
        List<String> builds = events().stream().filter(line -> line.startsWith("skaffold build ")).toList();
        assertEquals(2, builds.size());
        assertTrue(builds.stream().allMatch(line -> line.contains("--cache-artifacts=true ")),
                "Disabling Skaffold caching enables pruning that can delete an image rebuilt by the same invocation");
        List<Path> caches = builds.stream().flatMap(line -> List.of(line.split(" ")).stream())
                .filter(argument -> argument.startsWith("--cache-file="))
                .map(argument -> Path.of(argument.substring("--cache-file=".length()))).toList();
        assertEquals(2, caches.stream().distinct().count(), "Every build must use a fresh artifact cache");
        assertTrue(caches.stream().noneMatch(Files::exists), "Invocation artifact caches must be cleaned up: " + caches);
        assertNoAllocatorBootstrap();
        assertNoInfrastructureProvisioning();
    }

    @Test
    void upWithInvalidAllocatorCannotLoadImagesOrDeployApplications() throws Exception {
        state("nodes", NODES);
        state("failure", "kind-allocator");
        Result result = run("up.sh");
        assertEquals(71, result.exit(), result.output());
        assertTrue(events().stream().noneMatch(line -> line.startsWith("kind load ") || line.startsWith("helmfile ")));
        assertNoInfrastructureProvisioning();
        assertNoAllocatorBootstrap();
        assertFalse(Files.exists(lock()));
    }

    private void assertNoInfrastructureProvisioning() throws IOException {
        assertTrue(events().stream().noneMatch(line -> line.contains("apply -f ") || line.contains("create secret ")
                || line.contains("stage=infrastructure") || line.contains("kind-admission-bootstrap")), events().toString());
    }

    private void assertNoApplicationDelivery() throws IOException {
        List<String> maven = events().stream().filter(line -> line.startsWith("mvn ")).toList();
        assertTrue(maven.stream().allMatch(line -> line.contains("-Dshardshop.infrastructureOnly=true")), maven.toString());
        assertTrue(maven.stream().allMatch(line -> line.contains("-f " + temporary.resolve("pom.xml") + " ")),
                "Every invocation must stay scoped to ShardShop: " + maven);
        assertTrue(maven.stream().allMatch(line -> line.contains("-pl shardshop-testing")
                || (line.contains(" -N ") && line.contains(" install:install-file ")
                && !List.of(line.split(" ")).contains("install")
                && !List.of(line.split(" ")).contains("package"))),
                "Only nonrecursive parent metadata caching may omit the observer module selection: " + maven);
        assertTrue(events().stream().noneMatch(line -> line.startsWith("skaffold ") || line.startsWith("kind load ")
                || line.startsWith("docker image ") || line.startsWith("docker scout ")
                || line.contains("stage=application") || line.contains("stage=migrations")), events().toString());
    }

    private void assertNoAllocatorBootstrap() throws IOException {
        assertTrue(events().stream().noneMatch(line -> line.startsWith("kind create ") || line.contains("kind-admission-bootstrap")
                || line.contains("create configmap shardshop-snowflake-generators") || line.contains("generator-identity.yaml")), events().toString());
        assertTrue(events().stream().filter(line -> line.startsWith("helmfile ")).allMatch(line -> line.startsWith("helmfile fresh=false ")));
    }

    private void assertBefore(String first, String second) throws IOException { assertBefore(first, second, ""); }

    private void assertBefore(String first, String second, String qualifier) throws IOException {
        List<String> events = events();
        int firstIndex = -1;
        int secondIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (firstIndex < 0 && events.get(i).startsWith(first)) firstIndex = i;
            if (secondIndex < 0 && events.get(i).startsWith(second) && events.get(i).contains(qualifier)) secondIndex = i;
        }
        assertTrue(firstIndex >= 0 && secondIndex > firstIndex, first + " must precede " + second + ": " + events);
    }

    private List<String> phases() throws IOException {
        return events().stream().filter(line -> line.startsWith("mvn kind-")).map(line -> line.split(" ")[1]).toList();
    }

    private Result run(String script) throws Exception {
        Path output = temporary.resolve("output-" + invocation++);
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", temporary.resolve("scripts").resolve(script).toString())
                .directory(temporary.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(Map.of("PATH", temporary.resolve("bin") + ":" + System.getenv("PATH"),
                "TMPDIR", temporary.resolve("tmp").toString(), "FIXTURE", temporary.toString(), "REAL_HELM", helm));
        builder.environment().remove("SHARDSHOP_ENV_VALUES");
        builder.environment().remove("SHARDSHOP_INVENTORY");
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Startup script exceeded its test deadline");
            assertTrue(Files.size(output) <= 65_536, "Unexpectedly large lifecycle output");
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static String commandPath(String name) throws Exception {
        Process process = new ProcessBuilder("/bin/bash", "-c", "command -v " + name).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), name + " must be on PATH");
            return new String(process.getInputStream().readAllBytes()).trim();
        } finally {
            process.destroyForcibly();
        }
    }

    private JsonNode applicationValues() throws IOException { return JSON.readTree(temporary.resolve("application-values.json").toFile()); }
    private List<String> events() throws IOException { return Files.readAllLines(temporary.resolve("events")); }
    private Path lock() { return temporary.resolve("tmp/shardshop-up.lock"); }
    private void state(String name, String value) throws IOException { Files.writeString(temporary.resolve(name), value); }

    private void executable(String name, String script) throws IOException {
        Path path = temporary.resolve("bin").resolve(name);
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true), "Cannot create fixture " + name);
    }

    private static void copy(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private record Result(int exit, String output) { }
}
