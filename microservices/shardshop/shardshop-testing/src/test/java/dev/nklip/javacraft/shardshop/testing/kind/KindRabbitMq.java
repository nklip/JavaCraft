package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only broker acceptance gate. Commands never request users, passwords or exported definitions. */
public final class KindRabbitMq {
    private static final String POD = "shardshop-rabbitmq-0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final KindLab lab;

    public KindRabbitMq() { this(new KindLab()); }
    KindRabbitMq(KindLab lab) { this.lab = lab; }

    public void verify() throws IOException, InterruptedException {
        Desired desired = desired();
        lab.await("RabbitMQ readiness and durable topology", Duration.ofSeconds(300), () -> {
            JsonNode statefulSet = KindLab.parse(lab.kubectl("get", "statefulset", "shardshop-rabbitmq", "-o", "json"));
            JsonNode pod = KindLab.parse(lab.kubectl("get", "pod", POD, "-o", "json"));
            validateStorageAndReadiness(statefulSet, pod, KindLab.parse(lab.kubectl("get", "pvc", "data-" + POD, "-o", "json")));
            validateDesiredDeployment(desired.statefulSet(), statefulSet, pod);
            diagnostics("check_running");
            diagnostics("check_local_alarms");
            validateTopology(desired.topology(), list("list_feature_flags", false, "name", "state"),
                    list("list_queues", true, "name", "durable", "auto_delete", "arguments", "policy", "effective_policy_definition", "type"),
                    list("list_exchanges", true, "name", "type", "durable", "auto_delete", "internal", "arguments"),
                    list("list_bindings", true, "source_name", "destination_name", "destination_kind", "routing_key", "arguments"),
                    list("list_policies", true));
            return true;
        });
    }

    private Desired desired() throws IOException {
        JsonNode topology = null;
        JsonNode statefulSet = null;
        try (var input = Files.newInputStream(lab.snapshot().resolve("infrastructure.yaml"))) {
            for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(input)) {
                JsonNode resource = JSON.valueToTree(document);
                if (resource == null) continue;
                if (resource.path("kind").asText().equals("ConfigMap")
                        && resource.path("metadata").path("name").asText().equals("shardshop-rabbitmq-config")) {
                    topology = KindLab.parse(resource.path("data").path("definitions.json").asText());
                } else if (resource.path("kind").asText().equals("StatefulSet")
                        && resource.path("metadata").path("name").asText().equals("shardshop-rabbitmq")) {
                    statefulSet = resource;
                }
            }
        }
        require(topology != null && topology.path("queues").isArray() && !topology.path("queues").isEmpty(),
                "RabbitMQ topology is missing from the invocation snapshot");
        require(statefulSet != null, "RabbitMQ StatefulSet is missing from the invocation snapshot");
        return new Desired(topology, statefulSet);
    }

    private record Desired(JsonNode topology, JsonNode statefulSet) { }

    private void diagnostics(String check) throws IOException, InterruptedException {
        lab.kubectl(Duration.ofSeconds(20), "exec", POD, "-c", "rabbitmq", "--", "rabbitmq-diagnostics", "-q", "-t", "10", check);
    }

    private JsonNode list(String command, boolean vhost, String... fields) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of("exec", POD, "-c", "rabbitmq", "--", "rabbitmqctl",
                "--quiet", "--timeout", "10", "--formatter=json", command));
        if (vhost) arguments.addAll(List.of("-p", "shardshop"));
        arguments.addAll(List.of(fields));
        String result = lab.kubectl(Duration.ofSeconds(20), arguments.toArray(String[]::new));
        try {
            JsonNode parsed = KindLab.parse(result);
            require(parsed.isArray(), "RabbitMQ observation did not return a JSON array");
            return parsed;
        } catch (RuntimeException failure) {
            // Avoid echoing command output through parser exceptions.
            throw new IOException("RabbitMQ observation returned invalid JSON");
        }
    }

    static void validateDesiredDeployment(JsonNode expectedStatefulSet, JsonNode actualStatefulSet, JsonNode actualPod) throws IOException {
        JsonNode expectedTemplate = expectedStatefulSet.path("spec").path("template");
        JsonNode actualTemplate = actualStatefulSet.path("spec").path("template");
        String desiredImage = brokerImage(expectedTemplate);
        require(!desiredImage.isBlank() && desiredImage.equals(brokerImage(actualTemplate)) && desiredImage.equals(brokerImage(actualPod)),
                "RabbitMQ StatefulSet or pod image differs from the invocation snapshot; reconcile infrastructure with cluster-create.sh");
        String desiredChecksum = configurationChecksum(expectedTemplate);
        require(!desiredChecksum.isBlank() && desiredChecksum.equals(configurationChecksum(actualTemplate))
                        && desiredChecksum.equals(configurationChecksum(actualPod)),
                "RabbitMQ StatefulSet or pod configuration differs from the invocation snapshot; reconcile infrastructure with cluster-create.sh");
    }

    private static String brokerImage(JsonNode podTemplate) {
        return KindLab.items(podTemplate.path("spec").path("containers")).stream()
                .filter(container -> container.path("name").asText().equals("rabbitmq"))
                .map(container -> container.path("image").asText()).findFirst().orElse("");
    }

    private static String configurationChecksum(JsonNode podTemplate) {
        return podTemplate.path("metadata").path("annotations").path("shardshop.javacraft/configuration").asText();
    }

    static void validateStorageAndReadiness(JsonNode statefulSet, JsonNode pod, JsonNode pvc) throws IOException {
        require(statefulSet.path("spec").path("replicas").asInt() == 1
                        && statefulSet.path("status").path("observedGeneration").asLong(-1) == statefulSet.path("metadata").path("generation").asLong(-2)
                        && statefulSet.path("status").path("readyReplicas").asInt() == 1
                        && statefulSet.path("status").path("updatedReplicas").asInt() == 1
                        && !statefulSet.path("status").path("currentRevision").asText().isEmpty()
                        && statefulSet.path("status").path("currentRevision").equals(statefulSet.path("status").path("updateRevision")),
                "RabbitMQ StatefulSet has not reconciled its single ready replica");
        require(!pod.path("metadata").hasNonNull("deletionTimestamp") && pod.path("status").path("phase").asText().equals("Running")
                        && KindLab.items(pod.path("status").path("conditions")).stream()
                        .anyMatch(condition -> condition.path("type").asText().equals("Ready") && condition.path("status").asText().equals("True")),
                "RabbitMQ pod is missing, terminating or unready");
        require(pvc.path("metadata").path("name").asText().equals("data-" + POD)
                        && !pvc.path("metadata").hasNonNull("deletionTimestamp")
                        && pvc.path("status").path("phase").asText().equals("Bound")
                        && !pvc.path("spec").path("volumeName").asText().isBlank(), "RabbitMQ persistent storage is missing or unbound");
        require(KindLab.items(pod.path("spec").path("volumes")).stream().anyMatch(volume -> volume.path("name").asText().equals("data")
                && volume.path("persistentVolumeClaim").path("claimName").asText().equals("data-" + POD)), "RabbitMQ pod does not mount its retained data claim");
    }

    static void validateTopology(JsonNode expected, JsonNode flags, JsonNode queues, JsonNode exchanges,
                                 JsonNode bindings, JsonNode policies) throws IOException {
        require(KindLab.items(flags).stream().anyMatch(flag -> flag.path("name").asText().equals("stream_queue")
                && flag.path("state").asText().equals("enabled")), "RabbitMQ stream_queue feature flag is not enabled");
        Map<String, JsonNode> actualPolicies = byName(policies);
        require(actualPolicies.keySet().equals(byName(expected.path("policies")).keySet()), "RabbitMQ policy inventory differs from the snapshot");
        for (JsonNode policy : expected.path("policies")) {
            JsonNode actual = actualPolicies.get(policy.path("name").asText());
            fieldsEqual(policy, actual, "RabbitMQ policy", "name", "vhost", "pattern", "apply-to", "priority");
            require(policy.path("definition").equals(object(actual.path("definition"))), "RabbitMQ policy definition differs from the snapshot");
        }
        Map<String, JsonNode> actualQueues = byName(queues);
        require(actualQueues.keySet().equals(byName(expected.path("queues")).keySet()), "RabbitMQ queue inventory differs from the snapshot");
        for (JsonNode queue : expected.path("queues")) {
            JsonNode actual = actualQueues.get(queue.path("name").asText());
            fieldsEqual(queue, actual, "RabbitMQ queue", "name", "durable", "auto_delete");
            require(actual.path("type").asText().equals("quorum"), "RabbitMQ queue is not quorum");
            require(queue.path("arguments").equals(object(actual.path("arguments"))), "RabbitMQ queue arguments differ from the snapshot");
            List<JsonNode> matching = KindLab.items(expected.path("policies")).stream().filter(policy -> Pattern.matches(
                    policy.path("pattern").asText(), queue.path("name").asText())).toList();
            require(matching.size() == 1, "RabbitMQ snapshot must give every queue one complete policy");
            JsonNode policy = matching.getFirst();
            require(actual.path("policy").equals(policy.path("name")) && object(actual.path("effective_policy_definition")).equals(policy.path("definition")),
                    "RabbitMQ queue effective policy differs from the snapshot");
        }
        Map<String, JsonNode> actualExchanges = byName(exchanges);
        Set<String> explicitExchanges = new HashSet<>(actualExchanges.keySet());
        explicitExchanges.removeIf(name -> name.isEmpty() || name.startsWith("amq."));
        require(explicitExchanges.equals(byName(expected.path("exchanges")).keySet()), "RabbitMQ exchange inventory differs from the snapshot");
        for (JsonNode exchange : expected.path("exchanges")) {
            JsonNode actual = actualExchanges.get(exchange.path("name").asText());
            require(actual != null, "RabbitMQ required exchange is missing");
            fieldsEqual(exchange, actual, "RabbitMQ exchange", "name", "type", "durable", "auto_delete", "internal");
            require(exchange.path("arguments").equals(object(actual.path("arguments"))), "RabbitMQ exchange arguments differ from the snapshot");
        }
        Set<JsonNode> desiredBindings = new HashSet<>();
        for (JsonNode binding : expected.path("bindings")) desiredBindings.add(bindingFields(binding, false));
        Set<JsonNode> actualBindings = new HashSet<>();
        for (JsonNode binding : bindings) {
            // RabbitMQ also creates each queue's implicit binding from the default exchange.
            if (!binding.path("source_name").asText().isEmpty()) actualBindings.add(bindingFields(binding, true));
        }
        require(desiredBindings.equals(actualBindings), "RabbitMQ explicit bindings differ from the snapshot");
    }

    private static JsonNode bindingFields(JsonNode binding, boolean cli) throws IOException {
        return JSON.createObjectNode().setAll(Map.of("source", binding.path(cli ? "source_name" : "source"),
                "destination", binding.path(cli ? "destination_name" : "destination"),
                "destination_type", binding.path(cli ? "destination_kind" : "destination_type"),
                "routing_key", binding.path("routing_key"), "arguments", object(binding.path("arguments"))));
    }

    private static JsonNode object(JsonNode value) throws IOException {
        JsonNode result = value;
        if (value.isTextual()) {
            try { result = KindLab.parse(value.asText()); }
            catch (RuntimeException ignored) { throw new IOException("RabbitMQ metadata contains invalid JSON"); }
        }
        // rabbitmqctl represents AMQP argument tables as typed triples, including [] for an empty table.
        if (result.isArray()) {
            var table = JSON.createObjectNode();
            for (JsonNode entry : result) {
                require(entry.isArray() && entry.size() == 3 && entry.get(0).isTextual() && entry.get(1).isTextual(),
                        "RabbitMQ metadata is not an AMQP argument table");
                require(!table.has(entry.get(0).asText()), "RabbitMQ metadata contains duplicate argument names");
                table.set(entry.get(0).asText(), entry.get(2));
            }
            return table;
        }
        require(result.isObject(), "RabbitMQ metadata is not a JSON object");
        return result;
    }

    private static Map<String, JsonNode> byName(JsonNode array) throws IOException {
        require(array.isArray(), "RabbitMQ inventory is missing");
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode item : array) {
            String name = item.path("name").asText();
            require(result.put(name, item) == null, "RabbitMQ inventory contains duplicate names");
        }
        return result;
    }

    private static void fieldsEqual(JsonNode expected, JsonNode actual, String description, String... fields) throws IOException {
        for (String field : fields) require(expected.path(field).equals(actual.path(field)), description + " " + field + " differs from the snapshot");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
