package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Response;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.KubernetesApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.KubernetesApi.Access;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime.Endpoints;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.testcontainers.containers.Container.ExecResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Product reserves its generator ID in the Kubernetes API of the scenario at each JVM start. */
public class GeneratorAllocationStepDefinitions {
    private static final Pattern RESERVED = Pattern.compile("Reserved generator ID ([0-9]+) for this JVM start\\.");
    private static final Duration EXIT = Duration.ofSeconds(60);
    /** A name in the reserved test domain; the k3s server certificate names 127.0.0.1 and localhost, not this name. */
    private static final String UNNAMED_HOST = "kubernetes-api.test";

    private final KubernetesApi kubernetes;
    private final ProductRuntime product;
    private final CatalogApi api;
    private final List<PackagedApplication> checks = new ArrayList<>();
    private Path tokenFile;
    private PackagedApplication failedStart;
    private long failedStartNanos;

    public GeneratorAllocationStepDefinitions(KubernetesApi kubernetes, ProductRuntime product, CatalogApi api) {
        this.kubernetes = kubernetes;
        this.product = product;
        this.api = api;
    }

    @Given("a Kubernetes API with the generator RBAC and retention policy of the chart")
    public void kubernetesApi() throws IOException, InterruptedException {
        kubernetes.start();
    }

    @Given("the generator registry at high-water {int}")
    public void registry(int highWater) throws IOException, InterruptedException {
        kubernetes.createRegistry(highWater);
    }

    @Given("the service account {string} may only get the registry")
    public void registryReader(String account) throws IOException, InterruptedException {
        kubernetes.createRegistryReader(account);
    }

    @When("product runs as the service account {string}")
    public void productRuns(String account) throws IOException, InterruptedException {
        product.reserveGeneratorIds(kubernetes.productOptions(kubernetes.tokenFile(account), kubernetes.registryUid(),
                kubernetes.serverCa()));
        product.start(Endpoints.PRIMARIES);
    }

    @When("product restarts")
    public void productRestarts() throws IOException, InterruptedException {
        product.restart(Endpoints.PRIMARIES);
    }

    @When("{int} product JVMs check their startup at the same time as the service account {string}")
    public void concurrentChecks(int count, String account) throws IOException, InterruptedException {
        List<String> options = startupCheckOptions(kubernetes.productOptions(kubernetes.tokenFile(account),
                kubernetes.registryUid(), kubernetes.serverCa()));
        Path directory = ShardShopFiles.newWorkDirectory("generator-checks-");
        for (int jvm = 0; jvm < count; jvm++) {
            checks.add(PackagedApplication.start("shardshop-product", directory, options, List.of("--check-startup")));
        }
        for (PackagedApplication check : checks) {
            assertEquals(0, check.awaitExit(EXIT), check.tail());
        }
    }

    @When("product starts as the service account {string}")
    public void productStarts(String account) throws IOException, InterruptedException {
        startExpectingFailure(failureOptions(account, kubernetes.registryUid(), kubernetes.serverCa()));
    }

    @When("product starts as the service account {string} while the API server does not answer")
    public void productStartsWhileApiServerDoesNotAnswer(String account) throws IOException, InterruptedException {
        // kubectl runs in the paused container too, thus the token and the CA file come first.
        List<String> options = failureOptions(account, kubernetes.registryUid(), kubernetes.serverCa());
        kubernetes.pause();
        startExpectingFailure(options);
    }

    @When("product starts as the service account {string} with the UID of another registry")
    @When("product starts as the service account {string} without a registry")
    public void productStartsWithAnotherUid(String account) throws IOException, InterruptedException {
        startExpectingFailure(failureOptions(account, UUID.randomUUID().toString(), kubernetes.serverCa()));
    }

    @When("product starts as the service account {string} and trusts the client CA instead of the server CA")
    public void productStartsWithClientCa(String account) throws IOException, InterruptedException {
        startExpectingFailure(failureOptions(account, kubernetes.registryUid(), kubernetes.clientCa()));
    }

    @When("product starts as the service account {string} with a host name that the server certificate does not name")
    public void productStartsWithAnUnnamedHost(String account) throws IOException, InterruptedException {
        // OpenJDK then reads host names only from this file: the name points to the API server at 127.0.0.1.
        Path hosts = Files.writeString(ShardShopFiles.newWorkDirectory("hosts-").resolve("hosts"),
                "127.0.0.1 " + UNNAMED_HOST + "\n");
        List<String> options = new ArrayList<>(failureOptions(account, kubernetes.url(UNNAMED_HOST),
                kubernetes.registryUid(), kubernetes.serverCa()));
        options.add("-Djdk.net.hosts.file=" + hosts);
        startExpectingFailure(options);
    }

    @Then("product logged that it reserved the generator ID {int}")
    public void productReserved(int generatorId) {
        assertEquals(List.of(generatorId), reservedIds(product.application()), product.application().tail());
    }

    @Then("a new seller of product has the generator ID {int}")
    public void sellerCarriesGeneratorId(int generatorId) throws IOException {
        Response response = api.createSeller("generator-" + UUID.randomUUID(),
                CatalogApi.sellerRequest("Generator check company", "US"));
        assertEquals(201, response.status());
        JsonNode seller = Json.parse(response.body());
        assertEquals(generatorId, (Long.parseLong(seller.get("sellerId").textValue()) >>> 12) & 1023);
    }

    @Then("the JVMs logged the distinct generator IDs 1 to {int}, and no warning or error")
    public void distinctIds(int last) {
        Set<Integer> expected = IntStream.rangeClosed(1, last).boxed().collect(Collectors.toCollection(TreeSet::new));
        Set<Integer> reserved = new TreeSet<>();
        for (PackagedApplication check : checks) {
            List<Integer> ids = reservedIds(check);
            assertEquals(1, ids.size(), check.tail());
            assertTrue(reserved.add(ids.getFirst()), "Two JVMs reserved the generator ID " + ids.getFirst());
            assertEquals(List.of(), check.problems(), "Warnings and errors of " + check.log());
        }
        assertEquals(expected, reserved);
    }

    @Then("the registry high-water is {int}")
    public void highWater(int highWater) throws IOException, InterruptedException {
        assertEquals(highWater, kubernetes.highWater());
    }

    @Then("product stops with {string} and reserves no ID")
    public void productStops(String message) throws InterruptedException, IOException {
        productStopsWithin(message, (int) EXIT.toSeconds());
    }

    @Then("product stops with {string} within {int} seconds and reserves no ID")
    public void productStopsWithin(String message, int seconds) throws InterruptedException, IOException {
        assertNotEquals(0, failedStart.awaitExit(EXIT), failedStart.tail());
        assertTrue(System.nanoTime() - failedStartNanos < Duration.ofSeconds(seconds).toNanos(),
                "Product stopped later than " + seconds + " seconds after its start");
        String log = String.join("\n", failedStart.lines());
        assertTrue(log.contains(message), failedStart.tail());
        assertEquals(List.of(), reservedIds(failedStart), failedStart.tail());
        // Only fixed messages reach the log: no token and no text of an API response.
        assertFalse(log.contains(Files.readString(tokenFile)), "The log contains the service account token");
        assertFalse(log.contains("forbidden") || log.contains("\"kind\""), "The log contains an API response");
    }

    @Then("the service accounts have exactly these permissions on ConfigMaps in the namespace shardshop:")
    public void permissions(DataTable table) throws IOException, InterruptedException {
        List<Map<String, String>> rows = table.asMaps();
        List<String> columns = table.row(0).subList(1, table.row(0).size());
        List<Access> questions = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Map<String, String> row : rows) {
            for (String column : columns) {
                String[] verbAndObject = column.split(" ");
                String name = switch (verbAndObject[1]) {
                    case "registry" -> KubernetesApi.REGISTRY;
                    case "identity" -> "shardshop-generator-identity";
                    case "routing" -> "shardshop-routing";
                    case "configmaps" -> null;
                    default -> throw new IllegalArgumentException("Unknown object in the column " + column);
                };
                questions.add(new Access(row.get("account"), verbAndObject[0], name));
                expected.add(row.get("account") + " " + column + ": " + row.get(column));
            }
        }
        List<Boolean> allowed = kubernetes.allowed(questions);
        List<String> actual = new ArrayList<>();
        for (int question = 0; question < questions.size(); question++) {
            String answer = expected.get(question);
            actual.add(answer.substring(0, answer.lastIndexOf(':') + 1) + (allowed.get(question) ? " yes" : " no"));
        }
        assertEquals(expected, actual);
    }

    @Then("the retention policy rejects these changes of the registry:")
    public void retentionPolicy(List<String> changes) throws IOException, InterruptedException {
        for (String change : changes) {
            ExecResult result;
            if (change.startsWith("the high-water ")) {
                result = kubernetes.tryKubectl("-n", KubernetesApi.NAMESPACE, "patch", "configmap",
                        KubernetesApi.REGISTRY, "--type=merge", "-p",
                        "{\"data\":{\"highWaterMark\":\"" + change.substring("the high-water ".length()) + "\"}}");
            } else if (change.equals("a deletion")) {
                result = kubernetes.tryKubectl("-n", KubernetesApi.NAMESPACE, "delete", "configmap",
                        KubernetesApi.REGISTRY, "--wait=false");
            } else {
                throw new IllegalArgumentException("Unknown change " + change);
            }
            assertTrue(KubernetesApi.deniedByThePolicy(result), change + ": " + result.getStderr());
        }
    }

    /** The JVMs that the steps of this class start; ProductRuntime stops its own JVMs. */
    @After
    public void stopJvms() throws InterruptedException {
        for (PackagedApplication jvm : checks) {
            stop(jvm);
        }
        if (failedStart != null) {
            stop(failedStart);
        }
    }

    private List<String> failureOptions(String account, String uid, Path caFile) throws IOException,
            InterruptedException {
        return failureOptions(account, kubernetes.url(), uid, caFile);
    }

    private List<String> failureOptions(String account, String url, String uid, Path caFile) throws IOException,
            InterruptedException {
        tokenFile = kubernetes.tokenFile(account);
        return startupCheckOptions(kubernetes.productOptions(url, tokenFile, uid, caFile));
    }

    private void startExpectingFailure(List<String> options) throws IOException {
        failedStartNanos = System.nanoTime();
        // With --check-startup, an unexpected success exits with 0, and the exit code check fails at once.
        failedStart = PackagedApplication.start("shardshop-product", ShardShopFiles.newWorkDirectory("generator-"),
                options, List.of("--check-startup"));
    }

    /** One shard is sufficient: the startup check does not open a database connection. */
    private static List<String> startupCheckOptions(List<String> registryOptions) throws IOException {
        Path routing = Files.writeString(Files.createTempFile(ShardShopFiles.newWorkDirectory("routing-"), "routing-",
                ".properties"), "shardshop.routing.shards=shard-a\nshardshop.routing.regions=US\n");
        List<String> options = new ArrayList<>(List.of("-Dshardshop.routing.config=" + routing.toUri(),
                "-Dquarkus.http.host=127.0.0.1", "-Dquarkus.http.port=0"));
        options.addAll(registryOptions);
        return options;
    }

    private static List<Integer> reservedIds(PackagedApplication jvm) {
        List<Integer> ids = new ArrayList<>();
        for (String line : jvm.lines()) {
            Matcher reserved = RESERVED.matcher(line);
            if (reserved.find()) {
                ids.add(Integer.parseInt(reserved.group(1)));
            }
        }
        return ids;
    }

    private static void stop(PackagedApplication jvm) throws InterruptedException {
        if (jvm.isAlive()) {
            jvm.stop();
        }
    }
}
