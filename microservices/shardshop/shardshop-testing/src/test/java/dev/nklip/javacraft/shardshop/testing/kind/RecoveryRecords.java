package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;

/** Private, checksummed original row evidence. Ledger envelopes are never used to invent missing fields. */
public final class RecoveryRecords {
    static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final List<String> TABLES=List.of("buyers","order_allocations","orders","order_items","order_sagas",
            "order_result_ids","profit_intents","order_outbox","order_inbox");
    public record Saved(Path directory,String sha256,long orderId,String shard) { }
    record Original(String shard,long orderId,ObjectNode document,String digest) {
        ArrayNode rows(String table) { return (ArrayNode)document.path("rows").path(table); }
        ObjectNode order() { return (ObjectNode)rows("orders").get(0); }
        ObjectNode saga() { return (ObjectNode)rows("order_sagas").get(0); }
    }
    private RecoveryRecords() { }

    public static Saved capture(RecoveryAccess access,Path directory,long orderId) throws Exception {
        require(orderId>0,"Recovery capture requires a positive order ID");
        String home=null;
        for(String shard:access.shards()) try(Connection connection=access.open(shard)) {
            if(!rows(connection,"orders","order_id",orderId).isEmpty()) {
                require(home==null,"Original order appears on multiple shards"); home=shard;
            }
        }
        require(home!=null,"Original complete order evidence is unavailable");
        ObjectNode document=JSON.createObjectNode().put("formatVersion",1).put("orderId",Long.toString(orderId)).put("shard",home);
        document.set("shards",JSON.valueToTree(access.shards()));
        try(Connection connection=access.open(home)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) { statement.execute("SET LOCAL TIME ZONE 'UTC'"); }
            ArrayNode orders=rows(connection,"orders","order_id",orderId);
            require(orders.size()==1,"Original order changed during capture");
            long buyer=positive(orders.get(0),"buyer_id");
            ObjectNode data=document.putObject("rows");
            ObjectNode schemas=document.putObject("schemas");
            for(String table:TABLES) {
                String key=table.equals("buyers")?"buyer_id":table.equals("order_result_ids")||table.equals("order_outbox")||table.equals("order_inbox")?"saga_id":"order_id";
                long id=key.equals("buyer_id")?buyer:orderId;
                if(key.equals("saga_id")) {
                    ArrayNode sagas=rows(connection,"order_sagas","order_id",orderId);
                    require(sagas.size()==1,"Original saga evidence is unavailable"); id=positive(sagas.get(0),"saga_id");
                }
                data.set(table,rows(connection,table,key,id));
                schemas.set(table,schema(connection,table));
            }
            connection.rollback();
        }
        validate(document);
        BackupArtifacts bundle=BackupArtifacts.create(directory);
        bundle.writeJson("original-order.json",document);
        String digest=digest(document);
        bundle.seal(JSON.createObjectNode().put("purpose","original-order-recovery").put("orderId",Long.toString(orderId)).put("recordSha256",digest));
        return new Saved(directory,digest,orderId,home);
    }

    static Original load(Path directory) throws Exception {
        BackupArtifacts bundle=BackupArtifacts.open(directory);
        JsonNode parsed=JSON.readTree(bundle.file("original-order.json").toFile());
        require(parsed instanceof ObjectNode,"Original recovery record is malformed");
        ObjectNode document=(ObjectNode)parsed;
        validate(document);
        String digest=digest(document);
        require("original-order-recovery".equals(bundle.manifest().path("purpose").asText())
                && digest.equals(bundle.manifest().path("recordSha256").asText())
                && document.path("orderId").equals(bundle.manifest().path("orderId")),"Original recovery provenance differs");
        return new Original(document.path("shard").asText(),Long.parseLong(document.path("orderId").asText()),document,digest);
    }

    static void validate(ObjectNode document) throws IOException {
        require(document.path("formatVersion").asInt()==1 && document.path("orderId").asText().matches("[1-9][0-9]{0,18}")
                && document.path("shards").isArray() && document.path("rows").isObject() && document.path("schemas").isObject(),"Original recovery record header is invalid");
        long order;
        try { order=Long.parseLong(document.path("orderId").asText()); } catch(NumberFormatException invalid) { throw new IOException("Original order ID is invalid"); }
        require(order>0,"Original order ID is invalid");
        for(String table:TABLES) {
            require(document.path("rows").path(table).isArray() && document.path("schemas").path(table).isArray()
                    && !document.path("schemas").path(table).isEmpty(),"Original recovery rowset or schema evidence is incomplete");
            var columns=new java.util.HashSet<String>();
            for(JsonNode column:document.path("schemas").path(table)) require(column.path("name").isTextual()
                    && columns.add(column.path("name").asText()),"Original schema columns are invalid");
            var identities=new java.util.HashSet<String>();
            for(JsonNode row:document.path("rows").path(table)) {
                require(row.isObject(),"Original recovery row is malformed");
                var fields=new java.util.HashSet<String>();row.fieldNames().forEachRemaining(fields::add);
                require(columns.equals(fields),"Original row omits fields from its schema evidence");
                String identity=switch(table) {
                    case "buyers" -> row.path("buyer_id").asText();
                    case "order_allocations","orders" -> row.path("order_id").asText();
                    case "order_sagas" -> row.path("saga_id").asText();
                    case "order_items","profit_intents" -> row.path("order_id").asText()+":"+row.path("item_position").asText();
                    case "order_result_ids" -> row.path("result_message_id").asText();
                    default -> row.path("message_id").asText();
                };
                require(identities.add(identity),"Original recovery rowset repeats a primary identity");
            }
        }
        for(String table:List.of("buyers","order_allocations","orders","order_sagas"))
            require(document.path("rows").path(table).size()==1,"Original core identity evidence is incomplete");
        JsonNode rows=document.path("rows"), saved=rows.path("orders").get(0), saga=rows.path("order_sagas").get(0);
        long buyer=positive(saved,"buyer_id"), sagaId=positive(saga,"saga_id");
        require(positive(saved,"order_id")==order && positive(saga,"order_id")==order
                && positive(rows.path("buyers").get(0),"buyer_id")==buyer
                && positive(rows.path("order_allocations").get(0),"buyer_id")==buyer
                && positive(rows.path("order_allocations").get(0),"order_id")==order
                && saved.path("status").equals(saga.path("status")) && !rows.path("order_items").isEmpty(),"Original core identities disagree");
        positive(saga,"command_id");
        for(String table:TABLES) for(JsonNode row:rows.path(table)) {
            if(!table.equals("buyers") && row.has("order_id")) require(positive(row,"order_id")==order,"Original row belongs to another order");
            if(row.has("saga_id")) require(positive(row,"saga_id")==sagaId,"Original row belongs to another saga");
        }
        require(rows.path("orders").get(0).hasNonNull("created_at") && saga.hasNonNull("created_at"),"Original timestamps are absent");
        for(JsonNode item:rows.path("order_items")) require(item.has("unit_cost"),"Original item cost evidence is absent");
    }

    static ArrayNode rows(Connection connection,String table,String key,long id) throws Exception {
        require(TABLES.contains(table) && List.of("buyer_id","order_id","saga_id").contains(key),"Invalid recovery relation");
        ArrayNode result=JSON.createArrayNode();
        try(PreparedStatement query=connection.prepareStatement("SELECT row_to_json(t)::text FROM ordering."+table+" t WHERE "+key+"=? ORDER BY row_to_json(t)::text")) {
            query.setQueryTimeout(15); query.setLong(1,id);
            try(ResultSet rows=query.executeQuery()) { while(rows.next()) result.add(JSON.readTree(rows.getString(1))); }
        }
        return result;
    }
    static ArrayNode schema(Connection connection,String table) throws Exception {
        ArrayNode result=JSON.createArrayNode();
        try(PreparedStatement query=connection.prepareStatement("SELECT column_name,udt_name,is_nullable FROM information_schema.columns WHERE table_schema='ordering' AND table_name=? ORDER BY ordinal_position")) {
            query.setQueryTimeout(15); query.setString(1,table);
            try(ResultSet rows=query.executeQuery()) { while(rows.next()) result.addObject().put("name",rows.getString(1)).put("type",rows.getString(2)).put("nullable",rows.getString(3)); }
        }
        return result;
    }
    static long positive(JsonNode row,String field) throws IOException {
        JsonNode value=row.path(field);
        try { long id=Long.parseLong(value.asText()); require(id>0 && value.asText().matches("[1-9][0-9]*"),"Original identity is invalid"); return id; }
        catch(NumberFormatException invalid) { throw new IOException("Original identity is invalid"); }
    }
    static JsonNode canonical(JsonNode node) {
        if(node.isObject()) {
            ObjectNode sorted=JSON.createObjectNode();
            node.properties().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry->sorted.set(entry.getKey(),canonical(entry.getValue())));
            return sorted;
        }
        if(node.isArray()) {ArrayNode array=JSON.createArrayNode();node.forEach(value->array.add(canonical(value)));return array;}
        if(node.isNumber()) return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros());
        return node.deepCopy();
    }
    static String digest(JsonNode node) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(node.toString().getBytes(StandardCharsets.UTF_8)));
    }
    static void require(boolean condition,String reason) throws IOException { if(!condition) throw new IOException(reason); }
}
