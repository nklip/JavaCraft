package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in disruption: repeat HTTP seeding and replace one product JVM under the shared drill lock. */
public final class KindWorkloadDrill {
    private final KindLab lab;
    private final KindDeployment deployment;

    public KindWorkloadDrill() { this(new KindLab()); }
    KindWorkloadDrill(KindLab lab) {
        this.lab = lab;
        this.deployment = new KindDeployment(lab);
    }

    public void repeatAndReplace() throws IOException, InterruptedException {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                KindDeployment.Workload workload = deployment.deployed();
                requireSnapshot();
                Map<String, String> before = deployment.catalogIds();
                Map<String, String> images = images(workload);
                for (int run = 0; run < 2; run++) {
                    String digest = seed(guard, images);
                    KindDeployment.require(digest.equals(workload.digest()), "Repeated HTTP seeding recovered different IDs");
                    unchangedIds(before, deployment.catalogIds());
                    deployment.catalogCounts();
                }
                replace(guard, workload);
                unchangedIds(before, deployment.catalogIds());
                deployment.catalogCounts();
                JsonNode readerDeployment = deployment.resource("deployment", "shardshop-product-reader");
                KindDeployment.require(readerDeployment.path("spec").path("replicas").asInt() == 1,
                        "Reader replica count changed during workload drill");
            });
        }
    }

    private void requireSnapshot() throws IOException {
        KindDeployment.require(Files.isDirectory(lab.snapshot().resolve("chart"))
                && Files.isRegularFile(lab.snapshot().resolve("environment.yaml"))
                && Files.isRegularFile(lab.snapshot().resolve("inventory.yaml")),
                "Workload drill needs chart/, environment.yaml and inventory.yaml from the invocation snapshot");
    }

    private Map<String, String> images(KindDeployment.Workload workload) throws IOException, InterruptedException {
        JsonNode product = deployment.resource("deployment", "shardshop-product");
        return Map.of("product", image(product.path("spec").path("template").path("spec"), "product"),
                "product-reader", image(workload.reader().path("spec"), "reader"),
                "product-seeder", image(workload.seeder().path("spec"), "seeder"));
    }

    static String image(JsonNode spec, String name) throws IOException {
        for (JsonNode container : spec.path("containers")) {
            if (container.path("name").asText().equals(name)) {
                String image = container.path("image").asText();
                KindDeployment.require(image.matches("[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}"),
                        "Workload drill requires deployed immutable image digests for " + name);
                return image;
            }
        }
        throw new IOException("Missing deployed " + name + " container image");
    }

    private String seed(KindDrillGuard guard, Map<String, String> images) throws IOException, InterruptedException {
        String run = "drill-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String jobName = "shardshop-product-seeder-" + run;
        Path rendered = lab.snapshot().resolve(run + "-seeding.yaml");
        guard.onClose("remove temporary workload render " + run, () -> {
            Files.deleteIfExists(rendered);
            return !Files.exists(rendered);
        });
        String manifest = lab.command("helm", "template", "shardshop", lab.snapshot().resolve("chart").toString(),
                "--namespace", "shardshop", "--values", lab.snapshot().resolve("environment.yaml").toString(),
                "--values", lab.snapshot().resolve("inventory.yaml").toString(), "--set-string", "render=seeding",
                "--set-string", "run=" + run, "--set-string", "images.product=" + images.get("product"),
                "--set-string", "images.product-seeder=" + images.get("product-seeder"),
                "--set-string", "images.product-reader=" + images.get("product-reader"),
                "--show-only", "templates/applications.yaml");
        Files.writeString(rendered, manifest);
        JsonNode job = KindLab.parse(lab.kubectl("create", "--dry-run=client", "-f", rendered.toString(), "-o", "json"));
        validateJob(job, jobName, run, images.get("product-seeder"));
        KindDeployment.require(lab.kubectl("get", "job", jobName, "--ignore-not-found", "-o", "name").isBlank(),
                "Temporary workload Job name already exists");
        guard.onClose("delete temporary seeder Job " + jobName, () -> cleanupJob(jobName, run));
        lab.kubectl("create", "-f", rendered.toString());
        lab.await("temporary HTTP seeder Job " + jobName + " completion", Duration.ofSeconds(330),
                () -> KindDeployment.completed(deployment.resource("job", jobName)));
        JsonNode successful = deployment.successfulSeeder(jobName);
        return KindDeployment.digest(deployment.logs(KindDeployment.name(successful)), "Seeded and verified catalog-v2");
    }

    static void validateJob(JsonNode job, String name, String run, String image) throws IOException {
        KindDeployment.require(job.path("kind").asText().equals("Job")
                && job.path("apiVersion").asText().equals("batch/v1")
                && job.path("metadata").path("name").asText().equals(name)
                && job.path("metadata").path("namespace").asText().equals("shardshop")
                && job.path("metadata").path("labels").path("shardshop.javacraft/run").asText().equals(run),
                "Helm must render exactly the owned temporary seeder Job");
        JsonNode spec = job.path("spec").path("template").path("spec");
        KindDeployment.httpOnly(spec);
        KindDeployment.require(spec.path("containers").size() == 1 && image(spec, "seeder").equals(image)
                && spec.path("restartPolicy").asText().equals("Never")
                && job.path("spec").path("activeDeadlineSeconds").asInt() > 0
                && job.path("spec").path("activeDeadlineSeconds").asInt() <= 300,
                "Temporary seeder Job must use its deployed image and a bounded deadline");
    }

    private boolean cleanupJob(String name, String run) throws IOException, InterruptedException {
        String output = lab.kubectl("get", "job", name, "--ignore-not-found", "-o", "json");
        if (!output.isBlank()) {
            JsonNode job = KindLab.parse(output);
            KindDeployment.require(job.path("metadata").path("labels").path("shardshop.javacraft/run").asText().equals(run),
                    "Refusing to delete a temporary Job whose ownership label changed");
            lab.kubectl("delete", "job", name, "--ignore-not-found", "--wait=false");
        }
        lab.await("temporary seeder Job and pods removed", Duration.ofSeconds(90), () ->
                lab.kubectl("get", "job", name, "--ignore-not-found", "-o", "name").isBlank()
                        && deployment.pods("batch.kubernetes.io/job-name=" + name).isEmpty());
        return true;
    }

    private void replace(KindDrillGuard guard, KindDeployment.Workload workload) throws IOException, InterruptedException {
        List<String> beforePods = deployment.readyProducts();
        KindDeployment.require(beforePods.equals(workload.products()), "Product pod set changed before the replacement drill");
        Map<String, Integer> before = deployment.generators(beforePods, deployment.highWater());
        String removed = beforePods.getFirst();
        guard.onClose("await product recovery after workload drill", () -> {
            lab.await("two ready product pods after workload drill", Duration.ofSeconds(150), () ->
                    deployment.readyProducts().size() == 2
                            && deployment.resource("deployment", "shardshop-product").path("spec").path("replicas").asInt() == 2);
            return true;
        });
        // Other allocation may happen between observation and deletion: the authoritative boundary is this read.
        int boundary = deployment.highWater();
        KindDeployment.require(boundary < 1023, "Generator allocator is exhausted; cannot replace a product JVM");
        lab.kubectl("delete", "pod", removed, "--wait=false");
        lab.await("one replacement product pod ready", Duration.ofSeconds(150), () -> {
            List<String> pods = deployment.readyProducts();
            return pods.size() == 2 && !pods.contains(removed);
        });
        List<String> afterPods = deployment.readyProducts();
        replacement(before, deployment.generators(afterPods, deployment.highWater()), removed, boundary, deployment.highWater());
        Instant fresh = Instant.now();
        deployment.traffic(afterPods);
        deployment.cleanInterval(workload.reader(), fresh);
    }

    static void unchangedIds(Map<String, String> before, Map<String, String> after) throws IOException {
        KindDeployment.require(!before.isEmpty() && before.equals(after),
                "Independent primary SQL snapshots show catalog key-to-ID rows changed during repeated seeding");
    }

    static void replacement(Map<String, Integer> before, Map<String, Integer> after, String removed,
                            int beforeDeletionHighWater, int afterHighWater) throws IOException {
        KindDeployment.require(before.size() == 2 && after.size() == 2 && before.containsKey(removed)
                && !after.containsKey(removed) && beforeDeletionHighWater >= 1 && beforeDeletionHighWater < 1023
                && afterHighWater > beforeDeletionHighWater && afterHighWater <= 1023,
                "Invalid product replacement or allocator high-water boundary");
        List<String> replacement = after.keySet().stream().filter(name -> !before.containsKey(name)).toList();
        KindDeployment.require(replacement.size() == 1 && after.get(replacement.getFirst()) > beforeDeletionHighWater
                && after.get(replacement.getFirst()) <= afterHighWater,
                "Replacement JVM must reserve a generator above the high-water captured before deletion");
        for (var entry : before.entrySet()) {
            KindDeployment.require(entry.getValue() >= 1 && entry.getValue() <= beforeDeletionHighWater,
                    "Original product generator exceeds the pre-deletion high-water");
            if (!entry.getKey().equals(removed)) KindDeployment.require(entry.getValue().equals(after.get(entry.getKey())),
                    "The retained product pod changed its generator ID");
        }
        KindDeployment.require(after.values().stream().distinct().count() == 2,
                "Replacement product pods share a generator ID");
    }
}
