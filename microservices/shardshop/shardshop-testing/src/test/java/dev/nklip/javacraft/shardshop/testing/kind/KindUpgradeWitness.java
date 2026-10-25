package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static dev.nklip.javacraft.shardshop.testing.kind.KindDeployment.require;

/** Read-only evidence across the pre-5.3 foundation upgrade on an explicitly owned lab. */
public final class KindUpgradeWitness {
    private static final List<String> SHARD_TABLES = List.of("catalog.sellers", "catalog.products",
            "catalog.stock_reservations", "catalog.stock_reservation_guards", "catalog.profit_credits", "catalog.seller_profits",
            "ordering.buyers", "ordering.order_allocations", "ordering.orders", "ordering.order_items",
            "ordering.order_sagas", "ordering.order_result_ids", "ordering.profit_intents", "ordering.cdc_reader_state",
            "catalog.flyway_schema_history", "ordering.flyway_schema_history");
    private static final List<String> LEDGER_TABLES = List.of("ledger.ledger_operations", "ledger.ledger_entries",
            "ledger.ledger_result_ids", "ledger.flyway_schema_history");
    private final KindLab lab;
    private final KindSaga sql;

    public KindUpgradeWitness() { this(new KindLab()); }
    KindUpgradeWitness(KindLab lab) { this.lab = lab; sql = new KindSaga(lab); }

    private Path evidence() throws IOException {
        require(lab.clusterName().startsWith("shardshop-test-"), "Upgrade evidence requires an owned test cluster");
        String value = System.getProperty("shardshop.kind.upgrade.evidence", "");
        require(!value.isBlank() && Path.of(value).isAbsolute(), "Set an absolute shardshop.kind.upgrade.evidence file");
        return Path.of(value);
    }

    public void capture() throws IOException, InterruptedException {
        Path file = evidence();
        for (String shard : lab.shards()) require(sql.number(shard,
                "SELECT count(*) FROM pg_roles WHERE rolname IN ('ordering_operator','ordering_reconciler')") == 0,
                "Baseline already has the step 5.3 roles");
        ObjectNode before = observe();
        captureReplayFixture(before);
        validateBaseline(before);
        // No raw rows or credentials are persisted. Exclusive creation prevents overwriting the baseline.
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, Json.bytes(before), StandardOpenOption.WRITE);
    }

    public void retained() throws IOException, InterruptedException {
        JsonNode before = Json.parse(Files.readAllBytes(evidence()));
        validateRetained(before, observe());
        for (String shard : lab.shards()) {
            privileges(shard);
            require(sql.number(shard, KindSaga.stockViolations()) == 0, "Retained stock invariant differs on " + shard);
            require(sql.number(shard, KindSaga.profitViolations()) == 0, "Retained profit invariant differs on " + shard);
        }
    }

    /** Optional baseline fixture: a real accepted order with its result deliberately lost on the owned lab. */
    private void captureReplayFixture(ObjectNode before) throws IOException, InterruptedException {
        String order = System.getProperty("shardshop.kind.upgrade.order", "");
        if (order.isBlank()) return;
        order = KindSaga.id(order);
        String saga = KindSaga.id(System.getProperty("shardshop.kind.upgrade.saga", ""));
        String home = System.getProperty("shardshop.kind.upgrade.home", "");
        require(lab.shards().contains(home), "Upgrade replay fixture must select an inventory shard");
        require(sql.number(home, "SELECT count(*) FROM ordering.orders o JOIN ordering.order_sagas s USING(order_id) "
                + "WHERE o.order_id=" + order + " AND s.saga_id=" + saga + " AND o.status='PENDING_LEDGER' "
                + "AND s.status='PENDING_LEDGER' AND s.reconciliation_attempts=5") == 1,
                "Upgrade replay fixture must be an exhausted pending order/saga pair");
        require(sql.number(home, "SELECT count(*) FROM ordering.order_result_ids WHERE saga_id=" + saga) == 1,
                "Upgrade replay fixture needs exactly its original result binding");
        require(sql.number("ledger-db", "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + order
                + " AND outcome='RECORDED'") == 1, "Upgrade replay fixture needs its original recorded decision");
        before.putObject("replay").put("order", order).put("saga", saga).put("home", home)
                .put("decision", decisionHash(order));
    }

    public void replayed() throws IOException, InterruptedException {
        JsonNode before = Json.parse(Files.readAllBytes(evidence()));
        validateBaseline(before);
        JsonNode fixture = before.path("replay");
        String order = KindSaga.id(fixture.path("order").asText());
        String saga = KindSaga.id(fixture.path("saga").asText());
        String home = fixture.path("home").asText();
        require(lab.shards().contains(home), "Missing retained replay fixture shard");
        lab.await("operator reset replays the retained exhausted order", java.time.Duration.ofSeconds(150), () -> {
            require(sql.number(home, "SELECT count(*) FROM ordering.order_sagas WHERE saga_id=" + saga
                    + " AND order_id=" + order + " AND status='CONFIRMED' AND reconciliation_attempts=1") == 1,
                    "Reset has not completed exactly one reconciliation replay");
            sql.verifyOrder(home, order, "CONFIRMED");
            return true;
        });
        require(sql.number(home, "SELECT count(*) FROM ordering.order_result_ids WHERE saga_id=" + saga) == 2
                && sql.number("ledger-db", "SELECT count(*) FROM ledger.ledger_result_ids WHERE order_id=" + order) == 2,
                "Reset replay must reserve exactly one new command/result binding");
        require(fixture.path("decision").asText().equals(decisionHash(order)), "Reset replay changed the original ledger decision");
        sql.invariants();
    }

    private String decisionHash(String order) throws IOException, InterruptedException {
        return sql.sql("ledger-db", "SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') "
                + "FROM ledger.ledger_operations t WHERE order_id=" + order).strip();
    }

    private ObjectNode observe() throws IOException, InterruptedException {
        ObjectNode result = Json.newObject().put("cluster", lab.clusterName());
        result.set("routing", resource("configmap", "shardshop-routing").path("data"));
        ObjectNode identities = result.putObject("identities");
        for (String kind : List.of("namespace", "clusters.postgresql.cnpg.io", "databases.postgresql.cnpg.io", "pvc")) {
            String response = kind.equals("namespace") ? lab.kubectl("get", kind, "shardshop", "-o", "json")
                    : lab.kubectl("get", kind, "-o", "json");
            for (JsonNode object : KindLab.items(KindLab.parse(response))) identity(identities, kind, object);
        }
        for (String name : List.of("shardshop-snowflake-generators", "shardshop-generator-identity", "shardshop-migration-topology")) {
            JsonNode object = resource("configmap", name);
            identity(identities, "configmap", object);
            result.set(name, object.path("data"));
        }
        for (String name : List.of("catalog-migrator", "ordering-migrator", "product-app", "order-app", "ordering-cdc",
                "ledger-migrator", "ledger-app", "shardshop-rabbitmq")) {
            JsonNode object = resource("secret", name);
            identity(identities, "secret", object);
            identities.put("credential/" + name, digest(object.path("data")));
        }
        ObjectNode systems = result.putObject("systems");
        ObjectNode rows = result.putObject("rows");
        for (String cluster : lab.clusters()) {
            systems.put(cluster, sql.sql(cluster, "SELECT system_identifier::text || '|' || "
                    + "(SELECT oid::text FROM pg_database WHERE datname=current_database()) FROM pg_control_system()").strip());
            for (String table : cluster.equals("ledger-db") ? LEDGER_TABLES : SHARD_TABLES) {
                String projection = "to_jsonb(t)";
                String predicate = "TRUE";
                if (table.equals("catalog.products")) projection += " - 'stock'";
                if (table.equals("catalog.seller_profits")) projection += " - 'amount'";
                if (table.equals("ledger.ledger_result_ids")) projection += " - 'publication_attempt'";
                if (table.equals("ordering.orders")) predicate = "status IN ('CONFIRMED','CANCELLED')";
                if (table.equals("ordering.order_sagas")) predicate = "status IN ('CONFIRMED','CANCELLED') "
                        + "AND (status='CONFIRMED' OR stock_released_at IS NOT NULL)";
                if (table.equals("ordering.order_result_ids")) predicate = "received_envelope IS NOT NULL";
                if (table.equals("ordering.profit_intents")) predicate = "applied_at IS NOT NULL";
                if (table.endsWith("flyway_schema_history")) predicate = "version IS NOT NULL";
                var hashes = rows.putArray(cluster + "/" + table);
                sql.sql(cluster, "SELECT encode(sha256(convert_to((" + projection + ")::text,'UTF8')),'hex') FROM "
                        + table + " t WHERE " + predicate).lines().filter(line -> !line.isBlank()).forEach(hashes::add);
            }
        }
        return result;
    }

    private JsonNode resource(String kind, String name) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("get", kind, name, "-o", "json"));
    }

    private static void identity(ObjectNode identities, String kind, JsonNode object) throws IOException {
        String name = object.path("metadata").path("name").asText();
        String uid = object.path("metadata").path("uid").asText();
        require(!name.isBlank() && !uid.isBlank(), "Missing retained resource identity");
        identities.put(kind + "/" + name, uid);
    }

    private static String digest(JsonNode value) {
        // JSON object field order is immaterial to a Kubernetes Secret.
        Map<String, String> sorted = new java.util.TreeMap<>();
        value.properties().forEach(field -> sorted.put(field.getKey(), field.getValue().asText()));
        ObjectNode canonical = Json.newObject();
        sorted.forEach(canonical::put);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.bytes(canonical))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void validateBaseline(JsonNode before) throws IOException {
        require(before.path("cluster").asText().startsWith("shardshop-test-")
                && before.path("identities").isObject() && !before.path("identities").isEmpty()
                && before.path("systems").isObject() && !before.path("systems").isEmpty(), "Incomplete upgrade baseline");
        boolean orders = false;
        boolean decisions = false;
        for (var field : before.path("rows").properties()) {
            require(field.getValue().isArray(), "Malformed upgrade row witness");
            orders |= field.getKey().endsWith("/ordering.orders") && !field.getValue().isEmpty();
            decisions |= field.getKey().endsWith("/ledger.ledger_operations") && !field.getValue().isEmpty();
        }
        require(orders && decisions, "Upgrade baseline must retain terminal orders and permanent ledger decisions");
        highWater(before);
    }

    static void validateRetained(JsonNode before, JsonNode after) throws IOException {
        validateBaseline(before);
        for (String key : List.of("cluster", "routing", "systems", "shardshop-generator-identity", "shardshop-migration-topology"))
            require(before.has(key) && before.path(key).equals(after.path(key)), "Upgrade changed " + key);
        for (var field : before.path("identities").properties())
            require(field.getValue().equals(after.path("identities").path(field.getKey())), "Upgrade changed " + field.getKey());
        require(highWater(after) >= highWater(before), "Upgrade regressed the allocator high-water mark");
        for (var field : before.path("rows").properties()) {
            Map<String, Integer> retained = counts(after.path("rows").path(field.getKey()));
            for (var row : counts(field.getValue()).entrySet())
                require(retained.getOrDefault(row.getKey(), 0) >= row.getValue(), "Upgrade changed or removed retained rows in " + field.getKey());
        }
    }

    private static int highWater(JsonNode witness) throws IOException {
        String value = witness.path("shardshop-snowflake-generators").path("highWaterMark").asText();
        require(value.matches("0|[1-9][0-9]{0,3}"), "Malformed allocator witness");
        int parsed = Integer.parseInt(value);
        require(parsed <= 1023, "Allocator witness exceeds its range");
        return parsed;
    }

    private static Map<String, Integer> counts(JsonNode rows) throws IOException {
        require(rows.isArray(), "Missing upgrade row witness");
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode row : rows) {
            require(row.isTextual() && row.asText().matches("[0-9a-f]{64}"), "Malformed retained row hash");
            counts.merge(row.asText(), 1, Integer::sum);
        }
        return counts;
    }

    private void privileges(String shard) throws IOException, InterruptedException {
        JsonNode actual = KindLab.parse(sql.sql(shard, "SELECT coalesce(jsonb_object_agg(k,v),'{}'::jsonb)::text FROM ("
                + "SELECT n.nspname||'.'||c.relname||'.'||p.priv k,jsonb_agg(a.attname ORDER BY a.attname) v "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid "
                + "AND a.attnum>0 AND NOT a.attisdropped CROSS JOIN (VALUES ('SELECT'),('INSERT'),('UPDATE'),('REFERENCES')) p(priv) "
                + "WHERE n.nspname IN ('catalog','ordering') AND c.relkind IN ('r','v') "
                + "AND has_column_privilege('ordering_operator',c.oid,a.attnum,p.priv) GROUP BY 1) q"));
        require(actual.equals(KindLab.parse("""
                {"ordering.orders.SELECT":["order_id","status"],
                 "ordering.order_sagas.SELECT":["next_reconciliation_at","order_id","reconciliation_attempts","saga_id","status"],
                 "ordering.order_sagas.UPDATE":["next_reconciliation_at","reconciliation_attempts"]}
                """)), "Operator effective column privileges differ on " + shard);
        require(sql.sql(shard, "SELECT (SELECT rolcanlogin AND rolinherit AND NOT rolsuper AND NOT rolcreatedb "
                + "AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname='ordering_operator') "
                + "AND (SELECT NOT rolcanlogin FROM pg_roles WHERE rolname='ordering_reconciler') "
                + "AND pg_has_role('ordering_operator','ordering_reconciler','MEMBER') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('catalog_owner','ordering_owner',"
                + "'catalog_reader','catalog_writer','catalog_reserver','ordering_writer','ordering_cdc_reader') "
                + "AND pg_has_role('ordering_operator',oid,'MEMBER')) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN ('product_app','order_app','ordering_cdc') "
                + "AND pg_has_role(oid,'ordering_reconciler','MEMBER')) "
                + "AND NOT has_schema_privilege('ordering_operator','ordering','CREATE') "
                + "AND NOT has_schema_privilege('ordering_operator','catalog','USAGE') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname IN ('catalog','ordering') AND c.relkind IN ('r','v') AND ("
                + "has_table_privilege('ordering_operator',c.oid,'DELETE') OR has_table_privilege('ordering_operator',c.oid,'TRUNCATE') "
                + "OR has_table_privilege('ordering_operator',c.oid,'TRIGGER')))").strip().equals("t"),
                "Operator role attributes, memberships or DDL privileges differ on " + shard);
    }
}
