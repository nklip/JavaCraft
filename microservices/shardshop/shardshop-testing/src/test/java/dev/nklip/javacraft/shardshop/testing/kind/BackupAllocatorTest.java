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

class BackupAllocatorTest {
    @TempDir Path directory;
    private ObjectNode mark(String uid, int value) {
        return new ObjectMapper().createObjectNode().put("registryUid", uid).put("highWaterMark", value).put("observedAt", "2026-10-26T00:00:00Z");
    }
    @Test void separateHistoryAdvancesWithoutRewindingOrReplacingLineage() throws Exception {
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path file = directory.resolve("allocator.json");
        BackupAllocator.save(file, mark("original", 70));
        BackupAllocator.save(file, mark("original", 75));
        BackupAllocator.validate(mark("original", 70), BackupAllocator.read(file));
        assertEquals(2, BackupAllocator.read(file).path("history").size());
        assertThrows(IOException.class, () -> BackupAllocator.save(file, mark("original", 69)));
        assertThrows(IOException.class, () -> BackupAllocator.save(file, mark("replacement", 80)));
        assertEquals(75, BackupAllocator.read(file).path("highWaterMark").asInt());
    }
    @Test void restoreRejectsMissingStaleOrExhaustedIdentityEvidence() {
        assertThrows(IOException.class, () -> BackupAllocator.validate(mark("source", 75), mark("source", 74)));
        assertThrows(IOException.class, () -> BackupAllocator.validate(mark("source", 75), mark("other", 75)));
        assertThrows(IOException.class, () -> BackupAllocator.validate(mark("source", 75), mark("source", 1024)));
    }
}
