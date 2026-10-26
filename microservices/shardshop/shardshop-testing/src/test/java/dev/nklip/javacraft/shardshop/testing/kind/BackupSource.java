package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/**
 * Coordinated read-only database capture while all application writers are stopped.
 * Every queue in the application vhost must be empty, including DLQ parking queues;
 * retained dead letters conservatively prevent capture rather than being skipped.
 */
final class BackupSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> APPLICATIONS = Set.of("shardshop-product", "shardshop-order", "shardshop-order-saga",
            "shardshop-ledger", "shardshop-order-producer", "shardshop-product-reader", "shardshop-product-seeder-control");
    private static final Set<String> DRAINERS = Set.of("shardshop-order-saga", "shardshop-ledger");

    private BackupSource() { }

    static ObjectNode capture(KindLab lab, BackupArtifacts bundle, Path latestAllocatorFile)
            throws IOException, InterruptedException {
        ObjectNode metadata = JSON.createObjectNode();
        KindTopology topology = new KindTopology(lab);
        KindDeployment deployment = new KindDeployment(lab);
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                deployment.deployed();
                new KindSaga(lab).deployed();
                Map<String, KindTopology.Shard> initial = topology.observe();
                Map<String, String> identities = identities(lab);
                guard.onClose("source topology, business invariants and latest allocator observation", () -> {
                    BackupAllocator.observe(lab, latestAllocatorFile);
                    require(initial.equals(topology.observe()), "Source topology changed during backup");
                    require(identities.equals(identities(lab)), "Source database identity changed during backup");
                    deployment.deployed();
                    new KindSaga(lab).deployed();
                    new KindSaga(lab).invariants();
                    return true;
                });
                new KindFailoverDrill(lab).controls(guard);
                Map<String, JsonNode> saved = deployments(lab);
                guard.onClose("restore exact source deployment replicas", () -> {
                    restoreAll(lab, saved);
                    deployment.deployed();
                    return true;
                });
                requireNoJobs(lab);
                for (String name : saved.keySet()) if (!DRAINERS.contains(name)) stop(lab, name, saved.get(name));
                lab.await("source application drain for backup", Duration.ofSeconds(180), () -> drained(lab));
                for (String name : DRAINERS) stop(lab, name, saved.get(name));
                require(drained(lab), "Source drain changed after consumers stopped");
                requireNoJobs(lab);
                metadata.set("allocator", BackupAllocator.observe(lab, latestAllocatorFile));
                ObjectNode configuration = metadata.putObject("configuration");
                for (String config : List.of("shardshop-routing", "shardshop-migration-topology",
                        "shardshop-snowflake-generators", "shardshop-generator-identity")) {
                    configuration.set(config, KindLab.parse(lab.kubectl("get", "configmap", config, "-o", "json")));
                }
                metadata.set("inventory", lab.inventory());
                metadata.put("sourceCluster", lab.clusterName());
                ArrayNode shardNames = metadata.putArray("shards");
                lab.shards().forEach(shardNames::add);
                metadata.set("routing", configuration.path("shardshop-routing").deepCopy());
                metadata.put("brokerDefinitions", "rabbitmq-definitions.json");
                brokerDefinitions(lab, bundle);
                ArrayNode databases = metadata.putArray("databases");
                Map<String, JsonNode> roles = new LinkedHashMap<>();
                List<String> sources = new ArrayList<>(lab.shards());
                sources.add("ledger-db");
                for (String source : sources) {
                    JsonNode cluster = KindLab.parse(lab.kubectl("get", "cluster", source, "-o", "json"));
                    String primary = cluster.path("status").path("currentPrimary").asText();
                    require(!primary.isBlank(), "Source database has no authoritative primary");
                    String database = source.equals("ledger-db") ? "ledger" : "shardshop";
                    require("0".equals(sql(lab, primary, database,
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() "
                                    + "AND backend_type='client backend' AND (state<>'idle' OR xact_start IS NOT NULL)")),
                            "Source has active application database sessions");
                    ObjectNode entry = databases.addObject().put("name", source).put("dump", source + ".dump")
                            .put("clusterUid", cluster.path("metadata").path("uid").asText());
                    entry.put("systemIdentifier", sql(lab, primary, database, "SELECT system_identifier FROM pg_control_system()"));
                    entry.put("databaseOid", sql(lab, primary, database, "SELECT oid FROM pg_database WHERE datname=current_database()"));
                    entry.put("serverVersion", sql(lab, primary, database, "SHOW server_version_num"));
                    entry.set("databaseSecurity", jsonSql(lab, primary, database,
                            "SELECT json_build_object('owner',pg_get_userbyid(d.datdba),'privileges',"
                                    + "(SELECT coalesce(json_agg(json_build_object('grantee',"
                                    + "CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END,"
                                    + "'privilege',a.privilege_type,'grantable',a.is_grantable) "
                                    + "ORDER BY CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END,"
                                    + "a.privilege_type),'[]') FROM aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a)) "
                                    + "FROM pg_database d WHERE d.datname=current_database()"));
                    JsonNode schemas = jsonSql(lab, primary, database,
                            "SELECT coalesce(json_agg(json_build_object('name',nspname,'owner',pg_get_userbyid(nspowner)) ORDER BY nspname),'[]') FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname<>'information_schema'");
                    entry.set("schemas", schemas);
                    mergeRoles(roles, roleMetadata(lab, primary, database));
                    ArrayNode fingerprints = entry.putArray("tableFingerprints");
                    ObjectNode histories = entry.putObject("migrationHistories");
                    JsonNode tables = jsonSql(lab, primary, database,
                            "SELECT coalesce(json_agg(json_build_object('schema',schemaname,'table',tablename) ORDER BY schemaname,tablename),'[]') "
                                    + "FROM pg_tables WHERE schemaname !~ '^pg_' AND schemaname<>'information_schema'");
                    for (JsonNode table : tables) {
                        String schema = table.path("schema").asText();
                        String name = table.path("table").asText();
                        String qualified = identifier(schema) + "." + identifier(name);
                        Path rows = bundle.newFile("fingerprint.tmp");
                        try {
                            transfer(lab, primary, database, rows, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres",
                                    "-d", database, "-c", "COPY (SELECT encode(sha256(convert_to(row_to_json(t)::text,'UTF8')),'hex') FROM "
                                            + qualified + " t ORDER BY 1) TO STDOUT");
                            ObjectNode fingerprint = fingerprints.addObject().put("schema", schema).put("table", name);
                            fingerprint(rows, fingerprint);
                        } finally { Files.deleteIfExists(rows); }
                        if (name.startsWith("flyway_schema_history")) {
                            histories.set(schema + "." + name, jsonSql(lab, primary, database,
                                    "SELECT coalesce(json_agg(row_to_json(t) ORDER BY installed_rank),'[]') FROM " + qualified + " t"));
                        }
                    }
                    transfer(lab, primary, database, bundle.newFile(source + ".dump"), "pg_dump", "--format=custom",
                            "--no-password", "-U", "postgres", "-d", database);
                }
                ArrayNode roleArray = metadata.putArray("roles");
                roles.values().forEach(roleArray::add);
                require(drained(lab), "Source drain changed during backup capture");
                require(identities.equals(identities(lab)), "Source database identity changed during capture");
            });
        }
        return metadata;
    }

    private static Map<String, String> identities(KindLab lab) throws IOException, InterruptedException {
        Map<String, String> identities = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(lab.shards());
        names.add("ledger-db");
        for (String name : names) {
            JsonNode cluster = KindLab.parse(lab.kubectl("get", "cluster", name, "-o", "json"));
            String uid = cluster.path("metadata").path("uid").asText();
            String primary = cluster.path("status").path("currentPrimary").asText();
            require(!uid.isBlank() && !primary.isBlank(), "Source database identity is missing");
            String database = name.equals("ledger-db") ? "ledger" : "shardshop";
            String lineage = sql(lab, primary, database, "SELECT system_identifier FROM pg_control_system()");
            String oid = sql(lab, primary, database, "SELECT oid FROM pg_database WHERE datname=current_database()");
            require(lineage.matches("[0-9]+") && oid.matches("[0-9]+"), "Source database lineage is malformed");
            require("f".equals(sql(lab, primary, database, "SELECT pg_is_in_recovery()")), "Source database primary is read only");
            identities.put(name, uid + ":" + primary + ":" + lineage + ":" + oid);
        }
        return identities;
    }

    private static Map<String, JsonNode> deployments(KindLab lab) throws IOException, InterruptedException {
        Map<String, JsonNode> saved = new LinkedHashMap<>();
        for (JsonNode item : KindLab.items(KindLab.parse(lab.kubectl("get", "deployments", "-o", "json")))) {
            String name = item.path("metadata").path("name").asText();
            require(APPLICATIONS.contains(name), "Unexpected source application deployment");
            require(item.path("spec").path("replicas").isIntegralNumber(), "Missing source deployment replica count");
            saved.put(name, item.deepCopy());
        }
        require(saved.keySet().equals(APPLICATIONS), "Source application deployment inventory differs");
        return saved;
    }

    private static void stop(KindLab lab, String name, JsonNode saved) throws IOException, InterruptedException {
        lab.kubectl("patch", "deployment", name, "--type=json", "-p", replicaPatch(saved, saved, 0).toString());
        String selector = selector(saved);
        lab.await("source deployment pods stop for backup", Duration.ofSeconds(120), () ->
                KindLab.items(KindLab.parse(lab.kubectl("get", "pods", "-l", selector, "-o", "json"))).isEmpty());
    }

    private static void restore(KindLab lab, String name, JsonNode saved) throws IOException, InterruptedException {
        JsonNode current = KindLab.parse(lab.kubectl("get", "deployment", name, "-o", "json"));
        int replicas = saved.path("spec").path("replicas").asInt();
        lab.kubectl("patch", "deployment", name, "--type=json", "-p", replicaPatch(saved, current, replicas).toString());
    }

    private static void restoreAll(KindLab lab, Map<String, JsonNode> saved) throws IOException, InterruptedException {
        IOException failure = null;
        boolean interrupted = false;
        for (String name : saved.keySet()) {
            try { restore(lab, name, saved.get(name)); }
            catch (IOException | InterruptedException problem) {
                interrupted |= problem instanceof InterruptedException;
                Thread.interrupted();
                if (failure == null) failure = new IOException("Source deployment restoration needs attention");
                failure.addSuppressed(problem);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
        lab.await("source deployment replicas recover", Duration.ofSeconds(180), () -> {
            for (String name : saved.keySet()) {
                JsonNode current = KindLab.parse(lab.kubectl("get", "deployment", name, "-o", "json"));
                int count = saved.get(name).path("spec").path("replicas").asInt();
                if (current.path("status").path("readyReplicas").asInt() != count
                        || current.path("status").path("updatedReplicas").asInt() != count
                        || current.path("status").path("observedGeneration").asLong(-1)
                        < current.path("metadata").path("generation").asLong()) return false;
            }
            return true;
        });
    }

    static ArrayNode replicaPatch(JsonNode saved, JsonNode current, int replicas) throws IOException {
        require(saved.path("metadata").path("uid").isTextual()
                && saved.path("metadata").path("uid").equals(current.path("metadata").path("uid")),
                "Source deployment identity changed");
        ObjectNode before = ((ObjectNode) saved.path("spec")).deepCopy();
        ObjectNode now = ((ObjectNode) current.path("spec")).deepCopy();
        before.remove("replicas"); now.remove("replicas");
        require(before.equals(now), "Source deployment specification changed during backup");
        int observed = current.path("spec").path("replicas").asInt(-1);
        require(observed == 0 || observed == saved.path("spec").path("replicas").asInt(-2),
                "Source deployment replicas changed concurrently");
        ArrayNode patch = JSON.createArrayNode();
        patch.addObject().put("op", "test").put("path", "/metadata/uid").set("value", saved.path("metadata").path("uid"));
        patch.addObject().put("op", "test").put("path", "/spec").set("value", current.path("spec"));
        patch.addObject().put("op", "replace").put("path", "/spec/replicas").put("value", replicas);
        return patch;
    }

    private static String selector(JsonNode saved) throws IOException {
        JsonNode selector = saved.path("spec").path("selector");
        require(selector.path("matchLabels").isObject() && !selector.path("matchLabels").isEmpty()
                && selector.path("matchExpressions").isMissingNode(), "Unsupported source deployment pod selector");
        List<String> labels = new ArrayList<>();
        selector.path("matchLabels").properties().forEach(entry -> labels.add(entry.getKey() + "=" + entry.getValue().asText()));
        return String.join(",", labels);
    }

    private static void requireNoJobs(KindLab lab) throws IOException, InterruptedException {
        for (JsonNode cron : KindLab.items(KindLab.parse(lab.kubectl("get", "cronjobs", "-o", "json"))))
            require(cron.path("spec").path("suspend").asBoolean(), "Scheduled source Jobs prevent a coordinated backup");
        for (JsonNode job : KindLab.items(KindLab.parse(lab.kubectl("get", "jobs", "-o", "json")))) {
            boolean terminal = false;
            for (JsonNode condition : job.path("status").path("conditions")) {
                if ((condition.path("type").asText().equals("Complete") || condition.path("type").asText().equals("Failed"))
                        && condition.path("status").asText().equals("True")) terminal = true;
            }
            require(terminal && job.path("status").path("active").asInt() == 0,
                    "Active or pending source Jobs prevent a coordinated backup");
        }
    }

    static boolean zeroCounts(JsonNode queues) throws IOException {
        require(queues.isArray(), "Broker queue counts are malformed");
        for (JsonNode queue : queues) {
            for (String key : List.of("messages_ready", "messages_unacknowledged")) {
                require(queue.path(key).isIntegralNumber() && queue.path(key).canConvertToLong()
                        && queue.path(key).asLong() >= 0, "Broker queue count is malformed");
                if (queue.path(key).asLong() != 0) return false;
            }
        }
        return true;
    }

    private static boolean drained(KindLab lab) throws IOException, InterruptedException {
        KindSaga saga = new KindSaga(lab);
        for (String shard : lab.shards()) {
            if (saga.number(shard, "SELECT (SELECT count(*) FROM ordering.orders WHERE status NOT IN ('CONFIRMED','CANCELLED'))"
                    + "+(SELECT count(*) FROM ordering.order_sagas WHERE status NOT IN ('CONFIRMED','CANCELLED') "
                    + "OR (status='CANCELLED' AND stock_released_at IS NULL))"
                    + "+(SELECT count(*) FROM ordering.profit_intents WHERE applied_at IS NULL)"
                    + "+(SELECT count(*) FROM ordering.order_outbox WHERE published_at IS NULL)") != 0) return false;
        }
        if (saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_outbox WHERE published_at IS NULL") != 0) return false;
        return zeroCounts(KindLab.parse(lab.kubectl(Duration.ofSeconds(20), "exec", "shardshop-rabbitmq-0", "-c", "rabbitmq",
                "--", "rabbitmqctl", "--quiet", "--timeout", "10", "--formatter=json", "list_queues", "-p", "shardshop",
                "name", "messages_ready", "messages_unacknowledged")));
    }

    private static JsonNode roleMetadata(KindLab lab, String primary, String database) throws IOException, InterruptedException {
        String names = OwnedRestoreTarget.ROLES.stream().sorted().map(KindSaga::literal).collect(Collectors.joining(","));
        return jsonSql(lab, primary, database, "SELECT coalesce(json_agg(json_build_object('name',r.rolname,'login',r.rolcanlogin,"
                + "'inherit',r.rolinherit,'replication',r.rolreplication,'superuser',r.rolsuper,'createdb',r.rolcreatedb,"
                + "'createrole',r.rolcreaterole,'bypassrls',r.rolbypassrls,'connectionLimit',r.rolconnlimit,"
                + "'memberships',(SELECT coalesce(json_agg(parent.rolname ORDER BY parent.rolname),'[]') "
                + "FROM pg_auth_members m JOIN pg_roles parent ON parent.oid=m.roleid WHERE m.member=r.oid)) ORDER BY r.rolname),'[]') "
                + "FROM pg_roles r WHERE r.rolname IN (" + names + ")");
    }

    static void mergeRoles(Map<String, JsonNode> roles, JsonNode observed) throws IOException {
        require(observed.isArray(), "Source role metadata is malformed");
        for (JsonNode role : observed) {
            String name = role.path("name").asText();
            require(OwnedRestoreTarget.ROLES.contains(name), "Unexpected source database role");
            for (JsonNode membership : role.path("memberships"))
                require(OwnedRestoreTarget.ROLES.contains(membership.asText()), "Source role has an external membership");
            JsonNode prior = roles.putIfAbsent(name, role.deepCopy());
            require(prior == null || prior.equals(role), "Source role definitions differ between databases");
        }
    }

    private static String identifier(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    private static String sql(KindLab lab, String primary, String database, String statement) throws IOException, InterruptedException {
        return KindTopology.successfulSql(lab.kubectlAttempt("exec", primary, "-c", "postgres", "--", "env",
                "PGOPTIONS=-c statement_timeout=15000 -c lock_timeout=5000 -c timezone=UTC", "psql", "-X", "-qAt", "-v",
                "ON_ERROR_STOP=1", "-U", "postgres", "-d", database, "-c", statement));
    }

    private static JsonNode jsonSql(KindLab lab, String primary, String database, String statement) throws IOException, InterruptedException {
        return KindLab.parse(sql(lab, primary, database, statement));
    }

    private static void transfer(KindLab lab, String primary, String database, Path output, String... command)
            throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of("kubectl", "--context", "kind-" + lab.clusterName(), "-n", "shardshop",
                "--request-timeout=10s", "exec", primary, "-c", "postgres", "--", "env", "PGOPTIONS=-c timezone=UTC"));
        arguments.addAll(List.of(command));
        BackupProcesses.transfer(lab.processBuilder(arguments.toArray(String[]::new)), null, output, Duration.ofSeconds(120));
    }

    private static void fingerprint(Path file, ObjectNode metadata) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long lines = 0;
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1;) {
                    digest.update(buffer, 0, count);
                    for (int i = 0; i < count; i++) if (buffer[i] == '\n') lines++;
                }
            }
            metadata.put("sha256", HexFormat.of().formatHex(digest.digest())).put("rows", lines);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void brokerDefinitions(KindLab lab, BackupArtifacts bundle) throws IOException, InterruptedException {
        JsonNode secret = KindLab.parse(lab.kubectl("get", "secret", "shardshop-rabbitmq", "-o", "json"));
        String password;
        try { password = new String(Base64.getDecoder().decode(secret.path("data").path("admin-password").asText()), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("Broker management credential is unavailable"); }
        require(!password.isBlank(), "Broker management credential is unavailable");
        try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-rabbitmq", 15672);
             HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            String credentials = Base64.getEncoder().encodeToString(("shardshop-admin:" + password).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tunnel.port + "/api/definitions"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + credentials).GET().build();
            BoundedBody body = new BoundedBody();
            var pending = client.sendAsync(request, ignored -> body);
            try {
                HttpResponse<byte[]> response = pending.get(10, TimeUnit.SECONDS);
                require(response.statusCode() == 200, "Broker definition export failed");
                JsonNode definitions;
                try { definitions = JSON.readTree(response.body()); }
                catch (IOException invalid) { throw new IOException("Broker definition export is malformed"); }
                require(definitions != null && definitions.isObject(), "Broker definition export is malformed");
                bundle.writeJson("rabbitmq-definitions.json", definitions);
            } catch (ExecutionException | TimeoutException failure) {
                throw new IOException("Broker definition export failed or exceeded its bounds");
            } finally {
                body.cancel();
                pending.cancel(true);
            }
        }
    }

    /** Limit the response during receipt; the caller also bounds the complete asynchronous request. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 4 * 1024 * 1024 - bytes.size()) {
                    cancel();
                    result.completeExceptionally(new IOException("Broker definition size bound exceeded"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IOException("Broker export failed")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        void cancel() { if (subscription != null) subscription.cancel(); }
    }
}
