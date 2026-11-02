package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Reads and syncs the Argo CD Applications of the AWS deployment, through the Kubernetes API. */
final class ArgoCd {
    static final Duration SYNC = Duration.ofMinutes(10);
    private static final Set<String> FINISHED = Set.of("Succeeded", "Failed", "Error");
    private final KindLab lab;
    private final String initiator;

    ArgoCd(KindLab lab, String initiator) { this.lab = lab; this.initiator = initiator; }

    JsonNode application(String name) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("-n", "argocd", "get", "application", name, "-o", "json"));
    }

    String operationStart(String name) throws IOException, InterruptedException {
        return application(name).path("status").path("operationState").path("startedAt").asText();
    }

    /** A full sync with the hook strategy, as the admission policy of the delivery chart permits. */
    void sync(String name, String revision) throws IOException, InterruptedException {
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        ObjectNode operation = patch.putObject("operation");
        operation.putObject("initiatedBy").put("username", initiator);
        operation.putObject("sync").put("revision", revision);
        lab.kubectl("-n", "argocd", "patch", "application", name, "--type", "merge", "-p", patch.toString());
    }

    /** Wait for a new, finished sync of the revision, and check its result. */
    JsonNode awaitOperation(String name, String revision, String previousStart, boolean success, Duration timeout)
            throws IOException, InterruptedException {
        AtomicReference<JsonNode> result = new AtomicReference<>();
        lab.await(name + " sync of " + revision, timeout, () -> {
            JsonNode application = application(name);
            JsonNode operation = application.path("status").path("operationState");
            boolean finished = FINISHED.contains(operation.path("phase").asText())
                    && !operation.path("startedAt").asText().equals(previousStart)
                    && revision.equals(operation.path("syncResult").path("revision").asText());
            if (finished) result.set(application);
            return finished;
        });
        String phase = result.get().path("status").path("operationState").path("phase").asText();
        require(success == phase.equals("Succeeded"), name + " sync of " + revision + " ended " + phase);
        return result.get();
    }

    boolean finished(String name) throws IOException, InterruptedException {
        return FINISHED.contains(application(name).path("status").path("operationState").path("phase").asText());
    }

    static boolean settled(JsonNode application) {
        JsonNode status = application.path("status");
        String operation = status.path("operationState").path("phase").asText("Succeeded");
        return status.path("sync").path("status").asText().equals("Synced") && status.path("health").path("status").asText().equals("Healthy")
                && operation.equals("Succeeded");
    }
}
