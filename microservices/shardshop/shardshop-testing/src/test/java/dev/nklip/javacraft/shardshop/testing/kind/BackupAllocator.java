package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Independent monotonic allocator evidence; never writes a Kubernetes allocator object. */
final class BackupAllocator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BackupAllocator() { }

    static ObjectNode observe(KindLab lab, Path destination) throws IOException, InterruptedException {
        JsonNode registry = KindLab.parse(lab.kubectl("get", "configmap", "shardshop-snowflake-generators", "-o", "json"));
        JsonNode identity = KindLab.parse(lab.kubectl("get", "configmap", "shardshop-generator-identity", "-o", "json"));
        String uid = registry.path("metadata").path("uid").asText();
        require(!uid.isBlank() && uid.equals(identity.path("data").path("registryUid").asText())
                && identity.path("immutable").asBoolean(), "Allocator identity is not pinned");
        int high = KindDeployment.highWater(registry.path("data").path("highWaterMark").asText());
        ObjectNode observed = JSON.createObjectNode().put("registryUid", uid).put("highWaterMark", high)
                .put("observedAt", Instant.now().toString());
        save(destination, observed);
        return observed;
    }

    static void save(Path destination, ObjectNode observation) throws IOException {
        require(destination.isAbsolute() && destination.getParent() != null, "Allocator history requires an absolute path");
        OwnedRestoreTarget.privateDirectory(destination.getParent());
        ObjectNode document;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            document = read(destination);
            validate(document, observation);
        } else {
            document = JSON.createObjectNode().put("formatVersion", 1);
            document.putArray("history");
        }
        validate(observation, observation);
        if (document.path("history").isEmpty()
                || document.path("highWaterMark").asInt(-1) != observation.path("highWaterMark").asInt())
            document.withArray("history").add(observation.deepCopy());
        document.put("registryUid", observation.path("registryUid").asText());
        document.put("highWaterMark", observation.path("highWaterMark").asInt());
        document.put("observedAt", observation.path("observedAt").asText());
        Path temporary = Files.createTempFile(destination.getParent(), ".allocator-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document));
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    static ObjectNode read(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))
                        && Files.size(path) <= 1024 * 1024, "Allocator history must be a bounded private regular file");
        JsonNode result = JSON.readTree(Files.readAllBytes(path));
        require(result instanceof ObjectNode && result.path("formatVersion").asInt() == 1
                && result.path("history").isArray(), "Allocator history format is invalid");
        validate(result, result);
        require(!result.path("history").isEmpty(), "Allocator history is empty");
        JsonNode previous = null;
        for (JsonNode prior : result.path("history")) {
            validate(prior, result);
            if (previous != null) validate(previous, prior);
            try { Instant.parse(prior.path("observedAt").asText()); }
            catch (RuntimeException invalid) { throw new IOException("Allocator observation time is invalid"); }
            previous = prior;
        }
        require(previous != null && previous.path("registryUid").equals(result.path("registryUid"))
                && previous.path("highWaterMark").equals(result.path("highWaterMark")), "Allocator latest mark differs from its history");
        return (ObjectNode) result;
    }

    static void validate(JsonNode backup, JsonNode latest) throws IOException {
        require(backup.path("registryUid").isTextual() && !backup.path("registryUid").asText().isBlank()
                        && backup.path("registryUid").equals(latest.path("registryUid")), "Allocator lineage differs");
        require(backup.path("highWaterMark").isInt() && latest.path("highWaterMark").isInt(), "Missing allocator high-water evidence");
        int previous = backup.path("highWaterMark").asInt(), current = latest.path("highWaterMark").asInt();
        require(previous >= 0 && current >= previous && current <= 1023, "Allocator high-water mark would be rewound");
    }
}
