package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Run the production lifecycle scripts with real chart snapshots and isolated native-command fixtures. */
class KindStartupScriptTest {
    /** The local ports and Service names of scripts/webpages.sh, in the same order. */
    private static final List<Integer> PAGE_PORTS = List.of(18080, 18081, 18082, 18083, 18084);
    private static final List<String> PAGE_SERVICES = List.of("product", "order", "order-producer", "product-reader",
            "product-seeder-control");
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
        for (String script : List.of("cluster-create.sh", "up.sh", "webpages.sh", "down.sh")) {
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
        var flywayDigest = java.util.regex.Pattern.compile("shardshop-flyway[^@\\s]*@sha256:([0-9a-f]{64})")
                .matcher(Files.readString(source.resolve("infra/helm/shardshop/values.yaml")));
        assertTrue(flywayDigest.find());
        state("flyway-digest", flywayDigest.group(1));
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
                  update) ;;
                  start) echo true > "$FIXTURE/running";;
                  stop) echo false > "$FIXTURE/running";;
                  image) printf 'sha256:%064d\n' 0;;
                  exec)
                    case "$*" in
                      *'images ls '*)
                        if [[ "$*" == *shardshop-flyway* ]]; then
                          printf 'REF TYPE DIGEST\nfixture manifest sha256:%s\n' "$(cat "$FIXTURE/flyway-digest")"
                        else printf 'REF TYPE DIGEST\nfixture manifest sha256:%064d\n' 1; fi;;
                      *'content get '*) printf '{"config":{"digest":"sha256:%064d"}}\n' 0;;
                      *'images tag '*) ;;
                      *) exit 99;;
                    esac;;
                  *) exit 99;;
                esac
                """);
        executable("lsof", """
                #!/bin/bash
                set -eu
                printf 'lsof %s\n' "$*" >> "$FIXTURE/events"
                [[ "$(cat "$FIXTURE/failure")" != port-inspection ]] || exit 2
                case "$*" in '-t -nP -iTCP:1808'[0-4]' -sTCP:LISTEN') ;; *) exit 99;; esac
                for port in 18080 18081 18082 18083 18084; do
                  if [[ " $* " == *" -iTCP:$port "* && -f "$FIXTURE/occupied-$port" ]]; then
                    pid=$(cat "$FIXTURE/occupied-$port")
                    if kill -0 "$pid" 2>/dev/null; then printf '%s\\n' "$pid"; exit 0; fi
                  fi
                done
                exit 1
                """);
        executable("kubectl", """
                #!/bin/bash
                set -eu
                printf 'kubectl %s\n' "$*" >> "$FIXTURE/events"
                case "$*" in
                  *'port-forward '*)
                    if [[ -f "$FIXTURE/clear-before-forward" ]]; then
                      while read -r pid; do
                        if kill -0 "$pid" 2>/dev/null; then echo 'Forwarding started before page listener exited' >&2; exit 80; fi
                      done < "$FIXTURE/clear-before-forward"
                    fi
                    [[ ! -e "$TMPDIR/shardshop-up.lock" ]] || exit 76
                    if [[ -f "$FIXTURE/snapshot" ]]; then [[ ! -e "$(cat "$FIXTURE/snapshot")" ]] || exit 76; fi
                    arguments="$*"
                    service=${arguments#*service/shardshop-}
                    service=${service%% *}
                    echo $$ > "$FIXTURE/forward-pid-$service"
                    [[ "$(cat "$FIXTURE/failure")" != "port-forward-$service" ]] || exit 77
                    echo 'Forwarding from 127.0.0.1'
                    if [[ "$(cat "$FIXTURE/failure")" == "forward-exit-$service" ]]; then sleep 0.3; exit 79; fi
                    if [[ -f "$FIXTURE/block-forward" ]]; then exec sleep 60; fi
                    exec sleep 0.5;;
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
                  printf '%s' "$SHARDSHOP_SNAPSHOT" > "$FIXTURE/snapshot"
                  [[ "$(cat "$FIXTURE/failure")" != deployment ]] || exit 78
                  echo application-acceptance-complete >> "$FIXTURE/events"
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
                    --file-output=*) printf '{"builds":[' > "${argument#*=}"
                      separator=
                      for image in product product-seeder product-reader order ledger order-producer flyway; do
                        printf '%s{"imageName":"docker.io/library/shardshop-%s","tag":"docker.io/library/shardshop-%s:fixture"}' "$separator" "$image" "$image" >> "${argument#*=}"
                        separator=,
                      done
                      printf ']}' >> "${argument#*=}";;
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
        assertEquals(9, events.stream().filter(line -> line.contains("create secret generic ")).count());
        assertTrue(events.stream().anyMatch(line -> line.contains("create secret generic ordering-operator ")
                && line.contains("--from-literal=username=ordering_operator")));
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
        // down.sh turned the policy off; a resumed cluster gets kind's default back before its nodes start.
        assertBefore("docker update --restart=on-failure:1 " + String.join(" ", NODES.strip().split("\n")), "docker start ");
        assertTrue(events().stream().anyMatch(line -> line.contains("rollout restart daemonset/kindnet")));
        assertBefore("mvn kind-retention ", "kubectl --context kind-shardshop --request-timeout=30s apply -f ");
        assertBefore("mvn kind-allocator ", "kubectl --context kind-shardshop --request-timeout=30s apply -f ");
        assertBefore("mvn kind-allocator ", "helmfile fresh=false ", "stage=infrastructure");
        assertTrue(phases().contains("kind-infrastructure"));
        assertEquals(9, events().stream().filter(line -> line.contains("create secret generic ")).count());
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
    void upAndWebpagesRejectArgumentsBeforeAnyExternalCommandOrTemporaryState() throws Exception {
        for (String script : List.of("up.sh", "webpages.sh")) {
            for (String argument : List.of("--pages-only", "--anything", "unexpected")) {
                Result result = run(script, argument);
                assertNotEquals(0, result.exit(), result.output());
                assertTrue(events().isEmpty(), events().toString());
                assertFalse(Files.exists(lock()));
                assertFalse(Files.exists(temporary.resolve("snapshot")));
                assertTemporaryFilesCleaned();
            }
        }
    }

    @Test
    void upWithoutAClusterFailsBeforeBuildingOrMutatingAnything() throws Exception {
        Result result = run("up.sh");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("cluster-create.sh"), result.output());
        assertEquals(List.of("docker buildx version", "docker scout version", "kind get nodes --name shardshop"),
                events());
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
        assertBefore("docker update --restart=on-failure:1 " + String.join(" ", NODES.strip().split("\n")), "docker start ");
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
    void upCompletesDeploymentAndReleasesInvocationWithoutInspectingOrForwardingPagePorts() throws Exception {
        state("nodes", NODES);
        Result result = run("up.sh");
        assertEquals(0, result.exit(), result.output());
        assertTrue(events().contains("application-acceptance-complete"), events().toString());
        assertTrue(events().stream().noneMatch(line -> line.startsWith("lsof ") || line.contains(" port-forward ")),
                events().toString());
        assertTrue(result.output().contains("scripts/webpages.sh"), result.output());
        assertInvocationCleaned();
        assertTemporaryFilesCleaned();
    }

    @Test
    void upLeavesOccupiedPagePortsUntouched() throws Exception {
        state("nodes", NODES);
        Process productOwner = new ProcessBuilder("/bin/sleep", "60").start();
        Process orderOwner = new ProcessBuilder("/bin/sleep", "60").start();
        try {
            state("occupied-18080", Long.toString(productOwner.pid()));
            state("occupied-18081", Long.toString(orderOwner.pid()));
            Result result = run("up.sh");
            assertEquals(0, result.exit(), result.output());
            assertTrue(productOwner.isAlive(), "Deployment must leave the product page listener untouched");
            assertTrue(orderOwner.isAlive(), "Deployment must leave the order page listener untouched");
            assertTrue(events().contains("application-acceptance-complete"), events().toString());
            assertTrue(events().stream().noneMatch(line -> line.startsWith("lsof ") || line.contains(" port-forward ")),
                    events().toString());
            assertInvocationCleaned();
        } finally {
            productOwner.destroyForcibly();
            orderOwner.destroyForcibly();
            assertTrue(productOwner.waitFor(5, TimeUnit.SECONDS));
            assertTrue(orderOwner.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void webpagesOpensAllPageForwards() throws Exception {
        Result result = run("webpages.sh");
        assertPageSessionEnded(result);
        String forward = "kubectl --context kind-shardshop -n shardshop port-forward --address 127.0.0.1 service/shardshop-";
        assertEquals(List.of(forward + "order 18081:8080", forward + "order-producer 18082:8080",
                        forward + "product 18080:8080", forward + "product-reader 18083:8080",
                        forward + "product-seeder-control 18084:8080"),
                events().stream().filter(line -> line.contains(" port-forward ")).sorted().toList());
        for (String url : List.of("http://127.0.0.1:18080/products/", "http://127.0.0.1:18081/orders/",
                "http://127.0.0.1:18082/workload-order-producer/", "http://127.0.0.1:18083/workload-product-reader/",
                "http://127.0.0.1:18084/workload-product-seeder/")) {
            assertTrue(result.output().contains(url), result.output());
        }
        assertForwardsStopped();
        assertTemporaryFilesCleaned();
    }

    @Test
    void webpagesTerminatesOwnersOfEitherOrBothPagePortsBeforeForwarding() throws Exception {
        state("nodes", NODES);
        Process unrelated = new ProcessBuilder("/bin/sleep", "60").start();
        try {
            for (List<Integer> ports : List.of(List.of(18080), List.of(18081), List.of(18084), List.of(18080, 18081),
                    PAGE_PORTS)) {
                for (int port : PAGE_PORTS) Files.deleteIfExists(temporary.resolve("occupied-" + port));
                List<Process> owners = new ArrayList<>();
                try {
                    for (int port : ports) {
                        Process owner = new ProcessBuilder("/bin/sleep", "60").start();
                        owners.add(owner);
                        state("occupied-" + port, Long.toString(owner.pid()));
                    }
                    state("clear-before-forward", String.join("\n", owners.stream().map(owner -> Long.toString(owner.pid())).toList()) + "\n");
                    state("events", "");
                    Result result = run("webpages.sh");
                    assertPageSessionEnded(result);
                    assertForwardsStopped();
                    for (Process owner : owners) assertTrue(owner.waitFor(2, TimeUnit.SECONDS), "The discovered port owner must exit");
                    assertTrue(unrelated.isAlive(), "An unlisted process must remain untouched");
                    assertBefore("lsof ", "kubectl --context kind-shardshop -n shardshop port-forward ");
                    assertTrue(events().stream().allMatch(line -> line.startsWith("lsof ") || line.contains(" port-forward ")), events().toString());
                    assertFalse(Files.exists(lock()));
                } finally {
                    for (Process owner : owners) { owner.destroyForcibly(); owner.waitFor(5, TimeUnit.SECONDS); }
                }
            }
        } finally {
            unrelated.destroyForcibly();
            assertTrue(unrelated.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void webpagesEscalatesForAnOwnerIgnoringTerminationBeforeForwarding() throws Exception {
        state("nodes", NODES);
        Path ready = temporary.resolve("owner-ready");
        Process owner = new ProcessBuilder("/bin/bash", "-c", "trap '' TERM; touch \"$1\"; exec /bin/sleep 60", "fixture", ready.toString()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(ready) && owner.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(ready), "Owner must install its signal handler before startup");
            state("occupied-18081", Long.toString(owner.pid()));
            state("clear-before-forward", owner.pid() + "\n");
            Result result = run("webpages.sh");
            assertPageSessionEnded(result);
            assertForwardsStopped();
            assertTrue(owner.waitFor(2, TimeUnit.SECONDS));
            assertEquals(137, owner.exitValue(), "Ignoring TERM must cause bounded KILL escalation");
            assertBefore("lsof ", "kubectl --context kind-shardshop -n shardshop port-forward ");
            assertFalse(Files.exists(lock()));
        } finally {
            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void webpagesPortInspectionFailureStopsBeforeForwarding() throws Exception {
        state("nodes", NODES);
        state("failure", "port-inspection");
        Result result = run("webpages.sh");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().toLowerCase().contains("inspect"), result.output());
        assertTrue(events().stream().noneMatch(line -> line.startsWith("mvn ") || line.startsWith("skaffold ")
                || line.startsWith("kubectl ") || line.startsWith("helmfile ")), events().toString());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void webpagesOpensBothPagesWithoutBuildingDeployingOrChangingTheLab() throws Exception {
        state("nodes", NODES);
        state("block-forward", "");
        state("secret-retained", "retained credentials");
        try (Running up = start("webpages.sh")) {
            awaitForwarding(up);
            assertTrue(up.process().isAlive());
            assertEquals(5, events().stream().filter(line -> line.contains(" port-forward ")).count());
            assertTrue(forwardPids().stream().allMatch(pid -> ProcessHandle.of(pid).orElseThrow().isAlive()));
            assertWebpagesPreservedLab();
            Process signal = new ProcessBuilder("/bin/kill", "-TERM", Long.toString(up.process().pid())).start();
            try {
                assertTrue(signal.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, signal.exitValue());
            } finally {
                signal.destroyForcibly();
            }
            assertTrue(up.process().waitFor(5, TimeUnit.SECONDS));
            assertEquals(143, up.process().exitValue());
            assertForwardsStopped();
            assertWebpagesPreservedLab();
            assertTemporaryFilesCleaned();
        }
    }

    @Test
    void webpagesStartupFailureReapsAllTunnelsAndPreservesTheLab() throws Exception {
        state("nodes", NODES);
        state("block-forward", "");
        state("secret-retained", "retained credentials");
        for (String service : PAGE_SERVICES) {
            state("failure", "port-forward-" + service);
            Result result = run("webpages.sh");
            assertEquals(77, result.exit(), result.output());
            assertTrue(result.output().contains(service + " forwarding stopped"), result.output());
            assertForwardsStopped();
            assertWebpagesPreservedLab();
            assertTemporaryFilesCleaned();
        }
    }

    private void assertWebpagesPreservedLab() throws IOException {
        assertTrue(events().stream().allMatch(line -> line.startsWith("lsof ")
                || line.contains(" port-forward ")), events().toString());
        assertEquals(NODES, Files.readString(temporary.resolve("nodes")));
        assertEquals("true", Files.readString(temporary.resolve("running")));
        assertEquals("retained credentials", Files.readString(temporary.resolve("secret-retained")));
        assertFalse(Files.exists(lock()));
        assertFalse(Files.exists(temporary.resolve("snapshot")), "Webpages must not create a deployment snapshot");
    }

    private void assertTemporaryFilesCleaned() throws IOException {
        try (var files = Files.list(temporary.resolve("tmp"))) {
            assertEquals(List.of(), files.toList(), "Scripts must clean their invocation-owned temporary files");
        }
    }

    @Test
    void failedDeploymentDoesNotStartForwarding() throws Exception {
        state("nodes", NODES);
        state("failure", "deployment");
        Result result = run("up.sh");
        assertEquals(78, result.exit(), result.output());
        assertTrue(events().stream().noneMatch(line -> line.contains(" port-forward ")), events().toString());
        assertInvocationCleaned();
    }

    @Test
    void forwardingOwnsTheForegroundAndCanBeInterruptedWithoutStoppingTheCluster() throws Exception {
        for (String signal : List.of("INT", "TERM")) {
            state("nodes", NODES);
            state("running", "true");
            state("block-forward", "");
            for (String service : PAGE_SERVICES) Files.deleteIfExists(temporary.resolve("forward-pid-" + service));
            try (Running up = start("webpages.sh")) {
                awaitForwarding(up);
                assertTrue(up.process().isAlive());
                assertTrue(forwardPids().stream().allMatch(pid -> ProcessHandle.of(pid).orElseThrow().isAlive()),
                        "All tunnels must run under the foreground owner");
                assertFalse(Files.exists(lock()), "Webpage forwarding must not retain the lifecycle lock");
                Process kill = new ProcessBuilder("/bin/kill", "-" + signal, Long.toString(up.process().pid())).start();
                try {
                    assertTrue(kill.waitFor(5, TimeUnit.SECONDS));
                    assertEquals(0, kill.exitValue());
                } finally {
                    kill.destroyForcibly();
                }
                assertTrue(up.process().waitFor(5, TimeUnit.SECONDS), signal + " must stop forwarding promptly");
                assertEquals(signal.equals("INT") ? 130 : 143, up.process().exitValue());
                assertForwardsStopped();
                assertEquals("true", Files.readString(temporary.resolve("running")), "Interrupting forwarding must leave the cluster running");
            }
        }
    }

    @Test
    void downCanStopTheClusterWhileForwardingIsRunning() throws Exception {
        state("nodes", NODES);
        state("block-forward", "");
        try (Running up = start("webpages.sh")) {
            awaitForwarding(up);
            assertFalse(Files.exists(lock()), "Webpage forwarding must not retain the lifecycle lock");
            Result down = run("down.sh");
            assertEquals(0, down.exit(), down.output());
            assertEquals("false\n", Files.readString(temporary.resolve("running")));
            assertTrue(up.process().isAlive(), "Forwarding must remain in the foreground until it exits");
        }
    }

    @Test
    void forwardingFailureAfterStartupStopsAllTunnels() throws Exception {
        state("nodes", NODES);
        state("block-forward", "");
        state("failure", "forward-exit-order");
        Result result = run("webpages.sh");
        assertEquals(79, result.exit(), result.output());
        assertTrue(result.output().contains("http://127.0.0.1:18081/orders/"), result.output());
        assertTrue(result.output().contains("order forwarding stopped"), result.output());
        assertForwardsStopped();
        assertEquals("true", Files.readString(temporary.resolve("running")));
    }

    private void assertPageSessionEnded(Result result) {
        assertEquals(1, result.exit(), "A clean forward exit still loses required page access: " + result.output());
        assertTrue(result.output().contains("Product page:"), result.output());
        assertTrue(result.output().contains("Order page:"), result.output());
        assertTrue(result.output().contains("forwarding stopped (exit 0)"), result.output());
    }

    private List<Long> forwardPids() throws IOException {
        List<Long> pids = new ArrayList<>();
        for (String service : PAGE_SERVICES) {
            pids.add(Long.parseLong(Files.readString(temporary.resolve("forward-pid-" + service)).trim()));
        }
        return pids;
    }

    private void assertForwardsStopped() throws IOException {
        assertTrue(forwardPids().stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)),
                "All invocation-owned tunnels must be reaped");
    }

    private void awaitForwarding(Running up) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.readString(up.output()).contains("Keep this terminal open") && up.process().isAlive()
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.readString(up.output()).contains("Keep this terminal open"), Files.readString(up.output()));
    }

    private void assertInvocationCleaned() throws IOException {
        assertFalse(Files.exists(lock()), "Deployment must release the lifecycle lock");
        Path snapshot = Path.of(Files.readString(temporary.resolve("snapshot")));
        assertFalse(Files.exists(snapshot), "The deployment snapshot must be removed on exit");
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

    private Running start(String script, String... arguments) throws IOException {
        Path output = temporary.resolve("output-" + invocation++);
        List<String> command = new ArrayList<>(List.of("/bin/bash", temporary.resolve("scripts").resolve(script).toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(temporary.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(Map.of("PATH", temporary.resolve("bin") + ":" + System.getenv("PATH"),
                "TMPDIR", temporary.resolve("tmp").toString(), "FIXTURE", temporary.toString(), "REAL_HELM", helm));
        builder.environment().remove("SHARDSHOP_ENV_VALUES");
        builder.environment().remove("SHARDSHOP_INVENTORY");
        Process process = builder.start();
        process.getOutputStream().close();
        return new Running(process, output);
    }

    private Result run(String script, String... arguments) throws Exception {
        try (Running running = start(script, arguments)) {
            assertTrue(running.process().waitFor(30, TimeUnit.SECONDS), "Startup script exceeded its test deadline");
            assertTrue(Files.size(running.output()) <= 65_536, "Unexpectedly large lifecycle output");
            return new Result(running.process().exitValue(), Files.readString(running.output()));
        }
    }

    private record Running(Process process, Path output) implements AutoCloseable {
        @Override public void close() {
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
