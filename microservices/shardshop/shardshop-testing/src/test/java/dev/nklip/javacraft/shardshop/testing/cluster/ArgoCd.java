package dev.nklip.javacraft.shardshop.testing.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static dev.nklip.javacraft.shardshop.testing.cluster.Topology.require;

/** Reads and syncs the Argo CD Applications of the AWS deployment, through the Kubernetes API. */
public final class ArgoCd {
    public static final Duration SYNC = Duration.ofMinutes(10);
    public static final String SEEDER_APPLICATION = "shardshop.javacraft/seeder-application";
    private static final Set<String> FINISHED = Set.of("Succeeded", "Failed", "Error");
    private final ClusterLab lab;
    private final String initiator;

    public ArgoCd(ClusterLab lab, String initiator) { this.lab = lab; this.initiator = initiator; }

    public JsonNode application(String name) throws IOException, InterruptedException {
        return application(lab, name);
    }

    private static JsonNode application(ClusterLab lab, String name) throws IOException, InterruptedException {
        return ClusterLab.parse(lab.kubectl("-n", "argocd", "get", "application", name, "-o", "json"));
    }

    /**
     * The PostSync seeder attempt in the sync result of the Application: Argo CD gives each sync its own name. The
     * reader Deployment names this Application in {@link #SEEDER_APPLICATION}; only an Argo CD delivery sets it.
     */
    public static String seederJob(ClusterLab lab, String application) throws IOException, InterruptedException {
        String prefix = (application.length() > 26 ? application.substring(0, 26) : application).replaceAll("-+$", "") + "-seeder-";
        List<String> names = new ArrayList<>();
        for (JsonNode resource : application(lab, application).path("status").path("operationState")
                .path("syncResult").path("resources")) {
            if (resource.path("kind").asText().equals("Job") && resource.path("hookType").asText().equals("PostSync")
                    && resource.path("name").asText().startsWith(prefix)) names.add(resource.path("name").asText());
        }
        require(names.size() == 1, "The current sync of " + application + " has no single seeder attempt: " + names);
        return names.getFirst();
    }

    public String operationStart(String name) throws IOException, InterruptedException {
        return application(name).path("status").path("operationState").path("startedAt").asText();
    }

    /** A full sync with the hook strategy, as the admission policy of the delivery chart permits. */
    public void sync(String name, String revision) throws IOException, InterruptedException {
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        ObjectNode operation = patch.putObject("operation");
        operation.putObject("initiatedBy").put("username", initiator);
        operation.putObject("sync").put("revision", revision);
        lab.kubectl("-n", "argocd", "patch", "application", name, "--type", "merge", "-p", patch.toString());
    }

    /** Wait for a new, finished sync of the revision, and check its result. */
    public JsonNode awaitOperation(String name, String revision, String previousStart, boolean success, Duration timeout)
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

    public boolean finished(String name) throws IOException, InterruptedException {
        return FINISHED.contains(application(name).path("status").path("operationState").path("phase").asText());
    }

    public static boolean settled(JsonNode application) {
        JsonNode status = application.path("status");
        String operation = status.path("operationState").path("phase").asText("Succeeded");
        return status.path("sync").path("status").asText().equals("Synced") && status.path("health").path("status").asText().equals("Healthy")
                && operation.equals("Succeeded");
    }
}
