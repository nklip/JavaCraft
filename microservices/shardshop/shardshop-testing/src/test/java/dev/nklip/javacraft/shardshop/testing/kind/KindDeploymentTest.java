package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

final class KindDeploymentTest {
    private static final String DIGEST = "a".repeat(64);

    private static JsonNode json(String text) { return KindLab.parse(text); }
    private static ObjectNode spec() {
        return (ObjectNode) json("""
                {"automountServiceAccountToken":false,"containers":[{"name":"reader","env":[
                {"name":"SHARDSHOP_READER_PRODUCT_URL","value":"http://shardshop-product.shardshop.svc:8080"}]}],
                "volumes":[{"name":"tmp","emptyDir":{"sizeLimit":"64Mi"}}]}
                """);
    }
    private static String reserved(int id) { return "Reserved generator ID " + id + " for this JVM start."; }
    private static String report(String time, String seconds, long count, String rate, String outcomes) {
        return time + " INFO Product reads, interval " + seconds + " s: " + count + " requests, " + rate
                + "/s, outcomes {" + outcomes + "}, latency ms {p50=2}, regions {US=6000}, pool {leased=3, pending=0, available=13, max=16}";
    }

    @Test
    void primaryReadGateRejectsReplicaConfigurationAndPodOverrides() throws IOException {
        JsonNode base = json("{\"containers\":[{\"name\":\"product\"}]}");
        KindDeployment.primaryReads(base, ""); // The application default is primary.
        KindDeployment.primaryReads(base, "shardshop.catalog.read-profile=primary\n");
        for (String properties : List.of("shardshop.catalog.read-profile=replica",
                "%prod.shardshop.catalog.read-profile=replica", "shardshop.catalog.read-profile=${READ_PROFILE:primary}"))
            assertThrows(IOException.class, () -> KindDeployment.primaryReads(base, properties), properties);
        for (String env : List.of(
                "{\"name\":\"SHARDSHOP_CATALOG_READ_PROFILE\",\"value\":\"replica\"}",
                "{\"name\":\"SHARDSHOP_CATALOG_READ_PROFILE\",\"valueFrom\":{\"configMapKeyRef\":{\"name\":\"drill\",\"key\":\"profile\"}}}",
                "{\"name\":\"JAVA_TOOL_OPTIONS\",\"value\":\"-Xmx128m -Dshardshop.catalog.read-profile=replica\"}")) {
            ObjectNode overridden = (ObjectNode) base.deepCopy();
            ((ObjectNode) overridden.path("containers").get(0)).putArray("env").add(json(env));
            assertThrows(IOException.class, () -> KindDeployment.primaryReads(overridden, ""), env);
        }
        ObjectNode args = (ObjectNode) base.deepCopy();
        ((ObjectNode) args.path("containers").get(0)).putArray("args").add("-Dshardshop.catalog.read-profile=replica");
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(args, ""));
        JsonNode unrelated = json("{\"containers\":[{\"name\":\"product\",\"env\":[{\"name\":\"JAVA_TOOL_OPTIONS\",\"value\":\"-Xmx128m\"}]}]}");
        KindDeployment.primaryReads(unrelated, "");
    }

    @Test
    void deploymentGateChecksTheProductTemplateBeforeTrustingReadyReplicaCounts() throws Exception {
        KindLab lab = mock(KindLab.class);
        when(lab.kubectlAttempt("get", "configmap", "shardshop-product-config", "-o", "json"))
                .thenReturn(new KindLab.CommandResult(0, "{\"data\":{\"application.properties\":\"shardshop.catalog.max-pool-size=4\"}}", ""));
        when(lab.kubectlAttempt("get", "configmap", "shardshop-routing", "-o", "json"))
                .thenReturn(new KindLab.CommandResult(0, "{\"data\":{\"application.properties\":\"shardshop.routing.version=2\"}}", ""));
        String deployment = """
                {"metadata":{"generation":1},"spec":{"replicas":2,"template":{"spec":{"containers":[
                {"name":"product","env":[{"name":"SHARDSHOP_CATALOG_READ_PROFILE","value":"replica"}]}]}}},
                "status":{"readyReplicas":2,"updatedReplicas":2,"observedGeneration":1}}
                """;
        when(lab.kubectlAttempt("get", "deployment", "shardshop-product", "-o", "json"))
                .thenReturn(new KindLab.CommandResult(0, deployment, ""));
        IOException failure = assertThrows(IOException.class, () -> new KindDeployment(lab).deployed());
        assertTrue(failure.getMessage().contains("primary reads"));
        // A clean pod template must not hide a replica setting in the known routing source.
        when(lab.kubectlAttempt("get", "deployment", "shardshop-product", "-o", "json"))
                .thenReturn(new KindLab.CommandResult(0, deployment.replace("replica\"", "primary\""), ""));
        when(lab.kubectlAttempt("get", "configmap", "shardshop-routing", "-o", "json"))
                .thenReturn(new KindLab.CommandResult(0,
                        "{\"data\":{\"application.properties\":\"shardshop.catalog.read-profile=replica\"}}", ""));
        IOException routingFailure = assertThrows(IOException.class, () -> new KindDeployment(lab).deployed());
        assertTrue(routingFailure.getMessage().contains("primary reads"));
    }

    @Test
    void externalConfigurationCannotHideAReplicaOverride() {
        JsonNode base = json("{\"containers\":[{\"name\":\"product\"}]}");
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(base, "quarkus.config.locations=file:/drill.properties"));
        JsonNode indirect = json("{\"containers\":[{\"name\":\"product\",\"envFrom\":[{\"configMapRef\":{\"name\":\"drill\"}}]}]}");
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(indirect, ""));
    }

    @Test
    void routingSourceOverridesCannotHideTheReadProfile() throws IOException {
        JsonNode base = json("{\"containers\":[{\"name\":\"product\"}]}");
        KindDeployment.primaryReads(base, "shardshop.catalog.max-pool-size=4", "shardshop.routing.version=2");
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(base, "shardshop.catalog.read-profile=primary",
                "shardshop.catalog.read-profile=replica"));
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(base, "shardshop.routing.config=file:/drill.properties"));
        JsonNode changed = json("{\"containers\":[{\"name\":\"product\",\"env\":[{\"name\":\"SHARDSHOP_ROUTING_CONFIG\",\"value\":\"file:/drill.properties\"}]}]}");
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(changed, ""));
        JsonNode jvm = json("{\"containers\":[{\"name\":\"product\",\"args\":[\"-Dshardshop.routing.config=file:/drill.properties\"]}]}");
        assertThrows(IOException.class, () -> KindDeployment.primaryReads(jvm, ""));
    }

    @Test
    void missingOrEmptyMountedConfigurationCannotStandInForTheApplicationDefault() throws IOException {
        for (String document : List.of("{}", "{\"data\":{\"application.properties\":null}}",
                "{\"data\":{\"application.properties\":\"\"}}", "{\"data\":{\"application.properties\":123}}"))
            assertThrows(IOException.class, () -> KindDeployment.productConfiguration(json(document), "shardshop-routing"));
        assertEquals("shardshop.routing.version=2", KindDeployment.productConfiguration(
                json("{\"data\":{\"application.properties\":\"shardshop.routing.version=2\"}}"), "shardshop-routing"));
    }

    @Test
    void catalogCountsAcceptOnlyTheDatasetsAndTheGateFixtures() throws IOException {
        KindDeployment.validateCatalogCounts(Map.of("shard-a", "50 50 0 0 0 0 0 0 100",
                "shard-b", "50 100 0 0 0 0 0 0 150", "shard-c", "51 51 0 0 0 0 0 0 102"));
        KindDeployment.validateCatalogCounts(Map.of("shard-a", "50 50 1 1 0 0 0 0 102",
                "shard-b", "50 100 1 1 0 0 0 0 152", "shard-c", "51 51 0 0 0 0 0 0 102"));
        // The retained lab of 2026-10-21: catalog-v1 and the step 4.9 gate fixture stay beside the current rows.
        KindDeployment.validateCatalogCounts(Map.of("shard-a", "50 50 1 1 50 100 1 2 255",
                "shard-b", "50 100 1 1 50 100 0 0 302", "shard-c", "51 51 0 0 50 100 0 0 252"));
        // Step 4.12 validation: the earlier rule accepted any number of rows beside the known ones.
        for (String invalid : List.of("151 201 2 2 150 300 1 2 810", "151 201 0 0 0 0 0 0 353", "151 200 0 0 0 0 0 0 351",
                "151 201 3 2 0 0 0 0 357", "151 201 0 0 150 299 0 0 801", "151 201 0 0 0 0 2 2 356", "151 201 0 0 351"))
            assertThrows(IOException.class, () -> KindDeployment.validateCatalogCounts(Map.of("all", invalid)), invalid);
    }

    @Test
    void workloadAllowsOnlyLiteralHttpSettingsAndScratchStorage() throws IOException {
        KindDeployment.httpOnly(spec());
        for (String volume : new String[]{"secret", "projected", "hostPath", "persistentVolumeClaim", "configMap", "csi"}) {
            ObjectNode invalid = spec();
            ((ArrayNode) invalid.path("volumes")).removeAll().addObject().put("name", "credentials").putObject(volume);
            assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid), volume);
        }
        ObjectNode absent = spec();
        absent.remove("automountServiceAccountToken");
        assertThrows(IOException.class, () -> KindDeployment.httpOnly(absent));
        absent.put("automountServiceAccountToken", true);
        assertThrows(IOException.class, () -> KindDeployment.httpOnly(absent));
        absent.put("automountServiceAccountToken", "false");
        assertThrows(IOException.class, () -> KindDeployment.httpOnly(absent));
    }

    @Test
    void workloadRejectsCredentialsInEveryContainerGroup() {
        for (String group : new String[]{"containers", "initContainers", "ephemeralContainers"}) {
            for (String source : new String[]{"envFrom", "valueFrom", "credentialName", "privileged", "volumeDevices"}) {
                ObjectNode invalid = spec();
                ObjectNode container = group.equals("containers") ? (ObjectNode) invalid.path(group).get(0)
                        : invalid.putArray(group).addObject().put("name", "helper");
                switch (source) {
                    case "envFrom" -> container.putArray("envFrom").addObject().putObject("secretRef").put("name", "db");
                    case "valueFrom" -> container.putArray("env").addObject().put("name", "SETTING").putObject("valueFrom")
                            .putObject("configMapKeyRef").put("name", "db");
                    case "credentialName" -> container.putArray("env").addObject().put("name", "PRODUCT_APP_PASSWORD").put("value", "hidden");
                    case "privileged" -> container.putObject("securityContext").put("privileged", true);
                    case "volumeDevices" -> container.putArray("volumeDevices").addObject().put("name", "db");
                }
                assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid), group + "/" + source);
            }
        }
        ObjectNode pullSecret = spec();
        pullSecret.putArray("imagePullSecrets").addObject().put("name", "credential");
        assertThrows(IOException.class, () -> KindDeployment.httpOnly(pullSecret));
    }

    @Test
    void digestMustBeTheSingleRequestedSuccessfulSummary() throws IOException {
        assertEquals(DIGEST, KindDeployment.digest("Resolved catalog-v2 key-to-ID SHA-256 " + DIGEST
                + "\nSeeded and verified catalog-v2 key-to-ID SHA-256 " + DIGEST, "Seeded and verified catalog-v2"));
        assertThrows(IOException.class, () -> KindDeployment.digest("Resolved catalog-v2 key-to-ID SHA-256 " + DIGEST,
                "Seeded and verified catalog-v2"));
        assertThrows(IOException.class, () -> KindDeployment.digest("Seeded and verified catalog-v2 key-to-ID SHA-256 " + DIGEST + "b",
                "Seeded and verified catalog-v2"));
        String summary = "Seeded and verified catalog-v2 key-to-ID SHA-256 " + DIGEST;
        assertThrows(IOException.class, () -> KindDeployment.digest(summary + "\n" + summary, "Seeded and verified catalog-v2"));
    }

    @Test
    void runningReaderDigestSurvivesLargeLogsAndRotationOfItsStartupSummary() throws Exception {
        KindLab lab = mock(KindLab.class);
        String recurring = report("2026-10-13T12:00:31Z", "30.0", 6000, "200.0", "OK=6000")
                + ", catalog-v2 key-to-ID SHA-256 " + DIGEST + "\n";
        // The startup line has rotated away; the current log alone is over the command-output limit.
        String history = recurring.repeat(22_000);
        when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> {
            List<String> arguments = List.of((String[]) invocation.getRawArguments()[0]);
            assertEquals(List.of("logs", "reader-1"), arguments.subList(0, 2));
            boolean bounded = arguments.contains("--tail=100") && arguments.contains("--limit-bytes=65536");
            if (!bounded) throw new IOException("Kind command output exceeded 4194304 bytes");
            return history.substring(history.length() - recurring.length() * 100);
        });

        assertEquals(DIGEST, new KindDeployment(lab).readerDigest(json("{\"metadata\":{\"name\":\"reader-1\"}}")));
    }

    @Test
    void readerDigestAcceptsStartupOrConsistentReportsAndRejectsMissingOrConflictingEvidence() throws IOException {
        String startup = "Resolved catalog-v2 [US 50 sellers, 100 products], key-to-ID SHA-256 " + DIGEST;
        String interval = report("2026-10-13T12:00:31Z", "30.0", 6000, "200.0", "OK=6000")
                + ", catalog-v2 key-to-ID SHA-256 " + DIGEST;
        assertEquals(DIGEST, KindDeployment.readerDigest(startup));
        assertEquals(DIGEST, KindDeployment.readerDigest(startup + "\n" + interval + "\n" + interval));
        assertThrows(IOException.class, () -> KindDeployment.readerDigest(interval + "\n" + interval.replace(DIGEST, "b".repeat(64))));
        assertThrows(IOException.class, () -> KindDeployment.readerDigest(interval.replace(DIGEST, "invalid")));
        assertThrows(IOException.class, () -> KindDeployment.readerDigest(interval.replace("catalog-v2", "catalog-v1")));
        assertThrows(IOException.class, () -> KindDeployment.readerDigest("unrelated key-to-ID SHA-256 " + DIGEST));
        assertThrows(IOException.class, () -> KindDeployment.readerDigest(""));
    }

    @Test
    void currentDeploymentSeederAnnotationOverridesTheUnchangedPodsOlderRun() throws IOException {
        JsonNode deployment = json("""
                {"metadata":{"annotations":{"shardshop.javacraft/seeder-job":"shardshop-application-seeder-2"}}}
                """);
        JsonNode olderPod = json("{\"metadata\":{\"labels\":{\"shardshop.javacraft/run\":\"old-release\"}}}");
        assertEquals("shardshop-application-seeder-2", KindDeployment.seederJobName(deployment, olderPod));
        assertEquals("shardshop-application-seeder-2", KindDeployment.seederJobName(deployment, json("{}")));
    }

    @Test
    void absentCurrentAnnotationRetainsLegacyRunLookupButMissingEvidenceFails() throws IOException {
        JsonNode legacyPod = json("{\"metadata\":{\"labels\":{\"shardshop.javacraft/run\":\"manual-1\"}}}");
        assertEquals("shardshop-product-seeder-manual-1", KindDeployment.seederJobName(json("{}"), legacyPod));
        assertThrows(IOException.class, () -> KindDeployment.seederJobName(json("{}"), json("{}")));
        assertThrows(IOException.class, () -> KindDeployment.seederJobName(null, legacyPod));
        for (String run : List.of("INVALID", "run/foreign", "trailing-", "a".repeat(64))) {
            ObjectNode invalid = (ObjectNode) json("{}");
            invalid.putObject("metadata").putObject("labels").put("shardshop.javacraft/run", run);
            assertThrows(IOException.class, () -> KindDeployment.seederJobName(json("{}"), invalid), run);
        }
    }

    @Test
    void invalidCurrentAnnotationCannotSilentlyFallBackToAnEarlierSuccessfulJob() {
        JsonNode olderPod = json("{\"metadata\":{\"labels\":{\"shardshop.javacraft/run\":\"old-release\"}}}");
        for (String name : List.of("", "INVALID", "job/foreign", "job-", "a".repeat(64), "job\nforeign")) {
            ObjectNode deployment = (ObjectNode) json("{}");
            deployment.putObject("metadata").putObject("annotations").put("shardshop.javacraft/seeder-job", name);
            assertThrows(IOException.class, () -> KindDeployment.seederJobName(deployment, olderPod), name);
        }
        for (JsonNode value : List.of(json("{\"value\":null}").path("value"), json("{\"value\":1}").path("value"))) {
            ObjectNode deployment = (ObjectNode) json("{}");
            deployment.putObject("metadata").putObject("annotations").set("shardshop.javacraft/seeder-job", value);
            assertThrows(IOException.class, () -> KindDeployment.seederJobName(deployment, olderPod));
        }
    }

    @Test
    void onlySuccessfulJobsSatisfyTheSeedingGate() throws IOException {
        assertTrue(KindDeployment.completed(json("{\"status\":{\"conditions\":[{\"type\":\"Complete\",\"status\":\"True\"}]}}")));
        assertFalse(KindDeployment.completed(json("{\"status\":{\"conditions\":[{\"type\":\"Complete\",\"status\":\"False\"}]}}")));
        assertFalse(KindDeployment.completed(json("{}")));
        assertThrows(IOException.class, () -> KindDeployment.completed(json("""
                {"status":{"conditions":[{"type":"Complete","status":"True"},{"type":"Failed","status":"True"}]}}
                """)));
    }

    @Test
    void productGeneratorsAreUniqueSingleReservationsBoundedByTheRegistry() throws IOException {
        assertEquals(Map.of("p1", 1, "p2", 1023), KindDeployment.generatorIds(Map.of("p1", reserved(1), "p2", reserved(1023)), 1023));
        for (Map<String, String> invalid : java.util.List.of(Map.of("p1", "no reservation"), Map.of("p1", reserved(0)),
                Map.of("p1", reserved(1024)), Map.of("p1", reserved(3)), Map.of("p1", reserved(1) + "\n" + reserved(2)),
                Map.of("p1", reserved(1), "p2", reserved(1)), Map.of("p1", "Reserved generator ID 01 for this JVM start."))) {
            assertThrows(IOException.class, () -> KindDeployment.generatorIds(invalid, 2));
        }
        for (String value : new String[]{"", "-1", "01", "1024", "999999", "1.0", " 1"})
            assertThrows(IOException.class, () -> KindDeployment.highWater(value), value);
        assertEquals(0, KindDeployment.highWater("0"));
    }

    @Test
    void countersSumOnlyGetProductReadsAndRejectMissingOrInvalidSamples() throws IOException {
        String labels = "method=\"GET\",uri=\"/api/v1/sellers/{sellerId}/products/{productId}\"";
        assertEquals(7, KindDeployment.readCounter("""
                http_server_requests_seconds_count{method="POST",uri="/api/v1/sellers"} 100
                http_server_requests_seconds_count{method="GET",uri="/api/v1/sellers/{sellerId}/products/{productId}",status="200"} 4
                http_server_requests_seconds_count{status="503",uri="/api/v1/sellers/{sellerId}/products/{productId}",method="GET"} 3.0
                http_server_requests_seconds_count{method="GET",uri="/api/v1/sellers/by-key/{key}"} 100
                """));
        for (String value : new String[]{"NaN", "Infinity", "-1", "oops"})
            assertThrows(IOException.class, () -> KindDeployment.readCounter("http_server_requests_seconds_count{" + labels + "} " + value));
        assertEquals(0, KindDeployment.readCounter("# no product metric"));
    }

    @Test
    void freshProductPodWithoutReadSamplesStartsWithAZeroTrafficBaseline() throws IOException {
        assertEquals(0, KindDeployment.readCounter("""
                # HELP http_server_requests_seconds_count HTTP server requests
                # TYPE http_server_requests_seconds_count counter
                http_server_requests_seconds_count{method="GET",uri="/q/health",status="200"} 1
                http_server_requests_seconds_count{method="POST",uri="/api/v1/sellers",status="201"} 2
                """));
    }

    @Test
    void cleanIntervalMustStartAfterTheObservationRatherThanMerelyEndAfterIt() throws IOException {
        Instant boundary = Instant.parse("2026-10-13T12:00:00Z");
        assertFalse(KindDeployment.freshCleanInterval(report("2026-10-13T11:59:59Z", "30.0", 6000, "200.0", "OK=6000"), boundary, 180));
        assertFalse(KindDeployment.freshCleanInterval(report("2026-10-13T12:00:20Z", "30.0", 6000, "200.0", "OK=6000"), boundary, 180));
        assertFalse(KindDeployment.freshCleanInterval(report("2026-10-13T12:00:30Z", "30.0", 6000, "200.0", "OK=6000"), boundary, 180));
        assertTrue(KindDeployment.freshCleanInterval(report("2026-10-13T12:00:31Z", "30.0", 5400, "180.0", "OK=5400"), boundary, 180));
    }

    @Test
    void freshIntervalRequiresOnlyOkOutcomesAndTheConfiguredRate() throws IOException {
        Instant boundary = Instant.parse("2026-10-13T12:00:00Z");
        for (String outcomes : new String[]{"OK=5999, TRANSPORT=1", "OK=5999", "OK=6000, HTTP_ERROR=1", "", "OK=6000, OK=6000"})
            assertFalse(KindDeployment.freshCleanInterval(report("2026-10-13T12:00:31Z", "30.0", 6000, "200.0", outcomes), boundary, 180));
        assertFalse(KindDeployment.freshCleanInterval(report("2026-10-13T12:00:31Z", "30.0", 5397, "179.9", "OK=5397"), boundary, 180));
        assertFalse(KindDeployment.freshCleanInterval("2026-10-13T12:00:31Z Product reads, total 30.0 s: 6000 requests, 200.0/s, outcomes {OK=6000}, pool {}", boundary, 180));
        assertThrows(IOException.class, () -> KindDeployment.intervals(report("missing-timestamp", "30.0", 6000, "200.0", "OK=6000")));
        assertThrows(IOException.class, () -> KindDeployment.intervals(report("2026-10-13T12:00:31Z", "0.0", 6000, "200.0", "OK=6000")));
    }

    @Test
    void configuredReaderRateAndReportIntervalControlTheObservation() throws IOException {
        ObjectNode pod = (ObjectNode) json("{}");
        pod.set("spec", spec());
        assertEquals(200, KindDeployment.readerRate(pod));
        assertEquals(180, KindDeployment.minimumRate(pod));
        assertEquals(Duration.ofSeconds(30), KindDeployment.readerInterval(pod));
        ArrayNode env = (ArrayNode) pod.path("spec").path("containers").get(0).path("env");
        ObjectNode rate = env.addObject().put("name", "SHARDSHOP_READER_REQUEST_RATE").put("value", "400");
        ObjectNode interval = env.addObject().put("name", "SHARDSHOP_READER_REPORT_INTERVAL").put("value", "5s");
        assertEquals(400, KindDeployment.readerRate(pod));
        assertEquals(360, KindDeployment.minimumRate(pod));
        assertEquals(Duration.ofSeconds(5), KindDeployment.readerInterval(pod));
        rate.put("value", "100");
        assertEquals(180, KindDeployment.minimumRate(pod), "A reduced configured rate cannot relax the baseline acceptance floor");
        rate.put("value", "0");
        interval.put("value", "0s");
        assertThrows(IOException.class, () -> KindDeployment.readerRate(pod));
        assertThrows(IOException.class, () -> KindDeployment.readerInterval(pod));
    }
}
