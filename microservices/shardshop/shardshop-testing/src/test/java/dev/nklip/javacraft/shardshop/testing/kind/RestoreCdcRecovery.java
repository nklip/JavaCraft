package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Target-only, explicitly qualified resnapshot; no original database, broker or allocator is reachable. */
public final class RestoreCdcRecovery {
    private static final Duration WAIT=Duration.ofSeconds(120);
    private RestoreCdcRecovery() { }
    public record Report(Map<String,String> originalStateDigests, Map<String,Long> streamingCheckpoints,
                         String replayIdentityDigest, long ledgerEntries) { }
    record Witness(String shard,long message,long result,long saga,long order,String envelope) { }

    /** Checks fixture inputs before creating a database target or starting any container. */
    public static void preflight() throws IOException {
        String configured = System.getProperty("shardshop.testing.generator-allocation");
        if (configured == null || configured.isBlank()) throw new IOException("Set shardshop.testing.generator-allocation to the rendered chart fixture");
        preflight(Path.of(configured),
                ShardShopFiles.resolve("shardshop-order/target/quarkus-app/quarkus-run.jar"),
                ShardShopFiles.resolve("shardshop-ledger/target/quarkus-app/quarkus-run.jar"));
    }

    static void preflight(Path allocation, Path orderJar, Path ledgerJar) throws IOException {
        Path broker = allocation.resolveSibling("rabbitmq.yaml");
        String render = "Render only the fixture from the repository root: helm template shardshop "
                + "microservices/shardshop/infra/helm/shardshop --namespace shardshop "
                + "--values microservices/shardshop/infra/helm/shardshop/kind-values.yaml "
                + "--values microservices/shardshop/infra/shards.yaml --set-string render=infrastructure "
                + "--output-dir microservices/shardshop/shardshop-testing/target/chart";
        if (!Files.isRegularFile(allocation) || !Files.isReadable(allocation)
                || !Files.isRegularFile(broker) || !Files.isReadable(broker) || Files.size(broker) > 4 * 1024 * 1024) {
            throw new IOException("Restore CDC fixture is missing or unreadable. " + render);
        }
        boolean statefulSet = false;
        try {
            for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(Files.readString(broker))) {
                if (document instanceof Map<?, ?> resource && "StatefulSet".equals(resource.get("kind"))) {
                    Object spec = resource.get("spec");
                    Object template = spec instanceof Map<?, ?> map ? map.get("template") : null;
                    Object pod = template instanceof Map<?, ?> map ? map.get("spec") : null;
                    Object containers = pod instanceof Map<?, ?> map ? map.get("containers") : null;
                    Object first = containers instanceof List<?> list && !list.isEmpty() ? list.getFirst() : null;
                    if (!(first instanceof Map<?, ?> container) || !(container.get("command") instanceof List<?> command)
                            || command.isEmpty() || !(container.get("env") instanceof List<?>)) {
                        throw new IllegalArgumentException("Incomplete broker container fixture");
                    }
                    Object readiness = container.get("readinessProbe");
                    Object exec = readiness instanceof Map<?, ?> map ? map.get("exec") : null;
                    if (!(exec instanceof Map<?, ?> map) || !(map.get("command") instanceof List<?> probe) || probe.isEmpty()) {
                        throw new IllegalArgumentException("Incomplete broker readiness fixture");
                    }
                    statefulSet = true;
                }
            }
        } catch (RuntimeException invalid) { throw new IOException("Restore RabbitMQ fixture is malformed. " + render); }
        if (!statefulSet) throw new IOException("Restore fixture has no RabbitMQ StatefulSet. " + render);
        for (Path jar : List.of(orderJar, ledgerJar)) {
            if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) {
                throw new IOException("Restore requires the existing packaged order and ledger artifacts; missing " + jar);
            }
        }
    }

    public static Report verify(OwnedRestoreTarget target,List<String> shards,Path privateEvidence) throws Exception {
        OwnedRestoreTarget.privateDirectory(privateEvidence);
        RestoreVerification.verify(target,shards);
        Path work=Files.createTempDirectory(privateEvidence,"cdc-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Map<String,String> originals=new LinkedHashMap<>();
        for(String shard:shards) try(Connection connection=target.connection(shard)) {
            validateResetState(RestoreVerification.count(connection,"SELECT count(*) FROM ordering.debezium_offset_storage"),
                    RestoreVerification.count(connection,"SELECT count(*) FROM ordering.cdc_reader_state"),
                    RestoreVerification.count(connection,"SELECT count(*) FROM pg_replication_slots WHERE database=current_database()"));
            originals.put(shard,stateDigest(connection));
        }
        Witness witness=witness(target,shards);
        long entries;
        try(Connection ledger=target.connection("ledger-db")) { entries=RestoreVerification.count(ledger,"SELECT count(*) FROM ledger.ledger_entries"); }
        RabbitMqBroker broker=new RabbitMqBroker();
        PackagedApplication order=null,ledger=null;
        try {
            broker.start();
            Properties orderConfig=orderProperties(target,shards,broker.port(),broker.orderPassword());
            Path orderFile=write(work,"order.properties",orderConfig);
            order=startOrder(work,orderFile);
            PackagedApplication blocked=order;
            await("restored CDC rejects missing logical slots",()-> {
                alive(blocked);
                return shards.stream().allMatch(shard->blocked.lines().stream().anyMatch(line->line.contains(
                        "CDC recovery required for "+shard+": durable offset exists without its replication slot")));
            });
            order.stop(); order=null;
            // Recheck the complete relationship contract immediately before any reset.
            RestoreVerification.verify(target,shards);
            for(String shard:shards) try(Connection connection=target.connection(shard)) {
                RestoreVerification.require(originals.get(shard).equals(stateDigest(connection)),"CDC refusal changed restored recovery state");
                connection.setAutoCommit(false);
                try(Statement statement=connection.createStatement()) {
                    statement.setQueryTimeout(10);
                    statement.execute("LOCK TABLE ordering.debezium_offset_storage, ordering.cdc_reader_state IN ACCESS EXCLUSIVE MODE");
                    validateResetState(RestoreVerification.count(connection,"SELECT count(*) FROM ordering.debezium_offset_storage"),
                            RestoreVerification.count(connection,"SELECT count(*) FROM ordering.cdc_reader_state"),
                            RestoreVerification.count(connection,"SELECT count(*) FROM pg_replication_slots WHERE database=current_database()"));
                    statement.execute("TRUNCATE ordering.debezium_offset_storage, ordering.cdc_reader_state");
                    connection.commit();
                } catch(Exception failed) { connection.rollback(); throw failed; }
            }
            long attempt=attempt(target,witness);
            arm(target,witness);
            Path ledgerFile=write(work,"ledger.properties",ledgerProperties(target,broker));
            ledger=PackagedApplication.start("shardshop-ledger",work,List.of("-Dquarkus.config.locations="+ledgerFile.toUri()));
            order=startOrder(work,orderFile);
            PackagedApplication runningOrder=order,runningLedger=ledger;
            await("fresh snapshot republishes original retained command",()-> {
                alive(runningOrder); alive(runningLedger);
                return published(target,witness) && attempt(target,witness)>attempt;
            });
            Map<String,Long> first=checkpoints(target,shards,runningOrder,runningLedger);
            long streamedAttempt=attempt(target,witness);
            long barrier=wal(target,witness.shard());
            reinsert(target,witness);
            await("stream delivers the same original command after the snapshot",()-> {
                alive(runningOrder); alive(runningLedger);
                return published(target,witness) && attempt(target,witness)>streamedAttempt;
            });
            await("streaming checkpoint crosses replay insert",()-> {
                alive(runningOrder); alive(runningLedger);
                return checkpoint(target,witness.shard())>barrier;
            });
            Map<String,Long> finalPositions=checkpoints(target,shards,runningOrder,runningLedger);
            for(String shard:shards) RestoreVerification.require(finalPositions.get(shard)>=first.get(shard),"Recovered checkpoint moved backwards");
            await("replayed ledger results are durably confirmed",()-> {
                alive(runningOrder); alive(runningLedger);
                try(Connection connection=target.connection("ledger-db")) {
                    return RestoreVerification.count(connection,"SELECT count(*) FROM ledger.ledger_outbox WHERE published_at IS NULL")==0
                            && broker.queue("ledger.record-order").path("messages").asLong(-1)==0;
                }
            });
            order.stop(); order=null;
            ledger.stop(); ledger=null;
            try(Connection connection=target.connection("ledger-db")) {
                RestoreVerification.require(entries==RestoreVerification.count(connection,"SELECT count(*) FROM ledger.ledger_entries"),
                        "Replaying original commands changed ledger entry cardinality");
            }
            RestoreVerification.verify(target,shards);
            Report report=new Report(Map.copyOf(originals),Map.copyOf(finalPositions),digest(witness.message()+":"+witness.result()+":"+witness.order()),entries);
            Path reportFile=work.resolve("recovery-report.json");
            Files.writeString(reportFile,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(reportFile,PosixFilePermissions.fromString("rw-------"));
            return report;
        } finally {
            try { if(order!=null && order.isAlive()) order.stop(); }
            finally { try { if(ledger!=null && ledger.isAlive()) ledger.stop(); } finally { broker.dispose(); } }
        }
    }

    static void validateResetState(long offsets,long history,long slots) throws IOException {
        RestoreVerification.require(offsets>0 && history==1 && slots==0,
                "Explicit restore recovery requires original offsets/history and no target logical slot");
    }

    static Properties orderProperties(OwnedRestoreTarget target,List<String> shards,int port,String brokerPassword) {
        Properties config=new Properties();
        config.setProperty("quarkus.http.host","127.0.0.1"); config.setProperty("quarkus.http.port","0");
        config.setProperty("shardshop.routing.shards",String.join(",",shards));
        config.setProperty("shardshop.routing.regions","US,EU,ASIA");
        config.setProperty("shardshop.order.process","saga");
        // Required at startup, but every path capable of emitting an ID is disabled.
        config.setProperty("shardshop.id.generator-id","1");
        config.setProperty("shardshop.order.stock.enabled","false");
        config.setProperty("shardshop.order.profit.enabled","false");
        config.setProperty("shardshop.order.reconciliation.enabled","false");
        config.setProperty("shardshop.order.results.enabled","false");
        config.setProperty("shardshop.order.cleanup.enabled","false");
        config.setProperty("shardshop.order.cdc.enabled","true");
        config.setProperty("shardshop.order.max-pool-size","2");
        config.setProperty("shardshop.order.cdc.broker.host","127.0.0.1");
        config.setProperty("shardshop.order.cdc.broker.port",Integer.toString(port));
        config.setProperty("shardshop.order.cdc.broker.virtual-host","shardshop");
        config.setProperty("shardshop.order.cdc.broker.username","shardshop-order");
        config.setProperty("shardshop.order.cdc.broker.password",brokerPassword);
        for(String shard:shards) {
            String prefix="shardshop.order.connections."+shard+".";
            config.setProperty(prefix+"primary-url",target.jdbcUrl(shard));
            config.setProperty(prefix+"root-certificate",target.rootCertificate().toString());
            config.setProperty(prefix+"username","order_app"); config.setProperty(prefix+"password",target.rolePassword("order_app"));
            config.setProperty("shardshop.order.cdc.connections."+shard+".username","ordering_cdc");
            config.setProperty("shardshop.order.cdc.connections."+shard+".password",target.rolePassword("ordering_cdc"));
        }
        return config;
    }

    static Properties ledgerProperties(OwnedRestoreTarget target,RabbitMqBroker broker) {
        Properties config=new Properties();
        config.setProperty("quarkus.http.host","127.0.0.1"); config.setProperty("quarkus.http.port","0");
        config.setProperty("shardshop.ledger.enabled","true");
        config.setProperty("shardshop.ledger.database.url",target.jdbcUrl("ledger-db"));
        config.setProperty("shardshop.ledger.database.root-certificate",target.rootCertificate().toString());
        config.setProperty("shardshop.ledger.database.username","ledger_app");
        config.setProperty("shardshop.ledger.database.password",target.rolePassword("ledger_app"));
        config.setProperty("shardshop.ledger.allowed-currencies",String.join(",",CurrencyPolicy.SUPPORTED_CURRENCIES));
        config.setProperty("shardshop.ledger.publisher.enabled","true"); config.setProperty("shardshop.ledger.cleanup.enabled","false");
        config.setProperty("shardshop.ledger.broker.host","127.0.0.1"); config.setProperty("shardshop.ledger.broker.port",Integer.toString(broker.port()));
        config.setProperty("shardshop.ledger.broker.virtual-host","shardshop");
        config.setProperty("shardshop.ledger.broker.username","shardshop-ledger"); config.setProperty("shardshop.ledger.broker.password",broker.ledgerPassword());
        return config;
    }

    private static PackagedApplication startOrder(Path directory,Path configuration) throws IOException {
        return PackagedApplication.start("shardshop-order",directory,List.of("-Dshardshop.routing.config="+configuration.toUri()));
    }
    private static Path write(Path work,String name,Properties config) throws IOException {
        Path path=Files.createFile(work.resolve(name),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(OutputStream output=Files.newOutputStream(path)) { config.store(output,"Isolated restore target credentials"); }
        return path;
    }

    private static Witness witness(OwnedRestoreTarget target,List<String> shards) throws Exception {
        try(Connection ledger=target.connection("ledger-db")) {
            for(String shard:shards) try(Connection connection=target.connection(shard); Statement query=connection.createStatement();
                    ResultSet rows=query.executeQuery("SELECT r.message_id,r.result_message_id,r.saga_id,s.order_id FROM ordering.order_result_ids r "
                            + "JOIN ordering.order_sagas s USING(saga_id) ORDER BY r.message_id")) {
                while(rows.next()) try(PreparedStatement match=ledger.prepareStatement("SELECT command_envelope::text FROM ledger.ledger_result_ids WHERE message_id=? AND result_message_id=?")) {
                    match.setLong(1,rows.getLong(1)); match.setLong(2,rows.getLong(2));
                    try(ResultSet saved=match.executeQuery()) {
                        if(saved.next()) return new Witness(shard,rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),saved.getString(1));
                    }
                }
            }
        }
        throw new IOException("CDC recovery qualification requires an original reserved command with permanent ledger evidence");
    }
    private static void arm(OwnedRestoreTarget target,Witness witness) throws Exception {
        try(Connection connection=target.connection(witness.shard()); PreparedStatement statement=connection.prepareStatement(
                "INSERT INTO ordering.order_outbox(message_id,result_message_id,saga_id,envelope) VALUES(?,?,?,?::jsonb) "
                        + "ON CONFLICT(message_id) DO UPDATE SET published_at=NULL")) {
            statement.setLong(1,witness.message()); statement.setLong(2,witness.result()); statement.setLong(3,witness.saga()); statement.setString(4,witness.envelope());
            statement.executeUpdate();
        }
    }
    private static void reinsert(OwnedRestoreTarget target,Witness witness) throws Exception {
        try(Connection connection=target.connection(witness.shard())) {
            connection.setAutoCommit(false);
            try(PreparedStatement statement=connection.prepareStatement("DELETE FROM ordering.order_outbox WHERE message_id=?")) {
                statement.setLong(1,witness.message()); RestoreVerification.require(statement.executeUpdate()==1,"Replay witness disappeared");
            }
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO ordering.order_outbox(message_id,result_message_id,saga_id,envelope) VALUES(?,?,?,?::jsonb)")) {
                statement.setLong(1,witness.message()); statement.setLong(2,witness.result()); statement.setLong(3,witness.saga()); statement.setString(4,witness.envelope()); statement.executeUpdate();
            }
            connection.commit();
        }
    }
    private static boolean published(OwnedRestoreTarget target,Witness witness) throws Exception {
        try(Connection connection=target.connection(witness.shard()); PreparedStatement query=connection.prepareStatement("SELECT published_at IS NOT NULL FROM ordering.order_outbox WHERE message_id=?")) {
            query.setLong(1,witness.message()); try(ResultSet rows=query.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }
    private static long attempt(OwnedRestoreTarget target,Witness witness) throws Exception {
        try(Connection connection=target.connection("ledger-db"); PreparedStatement query=connection.prepareStatement("SELECT publication_attempt FROM ledger.ledger_result_ids WHERE message_id=? AND result_message_id=?")) {
            query.setLong(1,witness.message()); query.setLong(2,witness.result());
            try(ResultSet rows=query.executeQuery()) { RestoreVerification.require(rows.next(),"Permanent replay identity disappeared"); return rows.getLong(1); }
        }
    }
    private static long wal(OwnedRestoreTarget target,String shard) throws Exception {
        try(Connection connection=target.connection(shard)) { return RestoreVerification.count(connection,"SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),'0/0')::bigint"); }
    }
    private static Map<String,Long> checkpoints(OwnedRestoreTarget target,List<String> shards,PackagedApplication order,PackagedApplication ledger) throws Exception {
        Map<String,Long> positions=new LinkedHashMap<>();
        await("fresh streaming checkpoint on every restored shard",()-> {
            alive(order); alive(ledger); positions.clear();
            for(String shard:shards) { long lsn=checkpoint(target,shard); if(lsn<=0) return false; positions.put(shard,lsn); }
            return true;
        });
        return Map.copyOf(positions);
    }
    private static long checkpoint(OwnedRestoreTarget target,String shard) throws Exception {
        try(Connection connection=target.connection(shard); Statement query=connection.createStatement();
                ResultSet rows=query.executeQuery("SELECT offset_key,offset_val FROM ordering.debezium_offset_storage")) {
            long position=0;
            String identity="shardshop_order_"+shard.replace('-','_');
            while(rows.next()) {
                JsonNode key=RestoreVerification.parse(rows.getString(1));
                RestoreVerification.require(key!=null && key.isArray() && key.size()==2 && identity.equals(key.path(0).asText())
                        && key.path(1).isObject() && key.path(1).size()==1 && identity.equals(key.path(1).path("server").asText()),
                        "Recovered offset belongs to another connector");
                position=Math.max(position,streamingPosition(RestoreVerification.parse(rows.getString(2))));
            }
            if(position>0) {
                RestoreVerification.require(RestoreVerification.count(connection,"SELECT count(*) FROM ordering.cdc_reader_state")==1,
                        "Recovered CDC checkpoint lacks reader history");
                try(PreparedStatement slot=connection.prepareStatement("SELECT count(*) FROM pg_replication_slots WHERE slot_name=? "
                        + "AND database=current_database() AND plugin='pgoutput' AND slot_type='logical' AND failover AND active "
                        + "AND invalidation_reason IS NULL")) {
                    slot.setString(1,identity);
                    try(ResultSet found=slot.executeQuery()) { found.next(); RestoreVerification.require(found.getLong(1)==1,
                            "Recovered checkpoint lacks its active failover slot"); }
                }
            }
            return position;
        }
    }
    static long streamingPosition(JsonNode offset) {
        if(offset==null || (offset.has("snapshot") && !offset.path("snapshot").asText().equals("false"))
                || (offset.has("snapshot_completed") && (!offset.path("snapshot_completed").isBoolean()
                || !offset.path("snapshot_completed").booleanValue()))) return 0;
        JsonNode position=offset.hasNonNull("lsn_commit")?offset.path("lsn_commit"):offset.path("lsn");
        return position.isIntegralNumber() && position.canConvertToLong() && position.longValue()>0?position.longValue():0;
    }
    private static String stateDigest(Connection connection) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String sql:List.of("SELECT id,offset_key,offset_val,record_insert_ts,record_insert_seq FROM ordering.debezium_offset_storage ORDER BY id",
                "SELECT reader_name,initialized_at FROM ordering.cdc_reader_state ORDER BY reader_name")) {
            try(Statement query=connection.createStatement();ResultSet rows=query.executeQuery(sql)) {
                while(rows.next()) for(int column=1;column<=rows.getMetaData().getColumnCount();column++) {
                    String value=rows.getString(column); byte[] bytes=(value==null?"<null>":value).getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void alive(PackagedApplication application) throws IOException {
        RestoreVerification.require(application.isAlive(),"Isolated packaged worker exited; inspect private recovery log");
    }
    @FunctionalInterface private interface Condition { boolean satisfied() throws Exception; }
    private static void await(String reason,Condition condition) throws Exception {
        long deadline=System.nanoTime()+WAIT.toNanos();
        while(!condition.satisfied()) {
            if(System.nanoTime()>=deadline) throw new IOException("Timed out: "+reason);
            Thread.sleep(100);
        }
    }
}
