package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class BackupArtifactsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void privateBundleSealsLastAndReopensWithVerifiedMetadata() throws Exception {
        BackupArtifacts bundle = bundle();
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(bundle.directory())));
        bundle.writeJson("roles.json", JSON.createArrayNode().add("owner"));
        assertFalse(Files.exists(bundle.directory().resolve("manifest.json")));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        bundle.seal(JSON.createObjectNode().put("source", "owned-lab"));
        BackupArtifacts reopened = BackupArtifacts.open(bundle.directory());
        assertEquals("owned-lab", reopened.manifest().path("source").textValue());
        assertEquals(1, reopened.manifest().path("files").size());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(reopened.file("roles.json"))));
        assertThrows(IOException.class, () -> reopened.newFile("extra"));
        assertThrows(IOException.class, () -> bundle.seal(JSON.createObjectNode()));
        assertThrows(IOException.class, () -> BackupArtifacts.create(bundle.directory()));
    }

    @Test
    void refusesTraversalSymlinksAndFileOverwrite() throws Exception {
        BackupArtifacts bundle = bundle();
        bundle.writeJson("roles.json", JSON.createArrayNode());
        assertThrows(IOException.class, () -> bundle.newFile("roles.json"));
        for (String invalid : new String[]{"../outside", "/absolute", "a/b", "..", ".", ""}) {
            assertThrows(IOException.class, () -> bundle.newFile(invalid));
            assertThrows(IOException.class, () -> bundle.file(invalid));
        }
        Files.createSymbolicLink(bundle.directory().resolve("link"), temporary);
        assertThrows(IOException.class, () -> bundle.file("link"));
        assertThrows(IOException.class, () -> bundle.seal(JSON.createObjectNode()));
        assertFalse(Files.exists(bundle.directory().resolve("manifest.json")));
    }

    @Test
    void changedTruncatedMissingAndUnlistedFilesFailIntegrity() throws Exception {
        BackupArtifacts bundle = sealed();
        Path data = bundle.file("data.dump");
        Files.writeString(data, "other"); // Same length; digest, rather than size, must detect this.
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.writeString(data, "");
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.delete(data);
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.createFile(data, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(data, "hello");
        Files.createFile(bundle.directory().resolve("unlisted"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
    }

    @Test
    void malformedDuplicateTraversalAndOversizedManifestsFailClosed() throws Exception {
        BackupArtifacts bundle = sealed();
        Path marker = bundle.file("manifest.json");
        String valid = Files.readString(marker);
        ObjectNode changed = (ObjectNode) JSON.readTree(valid);
        changed.withArray("files").add(changed.path("files").get(0).deepCopy());
        Files.writeString(marker, JSON.writeValueAsString(changed));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        changed = (ObjectNode) JSON.readTree(valid);
        ((ObjectNode) changed.path("files").get(0)).put("name", "../outside");
        Files.writeString(marker, JSON.writeValueAsString(changed));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.writeString(marker, "{\"formatVersion\":1,\"formatVersion\":1,\"files\":[]}");
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.writeString(marker, "{");
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.writeString(marker, " ".repeat(1024 * 1024 + 1));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
    }

    @Test
    void rejectsPublicDirectoriesAndFilesAndSymlinkRoots() throws Exception {
        BackupArtifacts bundle = sealed();
        Files.setPosixFilePermissions(bundle.file("data.dump"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Files.setPosixFilePermissions(bundle.directory(), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class, () -> BackupArtifacts.open(bundle.directory()));
        Path link = temporary.resolve("linked");
        Files.createSymbolicLink(link, bundle.directory());
        assertThrows(IOException.class, () -> BackupArtifacts.open(link));
        assertThrows(IOException.class, () -> BackupArtifacts.create(Path.of("relative")));
    }

    private BackupArtifacts sealed() throws IOException {
        BackupArtifacts bundle = bundle();
        Files.writeString(bundle.newFile("data.dump"), "hello");
        bundle.seal(JSON.createObjectNode());
        return bundle;
    }

    private BackupArtifacts bundle() throws IOException {
        return BackupArtifacts.create(temporary.toRealPath().resolve("bundle"));
    }
}
