package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only topology and publication gates around Helm's migration release. */
public final class KindMigration {
    private static final Duration PUBLICATION_TIMEOUT = Duration.ofSeconds(180);
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private final KindLab lab;

    public KindMigration() { this(new KindLab()); }
    KindMigration(KindLab lab) { this.lab = lab; }

    public void reservation() throws IOException, InterruptedException {
        KindTopology.validateShards(lab.shards());
        JsonNode inventory = lab.inventory();
        validateReservation(inventory, KindLab.parse(lab.kubectl("get", "configmap",
                "shardshop-migration-topology", "-o", "json")));
        JsonNode routing = KindLab.parse(lab.kubectl("get", "configmap", "shardshop-routing",
                "--ignore-not-found", "-o", "json"));
        if (!routing.isEmpty()) validateVersions(inventory, routing, "Published routing");
    }

    public void idle() throws IOException, InterruptedException {
        validateIdleJobs(KindLab.parse(lab.kubectl("get", "jobs", "-l",
                "app.kubernetes.io/name=schema-migration", "-o", "json")));
    }

    public void publications() throws IOException, InterruptedException {
        List<String> shards = lab.shards();
        KindTopology.validateShards(shards);
        List<String> arguments = new ArrayList<>(List.of("get", "publications.postgresql.cnpg.io"));
        shards.forEach(shard -> arguments.add(shard + "-order-outbox"));
        arguments.addAll(List.of("-o", "json"));
        lab.await("current-generation shard outbox publications", PUBLICATION_TIMEOUT, () -> {
            validatePublications(shards, KindLab.parse(lab.kubectl(arguments.toArray(String[]::new))));
            return true;
        });
    }

    static void validateIdleJobs(JsonNode jobs) throws IOException {
        for (JsonNode job : KindLab.items(jobs)) {
            boolean terminal = false;
            for (JsonNode condition : job.path("status").path("conditions")) {
                if ("True".equals(condition.path("status").asText())
                        && List.of("Complete", "Failed").contains(condition.path("type").asText())) terminal = true;
            }
            JsonNode active = job.path("status").path("active");
            boolean noActivePods = active.isMissingNode()
                    || active.isIntegralNumber() && active.canConvertToLong() && active.longValue() == 0;
            require(terminal && noActivePods && !job.path("metadata").hasNonNull("deletionTimestamp"),
                    "Migration Job " + job.path("metadata").path("name").asText("unknown")
                            + " is active, pending or terminating; wait for it to finish before another migration release.");
        }
    }

    static void validateReservation(JsonNode inventory, JsonNode reservation) throws IOException {
        require(!reservation.isEmpty(), "Migration topology reservation is missing; refusing to run SQL.");
        validateVersions(inventory, reservation, "Migration topology reservation");
    }

    private static void validateVersions(JsonNode inventory, JsonNode resource, String description) throws IOException {
        for (String key : List.of("version", "regionVersion")) {
            JsonNode expected = inventory.path(key);
            require(expected.isTextual() && HASH.matcher(expected.textValue()).matches()
                            && expected.equals(resource.path("data").path(key)),
                    description + " differs from the invocation inventory; refusing migration/routing publication. "
                            + "Restore the retained inventory; online resharding is not implemented.");
        }
        require(!resource.path("metadata").hasNonNull("deletionTimestamp"),
                description + " is being deleted; refusing migration/routing publication.");
    }

    static void validatePublications(List<String> shards, JsonNode publications) throws IOException {
        Set<String> expected = new HashSet<>();
        shards.forEach(shard -> expected.add(shard + "-order-outbox"));
        Set<String> observed = new HashSet<>();
        for (JsonNode publication : KindLab.items(publications)) {
            String name = publication.path("metadata").path("name").asText();
            JsonNode generation = publication.path("metadata").path("generation");
            require(expected.contains(name) && observed.add(name)
                            && !publication.path("metadata").hasNonNull("deletionTimestamp")
                            && generation.isIntegralNumber() && generation.canConvertToLong() && generation.asLong() > 0
                            && generation.equals(publication.path("status").path("observedGeneration"))
                            && publication.path("status").path("applied").isBoolean()
                            && publication.path("status").path("applied").booleanValue(),
                    "CNPG publication has not applied its current generation; routing must remain unpublished.");
        }
        require(!expected.isEmpty() && expected.equals(observed),
                "The inventory's CNPG publications are missing; routing must remain unpublished.");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
