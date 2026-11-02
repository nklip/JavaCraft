package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** EKS-only release acceptance and drill of the applications that Argo CD delivers with the gate hooks. */
public final class EksApplications {
    static final String APPLICATIONS = "shardshop-applications";
    /** A sync runs the PreSync gate, the rollout, the seeder and the PostSync gate. */
    static final Duration APPLICATION_SYNC = Duration.ofMinutes(25);
    /** In the Argo CD mode, the reader names the Application whose sync result contains its seeder attempt. */
    static final String SEEDER_APPLICATION = "shardshop.javacraft/seeder-application";
    private static final String DRILL = "eks-application-drill";
    /** Each Deployment and its image in images.json of the invocation snapshot. Both order processes use one image. */
    private static final Map<String, String> IMAGES = imageKeys();
    private final KindLab lab;
    private final ArgoCd argo;

    public EksApplications() { this(new KindLab()); }
    EksApplications(KindLab lab) { this.lab = lab; this.argo = new ArgoCd(lab, DRILL); }

    /**
     * The release of the current sync: Argo CD applied each resource of the chart version after a successful
     * PreSync gate, all the pods of each Deployment run the pinned image of the run, and the seeder attempt of
     * this sync succeeded. The PostSync gate runs this while the sync runs, and the operator can run it after.
     */
    public void released() throws IOException, InterruptedException {
        JsonNode images = lab.json("images.json").path("images");
        lab.await("the release of the current sync with the pinned images", ArgoCd.SYNC, () -> {
            JsonNode application = argo.application(APPLICATIONS);
            JsonNode operation = application.path("status").path("operationState");
            require(Set.of("Running", "Succeeded").contains(operation.path("phase").asText()),
                    "The current sync of " + APPLICATIONS + " did not succeed");
            equal(operation.path("syncResult").path("revision").asText(),
                    application.path("spec").path("source").path("targetRevision").asText(), "Revision of the current sync");
            boolean gated = false;
            for (JsonNode resource : operation.path("syncResult").path("resources")) {
                String hook = resource.path("hookType").asText();
                if (hook.isEmpty()) {
                    require(resource.path("status").asText().equals("Synced"), "Argo CD did not apply " + describe(resource));
                } else if (hook.equals("PreSync")) {
                    require(resource.path("hookPhase").asText().equals("Succeeded"), "The PreSync gate did not succeed");
                    gated = true;
                }
            }
            require(gated, "The current sync ran no PreSync gate");
            for (Map.Entry<String, String> deployment : IMAGES.entrySet()) {
                rolledOut(deployment.getKey(), images.path(deployment.getValue()).asText());
            }
            JsonNode seeder = KindLab.parse(lab.kubectl("get", "job", seederJob(lab, APPLICATIONS), "-o", "json"));
            equal(seeder.path("spec").path("template").path("spec").path("containers").path(0).path("image").asText(),
                    images.path("product-seeder").asText(), "Image of the seeder attempt");
            require(seeder.path("status").path("succeeded").asInt() > 0, "The seeder attempt of the current sync did not succeed");
            return true;
        });
    }

    /**
     * A resync without changes runs both gates and a new seeder attempt, and keeps the earlier attempt. A held
     * drill lock fails the PreSync gate before Argo CD applies a resource. The admission policy denies a
     * selective sync and the apply strategy, because Argo CD runs no hook in them.
     */
    public void drill() throws IOException, InterruptedException {
        lab.await("a synced and healthy root Application", ArgoCd.SYNC, () -> ArgoCd.settled(argo.application(EksDelivery.ROOT)));
        String revision = argo.application(APPLICATIONS).path("spec").path("source").path("targetRevision").asText();

        String earlier = seederJob(lab, APPLICATIONS);
        Map<String, Long> generations = generations();
        String started = argo.operationStart(APPLICATIONS);
        argo.sync(APPLICATIONS, revision);
        JsonNode resynced = argo.awaitOperation(APPLICATIONS, revision, started, true, APPLICATION_SYNC);
        String later = seederJob(lab, APPLICATIONS);
        require(!later.equals(earlier), "A resync used the completed seeder attempt " + earlier + " again");
        require(KindLab.parse(lab.kubectl("get", "job", earlier, "-o", "json")).path("status").path("succeeded").asInt() > 0,
                "The resync deleted the earlier seeder attempt " + earlier);
        equal(hooks(resynced, "PreSync").size(), 1, "PreSync hooks of a resync");
        equal(hooks(resynced, "PostSync").size(), 2, "PostSync hooks of a resync: the seeder and the gate");
        equal(generations(), generations, "Deployment generations after a resync without changes");

        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                String start = argo.operationStart(APPLICATIONS);
                argo.sync(APPLICATIONS, revision);
                JsonNode stopped = argo.awaitOperation(APPLICATIONS, revision, start, false, APPLICATION_SYNC);
                List<JsonNode> results = KindLab.items(stopped.path("status").path("operationState").path("syncResult").path("resources"));
                require(!results.isEmpty(), "The stopped sync has no result of its PreSync gate");
                for (JsonNode resource : results) {
                    require(resource.path("hookType").asText().equals("PreSync"), "A sync with a failed PreSync gate ran " + describe(resource));
                    require(resource.path("hookPhase").asText().equals("Failed"), "The PreSync gate did not fail under the drill lock");
                    JsonNode gate = KindLab.parse(lab.kubectl("get", "job", resource.path("name").asText(), "-o", "json"));
                    require(gate.path("status").path("failed").asInt() > 0, "Argo CD deleted the failed gate " + resource.path("name").asText());
                }
                equal(generations(), generations, "Deployment generations after a stopped sync");
            });
        }
        // Argo CD does not repeat a failed sync for unchanged manifests, thus the recovery is an explicit sync.
        started = argo.operationStart(APPLICATIONS);
        argo.sync(APPLICATIONS, revision);
        argo.awaitOperation(APPLICATIONS, revision, started, true, APPLICATION_SYNC);

        for (ObjectNode bypass : List.of(bypass(revision, true), bypass(revision, false))) {
            KindLab.CommandResult result = lab.kubectlAttempt("-n", "argocd", "patch", "application", APPLICATIONS,
                    "--type", "merge", "-p", bypass.toString());
            String output = result.stdout() + result.stderr();
            require(result.exitCode() != 0 && output.contains("shardshop-full-sync") && output.contains("denied request"),
                    "The admission policy did not deny " + bypass);
            require(argo.application(APPLICATIONS).path("operation").isMissingNode(), "A denied sync started");
        }
        lab.await("a synced and healthy root Application", ArgoCd.SYNC, () -> ArgoCd.settled(argo.application(EksDelivery.ROOT)));
    }

    /** The PostSync seeder attempt in the sync result of the Application: Argo CD gives each sync its own name. */
    static String seederJob(KindLab lab, String application) throws IOException, InterruptedException {
        String prefix = (application.length() > 26 ? application.substring(0, 26) : application).replaceAll("-+$", "") + "-seeder-";
        List<String> names = new ArrayList<>();
        for (JsonNode resource : new ArgoCd(lab, DRILL).application(application).path("status").path("operationState")
                .path("syncResult").path("resources")) {
            if (resource.path("kind").asText().equals("Job") && resource.path("hookType").asText().equals("PostSync")
                    && resource.path("name").asText().startsWith(prefix)) names.add(resource.path("name").asText());
        }
        require(names.size() == 1, "The current sync of " + application + " has no single seeder attempt: " + names);
        return names.getFirst();
    }

    private void rolledOut(String name, String image) throws IOException, InterruptedException {
        require(image.matches("[^\\s@:]+(:[^\\s@]+)?@sha256:[0-9a-f]{64}"), "images.json has no digest-pinned image for " + name);
        JsonNode deployment = KindLab.parse(lab.kubectl("get", "deployment", name, "-o", "json"));
        equal(deployment.path("spec").path("template").path("spec").path("containers").path(0).path("image").asText(), image,
                "Image of " + name);
        int replicas = deployment.path("spec").path("replicas").asInt();
        JsonNode status = deployment.path("status");
        require(replicas > 0 && status.path("observedGeneration").asLong(-1) == deployment.path("metadata").path("generation").asLong()
                        && status.path("replicas").asInt() == replicas && status.path("updatedReplicas").asInt() == replicas
                        && status.path("readyReplicas").asInt() == replicas && status.path("availableReplicas").asInt() == replicas,
                name + " has not rolled out all its replicas");
        List<JsonNode> pods = KindLab.items(KindLab.parse(lab.kubectl("get", "pods", "-l", "app.kubernetes.io/name=" + name, "-o", "json")))
                .stream().filter(pod -> !pod.path("metadata").hasNonNull("deletionTimestamp")).toList();
        equal(pods.size(), replicas, "Pods of " + name);
        // The kubelet reports the pulled reference without the tag.
        String pulled = image.replaceFirst(":[^/@]+@", "@");
        for (JsonNode pod : pods) {
            JsonNode container = pod.path("status").path("containerStatuses").path(0);
            require(container.path("ready").asBoolean() && container.path("imageID").asText().equals(pulled),
                    pod.path("metadata").path("name").asText() + " does not run the pinned image of " + name);
        }
    }

    private Map<String, Long> generations() throws IOException, InterruptedException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String name : IMAGES.keySet()) {
            result.put(name, KindLab.parse(lab.kubectl("get", "deployment", name, "-o", "json")).path("metadata").path("generation").asLong());
        }
        return result;
    }

    private static List<String> hooks(JsonNode application, String type) {
        List<String> names = new ArrayList<>();
        for (JsonNode resource : application.path("status").path("operationState").path("syncResult").path("resources")) {
            if (resource.path("hookType").asText().equals(type)) {
                names.add(resource.path("name").asText());
            }
        }
        return names;
    }

    /** A sync that Argo CD runs without hooks: a selective sync, or a sync with the apply strategy. */
    private static ObjectNode bypass(String revision, boolean selective) {
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        ObjectNode operation = patch.putObject("operation");
        operation.putObject("initiatedBy").put("username", DRILL);
        ObjectNode sync = operation.putObject("sync").put("revision", revision);
        if (selective) {
            sync.putArray("resources").addObject().put("group", "apps").put("kind", "Deployment")
                    .put("namespace", "shardshop").put("name", "shardshop-product");
        } else {
            sync.putObject("syncStrategy").putObject("apply").put("force", false);
        }
        return patch;
    }

    private static String describe(JsonNode resource) {
        return resource.path("kind").asText() + " " + resource.path("name").asText();
    }

    private static Map<String, String> imageKeys() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put("shardshop-product", "product");
        images.put("shardshop-product-reader", "product-reader");
        images.put("shardshop-product-seeder-control", "product-seeder");
        images.put("shardshop-order", "order");
        images.put("shardshop-order-saga", "order");
        images.put("shardshop-ledger", "ledger");
        images.put("shardshop-order-producer", "order-producer");
        return Collections.unmodifiableMap(images);
    }
}
