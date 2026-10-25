package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Observes the operator's reconciliation; never reconciles database state itself. */
public final class KindReadiness {
    private static final Duration DATABASE_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration SQL_TIMEOUT = Duration.ofSeconds(180);
    private static final Pattern QUANTITY = Pattern.compile("([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(.*)");
    private static final String PARAMETERS_SQL = "SELECT coalesce(json_object_agg(f.name, json_build_object('setting', f.setting, 'applied', f.applied, 'pending', s.pending_restart)), '{}'::json) FROM (SELECT DISTINCT ON (name) name, setting, applied FROM pg_file_settings ORDER BY name, seqno DESC) f JOIN pg_settings s USING (name)";
    private final KindLab lab;

    public KindReadiness() { this(new KindLab()); }
    KindReadiness(KindLab lab) { this.lab = lab; }

    public void dns() throws IOException, InterruptedException {
        String manifest = lab.snapshot().resolve("dns-probes.yaml").toString();
        Throwable primaryFailure = null;
        try {
            lab.kubectl("create", "-f", manifest);
            // Cover the pod's cold-start and execution deadline, plus termination grace.
            lab.kubectl(Duration.ofSeconds(1070), "--request-timeout=1060s", "wait", "--for=jsonpath={.status.phase}=Succeeded",
                    "--timeout=1050s", "-f", manifest);
        } catch (IOException failure) {
            IOException diagnostic = new IOException("Cluster DNS probes did not succeed.\n" + dnsStatus(manifest)
                    + "\n" + dnsRecovery(), failure);
            primaryFailure = diagnostic;
            throw diagnostic;
        } catch (InterruptedException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                lab.kubectl("delete", "-f", manifest, "--ignore-not-found", "--wait=false");
            } catch (IOException cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else System.err.println("DNS probe cleanup failed; the lifecycle command will retry cleanup when it exits.");
            } catch (InterruptedException cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
                Thread.currentThread().interrupt();
            }
        }
    }

    private String dnsRecovery() {
        // Use the same effective config as observer processes, without reading its contents.
        String config = lab.processBuilder("kubectl").environment().get("KUBECONFIG");
        String command = (config == null || config.isBlank() ? ""
                : "KUBECONFIG='" + config.replace("'", "'\"'\"'") + "' ")
                + "kubectl --context kind-" + lab.clusterName();
        return "For waiting containers, inspect pod events and pinned-image availability. "
                + "For running probes that cannot resolve DNS, inspect node addresses/pod CIDRs with " + command + " get nodes -o wide "
                + "and docker exec <node> ip route. If kindnet has a stale address or missing peer route, replace only its pod "
                + "on the affected node using " + command + " -n kube-system delete pod -l app=kindnet "
                + "--field-selector spec.nodeName=<node>, then rerun the failed lifecycle command.";
    }

    private String dnsStatus(String manifest) {
        try {
            var status = lab.kubectlAttempt("get", "-f", manifest,
                    "-o", "custom-columns=NAME:.metadata.name,NODE:.spec.nodeName,PHASE:.status.phase,"
                            + "WAITING:.status.containerStatuses[0].state.waiting.reason,MESSAGE:.status.containerStatuses[0].state.waiting.message,"
                            + "TERMINATED:.status.containerStatuses[0].state.terminated.reason,EXIT:.status.containerStatuses[0].state.terminated.exitCode");
            return status.exitCode() == 0 ? status.stdout().strip() : "DNS probe status unavailable.";
        } catch (IOException failure) {
            return "DNS probe status unavailable.";
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return "DNS probe status unavailable.";
        }
    }

    public void infrastructure() throws IOException, InterruptedException {
        String operatorImage = lab.kubectl("-n", "cnpg-system", "get", "deployment", "cnpg-cloudnative-pg",
                "-o", "jsonpath={.spec.template.spec.containers[0].image}").trim();
        if (operatorImage.isEmpty()) throw new IOException("CNPG operator image is missing");
        lab.kubectl(Duration.ofSeconds(320), "--request-timeout=310s", "wait", "--for=condition=Ready",
                "clusters.postgresql.cnpg.io", "--all", "--timeout=300s");
        for (String cluster : lab.clusters()) {
            lab.await(cluster + " database rollout", DATABASE_TIMEOUT, () -> {
                var current = lab.kubectlAttempt("get", "cluster", cluster, "-o", "json");
                var pods = lab.kubectlAttempt("get", "pods", "-l", selector(cluster), "-o", "json");
                if (current.exitCode() != 0 || pods.exitCode() != 0)
                    throw new IOException("Database rollout did not converge for " + cluster + ": API unavailable");
                List<String> pending = rolloutPending(KindLab.parse(current.stdout()), KindLab.parse(pods.stdout()), operatorImage);
                if (!pending.isEmpty()) throw new IOException("Database rollout did not converge for " + cluster + ": "
                        + String.join("; ", pending));
                return true;
            });
            lab.await(cluster + " primary Service connectivity", SQL_TIMEOUT, () -> {
                String primary = lab.kubectl("get", "cluster", cluster, "-o", "jsonpath={.status.currentPrimary}").trim();
                if (primary.isEmpty()) throw new IOException(cluster + " has no current primary");
                if (lab.kubectlAttempt("exec", primary, "-c", "postgres", "--",
                        "pg_isready", "-q", "-h", cluster + "-rw", "-t", "5").exitCode() != 0)
                    throw new IOException(cluster + "-rw does not accept connections from " + primary);
                return true;
            });
            checkParameters(cluster);
        }
        for (String shard : lab.shards()) checkStreaming(shard);
        for (String resource : List.of("databaseroles.postgresql.cnpg.io", "databases.postgresql.cnpg.io")) {
            lab.await(resource + " applied at current generation", SQL_TIMEOUT, () -> {
                var result = lab.kubectlAttempt("get", resource, "-o", "json");
                if (result.exitCode() != 0) return false;
                JsonNode list = KindLab.parse(result.stdout());
                if (resource.equals("databaseroles.postgresql.cnpg.io")) {
                    List<String> expected = new ArrayList<>();
                    for (String shard : lab.shards()) {
                        for (String role : List.of("catalog-owner", "catalog-reader", "catalog-writer", "catalog-reserver",
                                "ordering-owner", "ordering-writer", "ordering-reconciler", "order-cdc-reader",
                                "catalog-migrator", "ordering-migrator", "product-app", "order-app",
                                "ordering-operator", "ordering-cdc")) expected.add(shard + "-" + role);
                    }
                    for (String role : List.of("ledger-schema-owner", "ledger-writer", "ledger-migrator", "ledger-app"))
                        expected.add("ledger-db-" + role);
                    List<String> actual = KindLab.items(list).stream()
                            .map(item -> item.path("metadata").path("name").asText()).toList();
                    if (!actual.containsAll(expected)) return false;
                } else if (resource.equals("databases.postgresql.cnpg.io")) {
                    List<String> expected = new ArrayList<>();
                    for (String shard : lab.shards()) expected.add(shard + "-shardshop");
                    expected.add("ledger-db-ledger");
                    List<String> actual = KindLab.items(list).stream()
                            .map(item -> item.path("metadata").path("name").asText()).toList();
                    if (!actual.containsAll(expected)) return false;
                }
                return generationsApplied(list);
            });
        }
    }

    private void checkParameters(String cluster) throws IOException, InterruptedException {
        JsonNode expected = KindLab.parse(lab.kubectl("get", "cluster", cluster, "-o", "json"))
                .path("spec").path("postgresql").path("parameters");
        List<JsonNode> pods = KindLab.items(KindLab.parse(lab.kubectl("get", "pods", "-l", selector(cluster), "-o", "json")));
        if (pods.isEmpty()) throw new IOException(cluster + " has no database pods");
        for (JsonNode pod : pods) {
            String name = pod.path("metadata").path("name").asText();
            lab.await(name + " PostgreSQL parameters", SQL_TIMEOUT, () -> {
                var result = sql(name, PARAMETERS_SQL);
                if (result.exitCode() != 0) throw new IOException(name + " PostgreSQL settings unavailable");
                JsonNode actual = KindLab.parse(result.stdout());
                List<String> pending = new ArrayList<>();
                for (var entry : expected.properties()) {
                    if (!parameterApplied(entry.getValue(), actual.path(entry.getKey()))) pending.add(entry.getKey());
                }
                if (!pending.isEmpty()) throw new IOException(name + " has unapplied PostgreSQL parameters: "
                        + String.join(", ", pending));
                return true;
            });
        }
    }

    private void checkStreaming(String shard) throws IOException, InterruptedException {
        lab.await(shard + " exact streaming quorum standbys", SQL_TIMEOUT, () -> {
            var current = lab.kubectlAttempt("get", "cluster", shard, "-o", "json");
            var pods = lab.kubectlAttempt("get", "pods", "-l", selector(shard), "-o", "json");
            if (current.exitCode() != 0 || pods.exitCode() != 0)
                throw new IOException("Streaming did not converge for " + shard + ": API unavailable");
            JsonNode cluster = KindLab.parse(current.stdout());
            String primary = cluster.path("status").path("currentPrimary").asText();
            if (primary.isEmpty()) throw new IOException("Streaming did not converge for " + shard + ": primary unavailable");
            var result = sql(primary, KindTopology.STREAMING_QUORUM_SQL);
            if (result.exitCode() != 0) throw new IOException("Streaming did not converge for " + shard + ": replication unavailable");
            JsonNode podList = KindLab.parse(pods.stdout());
            JsonNode inventory = lab.inventory();
            int instances = inventory.path("instances").asInt();
            if (!streamingMatches(inventory, cluster, podList, result.stdout())) {
                List<String> expected = KindLab.items(podList).stream()
                        .filter(pod -> !pod.path("metadata").hasNonNull("deletionTimestamp"))
                        .map(pod -> pod.path("metadata").path("name").asText())
                        .filter(name -> !name.equals(primary)).sorted().toList();
                List<String> actual = result.stdout().strip().lines().toList();
                // The query returns only the connection count and application names, never SQL data or credentials.
                throw new IOException("Streaming did not converge for " + shard + ": " + primary + " has "
                        + (actual.isEmpty() ? "unknown" : actual.getFirst()) + " standby replication connections; streaming quorum standbys: "
                        + (actual.size() < 2 ? "none" : String.join(", ", actual.subList(1, actual.size())))
                        + "; expected " + (instances - 1) + " standbys: " + String.join(", ", expected));
            }
            return true;
        });
    }

    private KindLab.CommandResult sql(String pod, String sql) throws IOException, InterruptedException {
        return lab.kubectlAttempt("exec", pod, "-c", "postgres", "--", "env",
                "PGOPTIONS=-c statement_timeout=5000 -c lock_timeout=5000", "psql", "-X", "-qAt", "-v",
                "ON_ERROR_STOP=1", "-U", "postgres", "-d", "postgres", "-c", sql);
    }

    private static String selector(String cluster) { return "cnpg.io/cluster=" + cluster + ",cnpg.io/podRole=instance"; }

    static List<String> rolloutPending(JsonNode cluster, JsonNode podList, String operatorImage) {
        List<String> pending = new ArrayList<>();
        JsonNode spec = cluster.path("spec");
        List<JsonNode> pods = KindLab.items(podList);
        if (pods.size() != spec.path("instances").asInt()) pending.add("instances " + pods.size() + "/" + spec.path("instances").asInt());
        for (JsonNode pod : pods) {
            String name = pod.path("metadata").path("name").asText();
            JsonNode postgres = container(pod.path("spec").path("containers"), "postgres");
            JsonNode bootstrap = container(pod.path("spec").path("initContainers"), "bootstrap-controller");
            boolean ready = false;
            for (JsonNode condition : pod.path("status").path("conditions"))
                ready |= condition.path("type").asText().equals("Ready") && condition.path("status").asText().equals("True");
            if (pod.path("metadata").hasNonNull("deletionTimestamp") || !ready) pending.add(name + " not Ready");
            if (!bootstrap.path("image").asText().equals(operatorImage)) pending.add(name + " operator image");
            if (!postgres.path("image").asText().equals(spec.path("imageName").asText())) pending.add(name + " database image");
            if (!resources(postgres.path("resources")).equals(resources(spec.path("resources")))) pending.add(name + " resources");
        }
        return pending;
    }

    private static JsonNode container(JsonNode containers, String name) {
        for (JsonNode container : containers) if (container.path("name").asText().equals(name)) return container;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    static Map<String, Map<String, BigDecimal>> resources(JsonNode node) {
        Map<String, Map<String, BigDecimal>> normalized = new HashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isEmpty()) {
                Map<String, BigDecimal> values = new HashMap<>();
                entry.getValue().properties().forEach(value -> values.put(value.getKey(), quantity(value.getValue().asText())));
                normalized.put(entry.getKey(), values);
            }
        });
        normalized.getOrDefault("limits", Map.of()).forEach((key, value) ->
                normalized.computeIfAbsent("requests", ignored -> new HashMap<>()).putIfAbsent(key, value));
        return normalized;
    }

    static BigDecimal quantity(String input) {
        var match = QUANTITY.matcher(input);
        if (!match.matches()) throw new IllegalArgumentException("Invalid Kubernetes quantity: " + input);
        String suffix = match.group(2);
        BigDecimal multiplier;
        if (suffix.matches("[eE][+-]?\\d+")) multiplier = new BigDecimal("1" + suffix);
        else if (suffix.endsWith("i") && "KMGTPE".contains(suffix.substring(0, suffix.length() - 1)) && suffix.length() == 2)
            multiplier = BigDecimal.valueOf(1024).pow("KMGTPE".indexOf(suffix.charAt(0)) + 1);
        else multiplier = switch (suffix) {
            case "" -> BigDecimal.ONE;
            case "n" -> new BigDecimal("0.000000001");
            case "u" -> new BigDecimal("0.000001");
            case "m" -> new BigDecimal("0.001");
            case "k", "K" -> BigDecimal.valueOf(1000);
            case "M", "G", "T", "P", "E" -> BigDecimal.valueOf(1000).pow("MGTPE".indexOf(suffix) + 2);
            default -> throw new IllegalArgumentException("Invalid Kubernetes quantity suffix: " + input);
        };
        return new BigDecimal(match.group(1)).multiply(multiplier).stripTrailingZeros();
    }

    static boolean parametersApplied(JsonNode expected, JsonNode actual) {
        for (var entry : expected.properties()) {
            if (!parameterApplied(entry.getValue(), actual.path(entry.getKey()))) return false;
        }
        return true;
    }

    private static boolean parameterApplied(JsonNode expected, JsonNode actual) {
        return actual.path("setting").equals(expected) && actual.path("applied").isBoolean()
                && actual.path("applied").booleanValue() && actual.path("pending").isBoolean()
                && !actual.path("pending").booleanValue();
    }

    static boolean generationsApplied(JsonNode list) {
        List<JsonNode> resources = KindLab.items(list);
        return !resources.isEmpty() && resources.stream().allMatch(resource ->
                resource.path("status").has("observedGeneration")
                && resource.path("status").path("observedGeneration").equals(resource.path("metadata").path("generation"))
                && resource.path("status").path("applied").asBoolean());
    }

    static boolean streamingMatches(JsonNode inventory, JsonNode cluster, JsonNode pods, String output) {
        return streamingMatches(cluster.path("status").path("currentPrimary").asText(),
                inventory.path("instances").asInt(), pods, output);
    }

    static boolean streamingMatches(String primary, int instances, JsonNode pods, String output) {
        List<String> names = KindLab.items(pods).stream().filter(pod -> !pod.path("metadata").hasNonNull("deletionTimestamp"))
                .map(pod -> pod.path("metadata").path("name").asText()).sorted().toList();
        if (instances < 2 || names.size() != instances || !names.contains(primary)) return false;
        List<String> lines = output.strip().lines().toList();
        List<String> expected = names.stream().filter(name -> !name.equals(primary)).toList();
        return !lines.isEmpty() && lines.getFirst().equals(Integer.toString(instances - 1))
                && lines.subList(1, lines.size()).equals(expected);
    }
}
