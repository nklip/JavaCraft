package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

final class KindMigrationTest {
    private static final String VERSION = "a".repeat(64);
    private static final String REGIONS = "b".repeat(64);
    private static ObjectNode json(String text) { return (ObjectNode) KindLab.parse(text); }
    private static ObjectNode inventory() {
        return json("{}").put("version", VERSION).put("regionVersion", REGIONS).put("shards", "shard-a\nshard-b");
    }
    private static ObjectNode reservation() {
        ObjectNode result = json("{}");
        ObjectNode versions = inventory().deepCopy();
        versions.remove("shards");
        result.set("data", versions);
        return result;
    }
    private static ObjectNode publication(String name) {
        ObjectNode resource = json("{}");
        resource.putObject("metadata").put("name", name).put("generation", 2);
        resource.putObject("status").put("observedGeneration", 2).put("applied", true);
        return resource;
    }
    private static ObjectNode terminalJob(String condition) {
        ObjectNode result = json("{}");
        result.putObject("metadata").put("name", "retained-migration");
        result.putObject("status").putArray("conditions").addObject().put("type", condition).put("status", "True");
        return result;
    }

    @Test
    void reservationRequiresBothHashesButPreservesLegacyTwoFieldState() throws IOException {
        KindMigration.validateReservation(inventory(), reservation());
        assertThrows(IOException.class, () -> KindMigration.validateReservation(inventory(), json("{}")));
        for (String key : List.of("version", "regionVersion")) {
            ObjectNode changed = reservation();
            ((ObjectNode) changed.path("data")).put(key, "c".repeat(64));
            assertThrows(IOException.class, () -> KindMigration.validateReservation(inventory(), changed), key);
            ((ObjectNode) changed.path("data")).remove(key);
            assertThrows(IOException.class, () -> KindMigration.validateReservation(inventory(), changed), key);
            ObjectNode invalid = inventory().put(key, "");
            assertThrows(IOException.class, () -> KindMigration.validateReservation(invalid, reservation()), key);
        }
        ObjectNode deleting = reservation();
        deleting.putObject("metadata").put("deletionTimestamp", "2026-10-13T00:00:00Z");
        assertThrows(IOException.class, () -> KindMigration.validateReservation(inventory(), deleting));
    }

    @Test
    void reservationAlsoRejectsConflictingPublishedRoutingWithoutExecutingSql() throws Exception {
        KindLab lab = mock(KindLab.class);
        when(lab.shards()).thenReturn(List.of("shard-a", "shard-b"));
        when(lab.inventory()).thenReturn(inventory());
        ObjectNode conflict = reservation();
        ((ObjectNode) conflict.path("data")).put("regionVersion", "c".repeat(64));
        when(lab.kubectl(any(String[].class))).thenAnswer(call -> {
            List<String> args = List.of((String[]) call.getRawArguments()[0]);
            assertEquals("get", args.getFirst());
            return (args.contains("shardshop-routing") ? conflict : reservation()).toString();
        });
        assertThrows(IOException.class, () -> new KindMigration(lab).reservation());
        verify(lab, never()).command(any(String[].class));
    }

    @Test
    void publicationsRequireExactInventoryAndCurrentAppliedGeneration() throws IOException {
        ObjectNode list = json("{}");
        list.putArray("items").add(publication("shard-a-order-outbox")).add(publication("shard-b-order-outbox"));
        KindMigration.validatePublications(List.of("shard-a", "shard-b"), list);
        assertThrows(IOException.class, () -> KindMigration.validatePublications(List.of("shard-a"), list));
        assertThrows(IOException.class, () -> KindMigration.validatePublications(List.of("shard-a", "shard-b", "shard-c"), list));
        assertThrows(IOException.class, () -> KindMigration.validatePublications(List.of("shard-a"), json("{\"items\":[]}")));
        for (String status : List.of("{\"observedGeneration\":1,\"applied\":true}",
                "{\"observedGeneration\":2,\"applied\":false}", "{\"observedGeneration\":2,\"applied\":\"true\"}",
                "{\"applied\":true}")) {
            ObjectNode invalid = publication("shard-a-order-outbox");
            invalid.set("status", KindLab.parse(status));
            assertThrows(IOException.class, () -> KindMigration.validatePublications(List.of("shard-a"), invalid));
        }
        ObjectNode duplicate = json("{}");
        duplicate.putArray("items").add(publication("shard-a-order-outbox")).add(publication("shard-a-order-outbox"));
        assertThrows(IOException.class, () -> KindMigration.validatePublications(List.of("shard-a"), duplicate));
    }

    @Test
    void publicationWaitUsesBoundedCurrentGenerationObservation() throws Exception {
        KindLab lab = mock(KindLab.class);
        when(lab.shards()).thenReturn(List.of("shard-a"));
        ObjectNode stale = publication("shard-a-order-outbox");
        ((ObjectNode) stale.path("status")).put("observedGeneration", 1);
        when(lab.kubectl(any(String[].class))).thenReturn(stale.toString(), publication("shard-a-order-outbox").toString());
        doAnswer(call -> {
            KindLab.Probe probe = call.getArgument(2);
            assertThrows(IOException.class, probe::check, "Old applied=true must remain pending");
            assertTrue(probe.check(), "The current applied generation must release the wait");
            return null;
        }).when(lab).await(eq("current-generation shard outbox publications"), eq(Duration.ofSeconds(180)), any(KindLab.Probe.class));

        new KindMigration(lab).publications();

        verify(lab, times(2)).kubectl("get", "publications.postgresql.cnpg.io", "shard-a-order-outbox", "-o", "json");
        verify(lab, never()).command(any(String[].class));
    }

    @Test
    void pendingRunningTerminatingAndIntermediateConditionsBlockAnotherMigrationRelease() {
        for (String status : List.of("{}", "{\"active\":0}", "{\"active\":1}",
                "{\"conditions\":[{\"type\":\"SuccessCriteriaMet\",\"status\":\"True\"}]}",
                "{\"conditions\":[{\"type\":\"FailureTarget\",\"status\":\"True\"}]}",
                "{\"conditions\":[{\"type\":\"Complete\",\"status\":\"False\"}]}")) {
            ObjectNode job = terminalJob("Complete");
            job.set("status", KindLab.parse(status));
            assertThrows(IOException.class, () -> KindMigration.validateIdleJobs(job), status);
        }
        ObjectNode deleting = terminalJob("Complete");
        ((ObjectNode) deleting.path("metadata")).put("deletionTimestamp", "2026-10-13T00:00:00Z");
        assertThrows(IOException.class, () -> KindMigration.validateIdleJobs(deleting));
        for (String active : List.of("1", "-1", "\"0\"", "9223372036854775809")) {
            ObjectNode job = terminalJob("Failed");
            ((ObjectNode) job.path("status")).set("active", KindLab.parse(active));
            assertThrows(IOException.class, () -> KindMigration.validateIdleJobs(job), active);
        }
    }

    @Test
    void completedAndFailedRetainedJobsPermitFreshFlywayExecution() throws IOException {
        KindMigration.validateIdleJobs(json("{\"items\":[]}"));
        for (String condition : List.of("Complete", "Failed")) {
            ObjectNode job = terminalJob(condition);
            KindMigration.validateIdleJobs(job);
            ((ObjectNode) job.path("status")).put("active", 0);
            KindMigration.validateIdleJobs(job);
        }
        ObjectNode jobs = json("{}");
        jobs.putArray("items").add(terminalJob("Complete")).add(terminalJob("Failed"));
        KindMigration.validateIdleJobs(jobs);
        jobs.withArray("items").add(json("{\"metadata\":{\"name\":\"queued-migration\"}}"));
        assertThrows(IOException.class, () -> KindMigration.validateIdleJobs(jobs));
    }

    @Test
    void idleObservationIncludesLegacyMigrationJobsWithoutRevisionLabels() throws Exception {
        KindLab lab = mock(KindLab.class);
        when(lab.kubectl(any(String[].class))).thenReturn("{\"items\":[{\"metadata\":{\"name\":\"shard-a-catalog-migration\"}}]}");
        assertThrows(IOException.class, () -> new KindMigration(lab).idle());
        verify(lab).kubectl("get", "jobs", "-l", "app.kubernetes.io/name=schema-migration", "-o", "json");
        verifyNoMoreInteractions(lab);
    }
}
