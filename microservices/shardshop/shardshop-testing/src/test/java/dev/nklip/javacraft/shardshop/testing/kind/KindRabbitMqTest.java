package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class KindRabbitMqTest {
    private JsonNode expected;
    private JsonNode flags;
    private ArrayNode queues;
    private ArrayNode exchanges;
    private ArrayNode bindings;
    private ArrayNode policies;

    @BeforeEach
    void observationsMatchTheRenderedContract() throws IOException {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        expected = KindLab.parse(Files.readString(source.resolve("infra/helm/shardshop/files/rabbitmq/definitions.json")));
        flags = KindLab.parse("[{\"name\":\"stream_queue\",\"state\":\"enabled\"}]");
        policies = expected.path("policies").deepCopy();
        exchanges = expected.path("exchanges").deepCopy();
        queues = expected.path("queues").deepCopy();
        for (JsonNode item : queues) {
            ObjectNode queue = (ObjectNode) item;
            boolean parking = queue.path("name").asText().endsWith(".dlq");
            JsonNode policy = policies.get(parking ? 2 : queue.path("name").asText().startsWith("ledger") ? 0 : 1);
            queue.put("type", "quorum");
            queue.set("policy", policy.path("name"));
            queue.set("effective_policy_definition", policy.path("definition").deepCopy());
        }
        bindings = ((ObjectNode) expected).arrayNode();
        for (JsonNode original : expected.path("bindings")) {
            ObjectNode binding = original.deepCopy();
            binding.set("source_name", binding.remove("source"));
            binding.set("destination_name", binding.remove("destination"));
            binding.set("destination_kind", binding.remove("destination_type"));
            bindings.add(binding);
        }
        // This server-created binding must not be mistaken for unexpected explicit routing.
        bindings.addObject().put("source_name", "").put("destination_name", "ledger.record-order")
                .put("destination_kind", "queue").put("routing_key", "ledger.record-order").putObject("arguments");
    }

    @Test
    void acceptsExactLiveTopologyAndServerDefaultBindings() {
        assertDoesNotThrow(this::validate);
    }

    @Test
    void rejectsMissingFeatureFlagOrAnyDisabledState() {
        for (String invalid : List.of("[]", "[{\"name\":\"stream_queue\",\"state\":\"disabled\"}]",
                "[{\"name\":\"stream_queue\",\"state\":\"unsupported\"}]")) {
            flags = KindLab.parse(invalid);
            assertThrows(IOException.class, this::validate);
        }
    }

    @Test
    void rejectsMissingQueuesAndMutatedDurabilityTypeOrArguments() throws Exception {
        ArrayNode correct = queues.deepCopy();
        queues.remove(0);
        assertThrows(IOException.class, this::validate);
        queues = correct.deepCopy();
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(queue -> queue.put("durable", false),
                queue -> queue.put("auto_delete", true), queue -> queue.put("type", "classic"),
                queue -> ((ObjectNode) queue.path("arguments")).put("x-quorum-initial-group-size", 3),
                queue -> ((ObjectNode) queue.path("arguments")).put("x-delivery-limit", 5),
                queue -> ((ObjectNode) queue.path("arguments")).put("x-message-ttl", 1000))) {
            mutation.accept((ObjectNode) queues.get(0));
            assertThrows(IOException.class, this::validate);
            queues = correct.deepCopy();
        }
    }

    @Test
    void rejectsDriftInInstalledAndEffectivePolicies() {
        ((ObjectNode) policies.get(0).path("definition")).put("overflow", "drop-head");
        assertThrows(IOException.class, this::validate);
        policies = expected.path("policies").deepCopy();
        ((ObjectNode) queues.get(2).path("effective_policy_definition")).put("delivery-limit", 20);
        assertThrows(IOException.class, this::validate);
    }

    @Test
    void rejectsExtraPoliciesWrongExchangeDurabilityAndMissingBindings() {
        policies.addObject().put("name", "overlapping-policy");
        assertThrows(IOException.class, this::validate);
        policies.remove(policies.size() - 1);
        ((ObjectNode) exchanges.get(0)).put("durable", false);
        assertThrows(IOException.class, this::validate);
        exchanges = expected.path("exchanges").deepCopy();
        bindings.remove(0);
        assertThrows(IOException.class, this::validate);
    }

    @Test
    void acceptsStructuredOrJsonEncodedCliPolicyDefinitions() {
        for (JsonNode policy : policies) ((ObjectNode) policy).put("definition", policy.path("definition").toString());
        assertDoesNotThrow(this::validate);
    }

    @Test
    void acceptsNativeCliTypedAmqpTablesAndEmptyArgumentLists() {
        for (JsonNode item : queues) {
            ObjectNode queue = (ObjectNode) item;
            JsonNode arguments = queue.path("arguments");
            ArrayNode table = queue.arrayNode();
            for (var entry : arguments.properties()) {
                table.addArray().add(entry.getKey()).add(entry.getValue().isTextual() ? "longstr" : "signedint").add(entry.getValue());
            }
            queue.set("arguments", table);
        }
        for (JsonNode exchange : exchanges) ((ObjectNode) exchange).putArray("arguments");
        for (JsonNode binding : bindings) ((ObjectNode) binding).putArray("arguments");
        assertDoesNotThrow(this::validate);
    }

    @Test
    void currentBrokerRevisionMustUseTheInvocationImageAndConfiguration() {
        ObjectNode expectedStatefulSet = (ObjectNode) KindLab.parse("""
                {"spec":{"template":{"metadata":{"annotations":{"shardshop.javacraft/configuration":"desired-checksum"}},
                "spec":{"containers":[{"name":"rabbitmq","image":"rabbitmq@sha256:desired"}]}}}}
                """);
        ObjectNode liveStatefulSet = expectedStatefulSet.deepCopy();
        ObjectNode livePod = expectedStatefulSet.path("spec").path("template").deepCopy();
        // Admission adds fields; comparison intentionally selects only the declared image and checksum.
        livePod.withObject("/spec").put("dnsPolicy", "ClusterFirst");
        assertDoesNotThrow(() -> KindRabbitMq.validateDesiredDeployment(expectedStatefulSet, liveStatefulSet, livePod));
        ((ObjectNode) liveStatefulSet.path("spec").path("template").path("spec").path("containers").get(0)).put("image", "old-image");
        assertThrows(IOException.class, () -> KindRabbitMq.validateDesiredDeployment(expectedStatefulSet, liveStatefulSet, livePod));
        ((ObjectNode) liveStatefulSet.path("spec").path("template").path("spec").path("containers").get(0)).put("image", "rabbitmq@sha256:desired");
        ((ObjectNode) livePod.path("spec").path("containers").get(0)).put("image", "old-pod-image");
        assertThrows(IOException.class, () -> KindRabbitMq.validateDesiredDeployment(expectedStatefulSet, liveStatefulSet, livePod));
        ((ObjectNode) livePod.path("spec").path("containers").get(0)).put("image", "rabbitmq@sha256:desired");
        ((ObjectNode) liveStatefulSet.path("spec").path("template").path("metadata").path("annotations"))
                .put("shardshop.javacraft/configuration", "old-checksum");
        assertThrows(IOException.class, () -> KindRabbitMq.validateDesiredDeployment(expectedStatefulSet, liveStatefulSet, livePod));
        ((ObjectNode) liveStatefulSet.path("spec").path("template").path("metadata").path("annotations"))
                .put("shardshop.javacraft/configuration", "desired-checksum");
        ((ObjectNode) livePod.path("metadata").path("annotations")).put("shardshop.javacraft/configuration", "old-pod-checksum");
        assertThrows(IOException.class, () -> KindRabbitMq.validateDesiredDeployment(expectedStatefulSet, liveStatefulSet, livePod));
    }

    @Test
    void retainedStorageMustBeBoundMountedAndReadyAtTheCurrentRevision() {
        JsonNode statefulSet = KindLab.parse("""
                {"metadata":{"generation":2},"spec":{"replicas":1},"status":{"observedGeneration":2,
                "readyReplicas":1,"updatedReplicas":1,"currentRevision":"revision-2","updateRevision":"revision-2"}}
                """);
        JsonNode pod = KindLab.parse("""
                {"metadata":{},"spec":{"volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"data-shardshop-rabbitmq-0"}}]},
                "status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}]}}
                """);
        JsonNode pvc = KindLab.parse("""
                {"metadata":{"name":"data-shardshop-rabbitmq-0"},"spec":{"volumeName":"persistent-volume"},"status":{"phase":"Bound"}}
                """);
        assertDoesNotThrow(() -> KindRabbitMq.validateStorageAndReadiness(statefulSet, pod, pvc));
        assertThrows(IOException.class, () -> KindRabbitMq.validateStorageAndReadiness(KindLab.parse("{}"), pod, pvc));
        assertThrows(IOException.class, () -> KindRabbitMq.validateStorageAndReadiness(statefulSet, KindLab.parse("{}"), pvc));
        assertThrows(IOException.class, () -> KindRabbitMq.validateStorageAndReadiness(statefulSet, pod, KindLab.parse("{}")));
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> ((ObjectNode) node.path("status")).put("observedGeneration", 1),
                node -> ((ObjectNode) node.path("status")).put("readyReplicas", 0),
                node -> ((ObjectNode) node.path("status")).put("currentRevision", "old-revision"))) {
            ObjectNode changed = statefulSet.deepCopy();
            mutation.accept(changed);
            assertThrows(IOException.class, () -> KindRabbitMq.validateStorageAndReadiness(changed, pod, pvc));
        }
        ObjectNode unbound = pvc.deepCopy();
        ((ObjectNode) unbound.path("status")).put("phase", "Pending");
        assertThrows(IOException.class, () -> KindRabbitMq.validateStorageAndReadiness(statefulSet, pod, unbound));
        ObjectNode wrongMount = pod.deepCopy();
        ((ObjectNode) wrongMount.path("spec").path("volumes").get(0).path("persistentVolumeClaim")).put("claimName", "other-claim");
        assertThrows(IOException.class, () -> KindRabbitMq.validateStorageAndReadiness(statefulSet, wrongMount, pvc));
    }

    private void validate() throws IOException {
        KindRabbitMq.validateTopology(expected, flags, queues, exchanges, bindings, policies);
    }
}
