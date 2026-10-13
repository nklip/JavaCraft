package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class KindWorkloadDrillTest {
    private static final String IMAGE = "docker.io/library/shardshop-product-seeder@sha256:" + "a".repeat(64);

    @Test
    void replacementMustExceedThePreDeletionRegistryRatherThanOnlyOldPodIds() throws IOException {
        Map<String, Integer> before = Map.of("old", 3, "retained", 4);
        KindWorkloadDrill.replacement(before, Map.of("new", 11, "retained", 4), "old", 10, 11);
        IOException failure = assertThrows(IOException.class, () ->
                KindWorkloadDrill.replacement(before, Map.of("new", 9, "retained", 4), "old", 10, 11));
        assertTrue(failure.getMessage().contains("before deletion"));
        assertThrows(IOException.class, () -> KindWorkloadDrill.replacement(before, Map.of("new", 10, "retained", 4), "old", 10, 11));
    }

    @Test
    void replacementRequiresExactlyOneNewPodAndUnchangedRetainedJvm() {
        Map<String, Integer> before = Map.of("old", 3, "retained", 4);
        for (Map<String, Integer> after : java.util.List.of(Map.of("old", 3, "retained", 4),
                Map.of("new", 11, "other-new", 12), Map.of("new", 11, "retained", 11),
                Map.of("new", 11, "retained", 5), Map.of("new", 12, "retained", 4))) {
            assertThrows(IOException.class, () -> KindWorkloadDrill.replacement(before, after, "old", 10, 11));
        }
        assertThrows(IOException.class, () -> KindWorkloadDrill.replacement(before, Map.of("new", 11, "retained", 4), "old", 10, 10));
        assertThrows(IOException.class, () -> KindWorkloadDrill.replacement(before, Map.of("new", 1024, "retained", 4), "old", 1023, 1024));
    }

    @Test
    void independentSqlComparisonDetectsChangedIdsEvenWhenApplicationDigestsAgree() throws IOException {
        Map<String, String> before = Map.of("shard-a", "{\"sellers\":[{\"seller_id\":\"9007199254740993\"}],\"products\":[]}");
        KindWorkloadDrill.unchangedIds(before, before);
        assertThrows(IOException.class, () -> KindWorkloadDrill.unchangedIds(before,
                Map.of("shard-a", "{\"sellers\":[{\"seller_id\":\"9007199254740994\"}],\"products\":[]}")));
        assertThrows(IOException.class, () -> KindWorkloadDrill.unchangedIds(before, Map.of()));
        assertThrows(IOException.class, () -> KindWorkloadDrill.unchangedIds(Map.of(), Map.of()));
    }

    private static ObjectNode job() {
        return (ObjectNode) KindLab.parse("""
                {"apiVersion":"batch/v1","kind":"Job","metadata":{"name":"shardshop-product-seeder-drill-test",
                "namespace":"shardshop","labels":{"shardshop.javacraft/run":"drill-test"}},
                "spec":{"activeDeadlineSeconds":300,"template":{"spec":{"restartPolicy":"Never","automountServiceAccountToken":false,
                "containers":[{"name":"seeder","image":"IMAGE","env":[{"name":"SHARDSHOP_SEEDER_PRODUCT_URL",
                "value":"http://shardshop-product.shardshop.svc:8080"}]}],"volumes":[{"name":"tmp","emptyDir":{}}]}}}}
                """.replace("IMAGE", IMAGE));
    }

    private static void validate(ObjectNode job) throws IOException {
        KindWorkloadDrill.validateJob(job, "shardshop-product-seeder-drill-test", "drill-test", IMAGE);
    }

    @Test
    void temporaryJobValidationBoundsMutationToOneOwnedSeeder() throws IOException {
        validate(job());
        for (String changed : new String[]{"kind", "name", "namespace", "run", "image", "deadline", "token"}) {
            ObjectNode invalid = job();
            ObjectNode metadata = (ObjectNode) invalid.path("metadata");
            ObjectNode spec = (ObjectNode) invalid.path("spec").path("template").path("spec");
            switch (changed) {
                case "kind" -> invalid.put("kind", "Deployment");
                case "name" -> metadata.put("name", "existing-job");
                case "namespace" -> metadata.put("namespace", "default");
                case "run" -> ((ObjectNode) metadata.path("labels")).put("shardshop.javacraft/run", "another-run");
                case "image" -> ((ObjectNode) spec.path("containers").get(0)).put("image", "shardshop-product-seeder:local");
                case "deadline" -> ((ObjectNode) invalid.path("spec")).put("activeDeadlineSeconds", 0);
                case "token" -> spec.put("automountServiceAccountToken", true);
            }
            assertThrows(IOException.class, () -> validate(invalid), changed);
        }
    }
}
