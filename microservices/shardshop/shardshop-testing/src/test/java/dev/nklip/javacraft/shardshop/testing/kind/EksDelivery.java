package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** EKS-only checks and drills of the Argo CD delivery contract of the database layer. */
public final class EksDelivery {
    static final List<String> LAYERS = List.of("cnpg", "barman-cloud", "shardshop-namespace",
            "shardshop-infrastructure", "shardshop-migrations", "shardshop-routing");
    private static final String ROOT = "shardshop";
    private static final String MIGRATIONS = "shardshop-migrations";
    private static final String ROUTING = "shardshop-routing";
    private static final String DRILL = "eks-delivery-drill";
    private static final Duration SYNC = Duration.ofMinutes(10);
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+-[0-9a-z]+");
    private static final Set<String> FINISHED = Set.of("Succeeded", "Failed", "Error");
    private final KindLab lab;
    private final KindTopology topology;

    public EksDelivery() { this(new KindLab()); }
    EksDelivery(KindLab lab) { this.lab = lab; this.topology = new KindTopology(lab); }

    /** Each layer comes from a digest-pinned OCI chart, is synced and healthy, and cannot delete data. */
    public void delivered() throws IOException, InterruptedException {
        lab.await("a synced and healthy root Application", SYNC, () -> settled(application(ROOT)));
        Chart base = shardshopChart();
        for (String name : LAYERS) {
            JsonNode application = application(name);
            require(settled(application), name + " is not synced and healthy");
            JsonNode source = application.path("spec").path("source");
            if (name.equals(MIGRATIONS)) {
                // Generated hook names contain the revision; ECR keeps this version immutable.
                equal(source.path("targetRevision").asText(), base.version(), "Chart version of the migrations");
            } else {
                require(source.path("repoURL").asText().startsWith("oci://") && DIGEST.matcher(source.path("targetRevision").asText()).matches(),
                        name + " is not a digest-pinned OCI source");
            }
            require(!application.path("spec").path("syncPolicy").path("automated").path("prune").asBoolean(true), name + " can prune resources");
            require(!application.path("metadata").path("finalizers").toString().contains("resources-finalizer"),
                    name + " deletes its resources together with the Application");
        }
        equal(routingRevision(), base.digest(), "Published routing revision");
        topology.publishedTopology();
        migrationHooks(application(MIGRATIONS));
    }

    /**
     * A resync without changes, a changed migration that is interrupted and then retried, a failed migration
     * before the routing publication, and the recovery. The runbook gives the digests of the two drill charts.
     */
    public void contractDrill() throws IOException, InterruptedException {
        Chart changed = chartProperties("shardshop.eks.changedChart", "shardshop.eks.changedVersion");
        Chart failing = chartProperties("shardshop.eks.failingChart", "shardshop.eks.failingVersion");
        // The catalog migrations of the drill charts. They stay in the databases, thus a second drill on the
        // same databases needs new versions.
        String changedMigration = System.getProperty("shardshop.eks.changedMigration", "9001");
        String failingMigration = System.getProperty("shardshop.eks.failingMigration", "9002");
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                lab.await("a synced and healthy root Application", SYNC, () -> settled(application(ROOT)));
                Chart base = shardshopChart();
                String shard = lab.shards().getFirst();

                // A resync without changes runs new migration Jobs. Flyway finds no pending migration.
                Map<String, String> history = history();
                Set<String> earlier = migrationHooks(application(MIGRATIONS));
                String started = operationStart(MIGRATIONS);
                sync(MIGRATIONS, base.version());
                Set<String> resynced = migrationHooks(awaitOperation(MIGRATIONS, base.version(), started, true));
                require(Collections.disjoint(earlier, resynced), "A resync used a completed migration Job again");
                equal(history(), history, "Schema history after a resync without changes");

                // A changed migration, interrupted while it runs. The interrupted Job stays, and the routing stays.
                started = operationStart(MIGRATIONS);
                setShardshopChart(changed);
                String primary = topology.primary(shard);
                lab.await("the drill migration on " + shard, SYNC, () -> topology.sql(primary, "SELECT count(*) FROM pg_stat_activity "
                        + "WHERE query LIKE '%pg_sleep(12)%' AND state = 'active' AND pid <> pg_backend_pid()").equals("1"));
                String job = activeMigrationJob(shard);
                lab.kubectl("delete", "pod", "-l", "job-name=" + job, "--wait=false");
                awaitOperation(MIGRATIONS, changed.version(), started, false);
                require(failedJob(job), "The interrupted migration Job " + job + " was deleted or did not fail");
                require(!versions(primary, "catalog").contains(changedMigration), "The interrupted migration was committed");
                equal(routingRevision(), base.digest(), "Routing revision after an interrupted migration");

                // The retry creates new Jobs and applies the changed migration on each shard.
                started = operationStart(MIGRATIONS);
                sync(MIGRATIONS, changed.version());
                awaitOperation(MIGRATIONS, changed.version(), started, true);
                for (String name : lab.shards()) {
                    require(versions(topology.primary(name), "catalog").contains(changedMigration), name + " lacks the changed migration");
                }
                require(failedJob(job), "The retry deleted the interrupted migration Job " + job);
                syncRoot();
                equal(routingRevision(), changed.digest(), "Routing revision after the migrations");

                // A failed migration stops the root Application before the routing publication.
                started = operationStart(MIGRATIONS);
                setShardshopChart(failing);
                awaitOperation(MIGRATIONS, failing.version(), started, false);
                require(!versions(topology.primary(shard), "catalog").contains(failingMigration), "The failed migration was committed");
                equal(routingRevision(), changed.digest(), "Routing revision after a failed migration");

                // The recovery with the corrected chart, then the return to the chart of the run.
                for (Chart chart : List.of(changed, base)) {
                    setShardshopChart(chart);
                    recoverMigrations(chart);
                    lab.await("the root Application on " + chart.version(), SYNC, () -> settled(application(ROOT))
                            && chart.digest().equals(routingRevision()) && settled(application(MIGRATIONS)));
                }
                migrationHooks(application(MIGRATIONS));
            });
        }
    }

    /** The PostSync migration Jobs of the last sync: one successful Job for each stream and database. */
    Set<String> migrationHooks(JsonNode application) throws IOException, InterruptedException {
        Set<String> names = new HashSet<>();
        for (JsonNode resource : application.path("status").path("operationState").path("syncResult").path("resources")) {
            if (!resource.path("kind").asText().equals("Job") || !resource.path("hookType").asText().equals("PostSync")) continue;
            require(resource.path("hookPhase").asText().equals("Succeeded"), "Migration Job " + resource.path("name").asText() + " did not succeed");
            names.add(resource.path("name").asText());
        }
        equal(names.size(), 2 * lab.shards().size() + 1, "Migration Jobs of the last sync");
        return names;
    }

    /** The applied schema versions of each migration stream. */
    private Map<String, String> history() throws IOException, InterruptedException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String shard : lab.shards()) {
            String primary = topology.primary(shard);
            for (String schema : List.of("catalog", "ordering")) result.put(shard + "/" + schema, versions(primary, schema));
        }
        result.put("ledger-db/ledger", versions(topology.primary("ledger-db"), "ledger", "ledger"));
        return result;
    }

    private String versions(String primary, String schema) throws IOException, InterruptedException {
        return versions(primary, "shardshop", schema);
    }

    private String versions(String primary, String database, String schema) throws IOException, InterruptedException {
        return topology.sql(primary, database, "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM " + schema
                + ".flyway_schema_history WHERE success");
    }

    private String activeMigrationJob(String shard) throws IOException, InterruptedException {
        for (JsonNode job : KindLab.items(KindLab.parse(lab.kubectl("get", "jobs", "-l",
                "app.kubernetes.io/component=catalog,shardshop.javacraft/migration-target=" + shard, "-o", "json")))) {
            if (job.path("status").path("active").asInt() > 0) return job.path("metadata").path("name").asText();
        }
        throw new IOException("No active catalog migration Job on " + shard);
    }

    private boolean failedJob(String name) throws IOException, InterruptedException {
        JsonNode job = KindLab.parse(lab.kubectl("get", "job", name, "--ignore-not-found", "-o", "json"));
        return job.path("status").path("failed").asInt() > 0;
    }

    /**
     * Argo CD does not repeat a failed sync for unchanged manifests. A return to a chart whose ConfigMaps exist
     * thus needs an explicit sync of the migrations, as the runbook tells the operator.
     */
    private void recoverMigrations(Chart chart) throws IOException, InterruptedException {
        lab.await("the migrations on " + chart.version(), SYNC, () -> chart.version().equals(
                application(MIGRATIONS).path("spec").path("source").path("targetRevision").asText()));
        JsonNode migrations = application(MIGRATIONS);
        String phase = migrations.path("status").path("operationState").path("phase").asText();
        if (!settled(migrations) && !phase.equals("Running")) {
            String started = operationStart(MIGRATIONS);
            sync(MIGRATIONS, chart.version());
            awaitOperation(MIGRATIONS, chart.version(), started, true);
        }
    }

    /** Stop a root sync that waits for a degraded wave, then change the shared chart of all layers. */
    private void setShardshopChart(Chart chart) throws IOException, InterruptedException {
        stopRootOperation();
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        patch.putObject("spec").putObject("source").putObject("helm").putObject("valuesObject").putObject("charts")
                .put("shardshop", chart.digest()).put("shardshopVersion", chart.version());
        lab.kubectl("-n", "argocd", "patch", "application", ROOT, "--type", "merge", "-p", patch.toString());
    }

    /** The root Application publishes the later waves only in a new sync. */
    private void syncRoot() throws IOException, InterruptedException {
        stopRootOperation();
        String started = operationStart(ROOT);
        sync(ROOT, application(ROOT).path("spec").path("source").path("targetRevision").asText());
        lab.await("a root sync after the retry", SYNC, () -> {
            JsonNode operation = application(ROOT).path("status").path("operationState");
            return !operation.path("startedAt").asText().equals(started) && operation.path("phase").asText().equals("Succeeded");
        });
    }

    private void stopRootOperation() throws IOException, InterruptedException {
        if (!application(ROOT).path("status").path("operationState").path("phase").asText().equals("Running")) return;
        lab.kubectl("-n", "argocd", "patch", "application", ROOT, "--type", "merge", "-p",
                "{\"status\":{\"operationState\":{\"phase\":\"Terminating\"}}}");
        lab.await("the end of the waiting root sync", SYNC, () -> FINISHED.contains(
                application(ROOT).path("status").path("operationState").path("phase").asText()));
    }

    private void sync(String name, String revision) throws IOException, InterruptedException {
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        ObjectNode operation = patch.putObject("operation");
        operation.putObject("initiatedBy").put("username", DRILL);
        operation.putObject("sync").put("revision", revision);
        lab.kubectl("-n", "argocd", "patch", "application", name, "--type", "merge", "-p", patch.toString());
    }

    /** Wait for a new, finished sync of the revision, and check its result. */
    private JsonNode awaitOperation(String name, String revision, String previousStart, boolean success)
            throws IOException, InterruptedException {
        AtomicReference<JsonNode> result = new AtomicReference<>();
        lab.await(name + " sync of " + revision, SYNC, () -> {
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

    private String operationStart(String name) throws IOException, InterruptedException {
        return application(name).path("status").path("operationState").path("startedAt").asText();
    }

    private Chart shardshopChart() throws IOException, InterruptedException {
        JsonNode charts = application(ROOT).path("spec").path("source").path("helm").path("valuesObject").path("charts");
        return chart(charts.path("shardshop").asText(), charts.path("shardshopVersion").asText());
    }

    private String routingRevision() throws IOException, InterruptedException {
        return application(ROUTING).path("spec").path("source").path("targetRevision").asText();
    }

    private JsonNode application(String name) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("-n", "argocd", "get", "application", name, "-o", "json"));
    }

    static boolean settled(JsonNode application) {
        JsonNode status = application.path("status");
        String operation = status.path("operationState").path("phase").asText("Succeeded");
        return status.path("sync").path("status").asText().equals("Synced") && status.path("health").path("status").asText().equals("Healthy")
                && operation.equals("Succeeded");
    }

    private static Chart chartProperties(String digest, String version) throws IOException {
        return chart(System.getProperty(digest, ""), System.getProperty(version, ""));
    }

    private static Chart chart(String digest, String version) throws IOException {
        require(DIGEST.matcher(digest).matches() && VERSION.matcher(version).matches(),
                "Each shared chart needs its digest and its version from the runbook");
        return new Chart(digest, version);
    }

    /** One pushed shared chart: the digest pins the layers, and the immutable version names the migration hooks. */
    record Chart(String digest, String version) { }
}
