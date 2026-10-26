package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Selected original-history recovery. Ledger/catalog data remain authoritative and are never overwritten. */
public final class RecoveryRepair {
    private RecoveryRepair() { }
    public record Report(long orderId,String shard,long restoredRows,long resolvedIncidents,String status) { }
    private record Incident(String table,long message,String fingerprint,String reason) { }
    private record Ledger(ObjectNode decision,ArrayNode bindings,ObjectNode result) { }

    public static Report repair(RecoveryAccess access,Path recordDirectory,long orderId) throws Exception {
        RecoveryRecords.Original original=RecoveryRecords.load(recordDirectory);
        RecoveryRecords.require(orderId==original.orderId() && original.document().path("shards").equals(RecoveryRecords.JSON.valueToTree(access.shards()))
                && access.shards().contains(original.shard()),"Recovery selection or routing differs from original evidence");
        long buyer=RecoveryRecords.positive(original.order(),"buyer_id");
        RecoveryRecords.require(access.shards().get(CatalogRouting.sellerShard(Long.toString(buyer),access.shards().size())).equals(original.shard()),
                "Original buyer routing differs from recovery target");
        validateProfitEvidence(original);
        Ledger ledger=ledger(access,original);
        validateIdentity(original,ledger.decision());
        validateBindings(original,ledger);
        boolean pending="PENDING_LEDGER".equals(original.order().path("status").asText());
        String outcome=ledger.decision().path("outcome").asText();
        String status=outcome.equals("RECORDED")?"CONFIRMED":"CANCELLED";
        RecoveryRecords.require(pending || status.equals(original.order().path("status").asText()),"Original order outcome conflicts with permanent ledger decision");
        if(pending) {
            RecoveryRecords.require(original.saga().path("ledger_result").isNull() && original.rows("profit_intents").isEmpty() && original.rows("order_inbox").isEmpty(),
                    "Original pending rowset contains an accepted outcome");
            for(JsonNode binding:original.rows("order_result_ids")) RecoveryRecords.require(binding.path("received_envelope").isNull(),
                    "Original pending binding already received an outcome");
            if(outcome.equals("RECORDED")) for(JsonNode item:original.rows("order_items"))
                RecoveryRecords.require(item.hasNonNull("unit_cost"),"Original acceptance-time cost is unknown");
        } else {
            RecoveryRecords.require(original.saga().path("ledger_result").equals(ledger.result()),"Original accepted envelope differs from permanent ledger evidence");
            if(status.equals("CANCELLED")) RecoveryRecords.require(original.saga().hasNonNull("stock_released_at"),"Original cancellation has unfinished stock release");
        }
        verifyCatalog(access,original,status);
        verifyForeignIdentities(access,original);
        try(Connection target=access.open(original.shard())) {
            target.setAutoCommit(false);
            try {
                try(Statement settings=target.createStatement()) {
                    settings.execute("SET LOCAL statement_timeout='15s'"); settings.execute("SET LOCAL lock_timeout='5s'");
                    settings.execute("SET LOCAL idle_in_transaction_session_timeout='30s'"); settings.execute("SET LOCAL TIME ZONE 'UTC'");
                }
                try(PreparedStatement lock=target.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1,CatalogRouting.creationLock("order","",Long.toString(orderId))); lock.execute();
                }
                Map<String,Boolean> absent=new HashMap<>();
                for(String table:RecoveryRecords.TABLES) {
                    RecoveryRecords.require(RecoveryRecords.schema(target,table).equals(original.document().path("schemas").path(table)),
                            "Recovery target schema differs from original row evidence");
                    String key=table.equals("buyers")?"buyer_id":List.of("order_result_ids","order_outbox","order_inbox").contains(table)?"saga_id":"order_id";
                    long id=key.equals("buyer_id")?buyer:key.equals("saga_id")?RecoveryRecords.positive(original.saga(),"saga_id"):orderId;
                    ArrayNode current=RecoveryRecords.rows(target,table,key,id);
                    validateTargetRows(original.rows(table),current);
                    absent.put(table,current.isEmpty());
                }
                List<Incident> incidents=incidents(target,original,ledger);
                RecoveryRecords.require(!incidents.isEmpty(),"No matching unresolved recovery incident exists");
                // Every schema, row, foreign identity, incident and remote business effect is checked before writes.
                long inserted=0;
                for(String table:RecoveryRecords.TABLES) if(absent.get(table)) {
                    for(JsonNode row:original.rows(table)) {
                        try(PreparedStatement insert=target.prepareStatement("INSERT INTO ordering."+table+" SELECT * FROM jsonb_populate_record(NULL::ordering."+table+",?::jsonb)")) {
                            insert.setString(1,row.toString()); RecoveryRecords.require(insert.executeUpdate()==1,"Original row insertion was not singular"); inserted++;
                        }
                    }
                }
                if(pending) applyAuthenticResult(target,original,ledger.result(),status);
                // Recheck remote effects immediately before resolving; no remote state is changed here.
                verifyCatalog(access,original,status);
                long resolved=0;
                for(Incident incident:incidents) try(PreparedStatement resolve=target.prepareStatement("UPDATE ordering."+incident.table()
                        +" SET resolved_at=GREATEST(clock_timestamp(),first_seen_at),resolution=? WHERE message_id=? AND payload_fingerprint=? AND reason=? AND resolved_at IS NULL")) {
                    resolve.setString(1,"Verified original record "+original.digest()+"; permanent outcome "+outcome+"; original reason retained");
                    resolve.setLong(2,incident.message()); resolve.setString(3,incident.fingerprint()); resolve.setString(4,incident.reason());
                    RecoveryRecords.require(resolve.executeUpdate()==1,"Recovery incident changed concurrently"); resolved++;
                }
                target.commit();
                return new Report(orderId,original.shard(),inserted,resolved,status);
            } catch(java.sql.SQLException failed) {
                try { target.rollback(); } catch(java.sql.SQLException rollback) {
                    throw new IOException("Original-history recovery SQL failed and rollback needs operator attention");
                }
                // PostgreSQL constraint diagnostics may include buyer PII or complete row values.
                throw new IOException("Original-history recovery SQL failed; transaction rolled back and incident retained");
            } catch(Exception | Error failed) { target.rollback(); throw failed; }
        }
    }

    static void validateTargetRows(ArrayNode original,ArrayNode current) throws IOException {
        RecoveryRecords.require(current.isEmpty() || current.equals(original),"Recovery would overwrite conflicting existing history");
    }

    static void validateIdentity(RecoveryRecords.Original original,JsonNode decision) throws IOException {
        JsonNode order=original.order(),saga=original.saga();
        for(String name:List.of("order_id","buyer_id","request_fingerprint","currency"))
            RecoveryRecords.require(order.path(name).equals(decision.path(name)),"Original order identity differs from permanent ledger decision");
        for(String name:List.of("saga_id","command_id")) RecoveryRecords.require(saga.path(name).equals(decision.path(name)),
                "Original saga identity differs from permanent ledger decision");
        RecoveryRecords.require(decimal(order,"total_amount").compareTo(decimal(decision,"amount"))==0,"Original order total differs from ledger");
        JsonNode snapshot=decision.path("snapshot"),items=snapshot.path("items");
        RecoveryRecords.require(snapshot.path("currency").asText().equals(order.path("currency").asText())
                && decimal(snapshot,"amount").compareTo(decimal(order,"total_amount"))==0,"Original ledger snapshot total differs");
        RecoveryRecords.require(items.isArray() && items.size()==original.rows("order_items").size(),"Original immutable item evidence differs from ledger");
        for(JsonNode item:original.rows("order_items")) {
            JsonNode match=null;
            for(JsonNode candidate:items) if(candidate.path("itemPosition").asInt(-1)==item.path("item_position").asInt(-2)) match=candidate;
            RecoveryRecords.require(match!=null,"Original item position differs from ledger");
            for(String[] field:List.of(new String[]{"seller_id","sellerId"},new String[]{"product_id","productId"},new String[]{"quantity","quantity"},
                    new String[]{"product_name","productName"},new String[]{"currency","currency"}))
                RecoveryRecords.require(item.path(field[0]).asText().equals(match.path(field[1]).asText()),"Original immutable item differs from ledger");
            RecoveryRecords.require(decimal(item,"unit_price").compareTo(decimal(match,"unitPrice"))==0,"Original item price differs from ledger");
        }
    }

    static void validateProfitEvidence(RecoveryRecords.Original original) throws IOException {
        boolean confirmed=original.order().path("status").asText().equals("CONFIRMED");
        RecoveryRecords.require(original.rows("profit_intents").size()==(confirmed?original.rows("order_items").size():0),
                "Original profit intent rowset is incomplete or conflicts with order state");
        for(JsonNode intent:original.rows("profit_intents")) {
            JsonNode item=null;
            for(JsonNode candidate:original.rows("order_items")) if(candidate.path("item_position").equals(intent.path("item_position"))) item=candidate;
            RecoveryRecords.require(item!=null && item.hasNonNull("unit_cost") && intent.hasNonNull("applied_at") && intent.path("blocked_reason").isNull(),
                    "Original terminal profit evidence is unfinished");
            for(String field:List.of("seller_id","product_id","currency")) RecoveryRecords.require(intent.path(field).equals(item.path(field)),"Original profit intent identity differs from item");
            RecoveryRecords.require(decimal(intent,"amount").compareTo(decimal(item,"unit_price").subtract(decimal(item,"unit_cost"))
                    .multiply(BigDecimal.valueOf(item.path("quantity").asInt())))==0,"Original profit intent differs from acceptance-time cost");
        }
    }

    private static Ledger ledger(RecoveryAccess access,RecoveryRecords.Original original) throws Exception {
        try(Connection connection=access.open("ledger-db")) {
            ObjectNode decision;
            try(PreparedStatement query=connection.prepareStatement("SELECT row_to_json(o)::text FROM ledger.ledger_operations o WHERE order_id=?")) {
                query.setLong(1,original.orderId()); try(ResultSet rows=query.executeQuery()) {
                    RecoveryRecords.require(rows.next(),"Permanent ledger decision is unavailable"); decision=(ObjectNode)RecoveryRecords.JSON.readTree(rows.getString(1));
                }
            }
            ArrayNode bindings=RecoveryRecords.JSON.createArrayNode();
            try(PreparedStatement query=connection.prepareStatement("SELECT row_to_json(r)::text FROM ledger.ledger_result_ids r WHERE order_id=? ORDER BY message_id")) {
                query.setLong(1,original.orderId()); try(ResultSet rows=query.executeQuery()) { while(rows.next()) bindings.add(RecoveryRecords.JSON.readTree(rows.getString(1))); }
            }
            RecoveryRecords.require(!bindings.isEmpty(),"Original permanent result evidence is unavailable");
            ObjectNode result=(ObjectNode)bindings.get(0).path("envelope");
            // A terminal original may have accepted another original reconciliation binding.
            if(original.saga().hasNonNull("ledger_result")) {
                boolean found=false;
                for(JsonNode binding:bindings) if(binding.path("envelope").equals(original.saga().path("ledger_result"))) { result=(ObjectNode)binding.path("envelope"); found=true; }
                RecoveryRecords.require(found,"Original accepted envelope is not retained by ledger");
            } else {
                boolean found=false;
                for(JsonNode binding:bindings) for(JsonNode reserved:original.rows("order_result_ids"))
                    if(binding.path("result_message_id").equals(reserved.path("result_message_id"))
                            && binding.path("message_id").equals(reserved.path("message_id"))) { result=(ObjectNode)binding.path("envelope"); found=true; break; }
                RecoveryRecords.require(found,"Original pending rowset has no ledger-reserved result identity");
            }
            validateResultDecision(decision,result);
            try(PreparedStatement entries=connection.prepareStatement("SELECT count(*),count(*) FILTER(WHERE amount=? AND currency=? AND recorded_at=?) FROM ledger.ledger_entries WHERE order_id=?")) {
                entries.setBigDecimal(1,decimal(decision,"amount"));entries.setString(2,decision.path("currency").asText());
                entries.setTimestamp(3,java.sql.Timestamp.from(java.time.OffsetDateTime.parse(decision.path("decided_at").asText()).toInstant()));entries.setLong(4,original.orderId());
                try(ResultSet counts=entries.executeQuery()) {counts.next();long expected=decision.path("outcome").asText().equals("RECORDED")?1:0;
                    RecoveryRecords.require(counts.getLong(1)==expected && counts.getLong(2)==expected,"Permanent ledger entry disagrees with saved decision");}
            }
            return new Ledger(decision,bindings,result);
        }
    }

    static void validateResultDecision(JsonNode decision,JsonNode result) throws IOException {
        String outcome=decision.path("outcome").asText();
        RecoveryRecords.require(List.of("RECORDED","REJECTED").contains(outcome)
                && result.path("outcome").asText().equals(outcome)
                && result.path("type").asText().equals(outcome.equals("RECORDED")?"LedgerRecorded":"LedgerRejected")
                && result.path("schemaVersion").asInt(-1)==decision.path("result_schema_version").asInt(-2)
                && result.path("currency").asText().equals(decision.path("currency").asText())
                && decimal(result,"amount").compareTo(decimal(decision,"amount"))==0
                && result.path("rejectionReason").asText("").equals(decision.path("rejection_reason").asText("")),
                "Authentic result data disagrees with permanent ledger outcome");
        try {
            RecoveryRecords.require(java.time.Instant.parse(result.path("decidedAt").asText()).equals(
                    java.time.OffsetDateTime.parse(decision.path("decided_at").asText()).toInstant()),"Authentic result decision timestamp differs");
        } catch(java.time.DateTimeException invalid) {throw new IOException("Permanent result timestamp evidence is invalid");}
    }

    private static void validateBindings(RecoveryRecords.Original original,Ledger ledger) throws IOException {
        for(JsonNode binding:ledger.bindings()) {
            JsonNode command=binding.path("command_envelope"),result=binding.path("envelope");
            RecoveryRecords.require(command.isObject() && result.isObject(),"Permanent transport evidence is malformed");
            for(String[] field:List.of(new String[]{"order_id","orderId"},new String[]{"buyer_id","buyerId"},new String[]{"saga_id","sagaId"},new String[]{"command_id","commandId"},new String[]{"request_fingerprint","requestFingerprint"})) {
                RecoveryRecords.require(ledger.decision().path(field[0]).asText().equals(command.path(field[1]).asText())
                        && ledger.decision().path(field[0]).asText().equals(result.path(field[1]).asText()),"Permanent command/result correlation differs");
            }
            RecoveryRecords.require(command.path("schemaVersion").asInt(-1)==original.order().path("currency_policy_version").asInt(-2)
                    && command.path("snapshot").equals(ledger.decision().path("snapshot"))
                    && command.path("messageId").asText().equals(binding.path("message_id").asText())
                    && command.path("resultMessageId").asText().equals(binding.path("result_message_id").asText())
                    && result.path("messageId").asText().equals(binding.path("result_message_id").asText()),"Permanent transport binding differs");
            validateResultDecision(ledger.decision(),result);
            ObjectNode data=((ObjectNode)result).deepCopy(); data.remove(List.of("messageId","occurredAt"));
            RecoveryRecords.require(data.equals(ledger.decision().path("result_data")),"Permanent result outcome differs from saved ledger decision");
        }
        for(JsonNode saved:original.rows("order_result_ids")) if(saved.hasNonNull("received_envelope")) {
            boolean found=false;
            for(JsonNode binding:ledger.bindings()) if(saved.path("message_id").equals(binding.path("message_id"))
                    && saved.path("result_message_id").equals(binding.path("result_message_id"))
                    && saved.path("received_envelope").equals(binding.path("envelope"))) found=true;
            RecoveryRecords.require(found,"Original received result conflicts with permanent ledger binding");
        }
        for(JsonNode outbox:original.rows("order_outbox")) {
            boolean found=false;
            for(JsonNode binding:ledger.bindings()) if(outbox.path("message_id").equals(binding.path("message_id"))
                    && outbox.path("result_message_id").equals(binding.path("result_message_id")) && outbox.path("envelope").equals(binding.path("command_envelope"))) found=true;
            RecoveryRecords.require(found,"Original command lacks exact permanent ledger evidence");
        }
    }

    private static void verifyForeignIdentities(RecoveryAccess access,RecoveryRecords.Original original) throws Exception {
        long saga=RecoveryRecords.positive(original.saga(),"saga_id"),command=RecoveryRecords.positive(original.saga(),"command_id");
        for(String shard:access.shards()) try(Connection connection=access.open(shard)) {
            if(!shard.equals(original.shard())) RecoveryRecords.require(RecoveryRecords.rows(connection,"orders","order_id",original.orderId()).isEmpty(),"Order exists on a foreign shard");
            try(PreparedStatement query=connection.prepareStatement("SELECT count(*) FROM ordering.order_sagas WHERE (saga_id=? OR command_id=?) AND order_id<>?")) {
                query.setLong(1,saga);query.setLong(2,command);query.setLong(3,original.orderId());
                try(ResultSet rows=query.executeQuery()) {rows.next();RecoveryRecords.require(rows.getLong(1)==0,"Original logical identity belongs to another order");}
            }
            JsonNode allocation=original.rows("order_allocations").get(0);
            try(PreparedStatement query=connection.prepareStatement("SELECT count(*) FROM ordering.order_allocations WHERE buyer_id=? AND run_name=? AND request_ordinal=? AND order_id<>?")) {
                query.setLong(1,RecoveryRecords.positive(original.order(),"buyer_id"));query.setString(2,allocation.path("run_name").asText());
                query.setLong(3,allocation.path("request_ordinal").asLong());query.setLong(4,original.orderId());
                try(ResultSet rows=query.executeQuery()) {rows.next();RecoveryRecords.require(rows.getLong(1)==0,"Original allocation retry coordinates belong to another order");}
            }
            for(JsonNode binding:original.rows("order_result_ids")) try(PreparedStatement query=connection.prepareStatement("SELECT count(*) FROM ordering.order_result_ids WHERE (message_id IN (?,?) OR result_message_id IN (?,?)) AND saga_id<>?")) {
                long message=RecoveryRecords.positive(binding,"message_id"),result=RecoveryRecords.positive(binding,"result_message_id");
                query.setLong(1,message);query.setLong(2,result);query.setLong(3,message);query.setLong(4,result);query.setLong(5,saga);
                try(ResultSet rows=query.executeQuery()) {rows.next();RecoveryRecords.require(rows.getLong(1)==0,"Original transport identity belongs to another saga");}
            }
        }
    }

    private static void verifyCatalog(RecoveryAccess access,RecoveryRecords.Original original,String status) throws Exception {
        Map<Long,JsonNode> items=new HashMap<>(); for(JsonNode item:original.rows("order_items")) items.put(RecoveryRecords.positive(item,"product_id"),item);
        Map<Long,Integer> reservations=new HashMap<>(),credits=new HashMap<>();
        for(String shard:access.shards()) try(Connection connection=access.open(shard)) {
            RecoveryRecords.require(RestoreVerification.count(connection,KindSaga.stockViolations())==0
                    && RestoreVerification.count(connection,KindSaga.profitViolations())==0,"Catalog stock or profit invariants disagree");
            try(PreparedStatement query=connection.prepareStatement("SELECT r.product_id,r.quantity,r.released_at,g.closed FROM catalog.stock_reservations r LEFT JOIN catalog.stock_reservation_guards g USING(order_id) WHERE r.order_id=?")) {
                query.setLong(1,original.orderId()); try(ResultSet rows=query.executeQuery()) {while(rows.next()) {
                    long product=rows.getLong(1);JsonNode item=items.get(product);
                    RecoveryRecords.require(item!=null && item.path("quantity").asInt()==rows.getInt(2)
                            && (rows.getTimestamp(3)==null)==status.equals("CONFIRMED"),"Catalog reservation disagrees with permanent order outcome");
                    validateReservationFence(rows.getObject(4)!=null,rows.getBoolean(4),rows.getTimestamp(3)!=null,status.equals("CONFIRMED"));
                    reservations.merge(product,1,Integer::sum);
                }}
            }
            try(PreparedStatement query=connection.prepareStatement("SELECT product_id,seller_id,currency,amount FROM catalog.profit_credits WHERE order_id=?")) {
                query.setLong(1,original.orderId());try(ResultSet rows=query.executeQuery()) {while(rows.next()) {
                    long product=rows.getLong(1);JsonNode item=items.get(product);
                    RecoveryRecords.require(status.equals("CONFIRMED") && item!=null && item.hasNonNull("unit_cost")
                            && RecoveryRecords.positive(item,"seller_id")==rows.getLong(2) && item.path("currency").asText().equals(rows.getString(3))
                            && decimal(item,"unit_price").subtract(decimal(item,"unit_cost")).multiply(BigDecimal.valueOf(item.path("quantity").asInt())).compareTo(rows.getBigDecimal(4))==0,
                            "Catalog profit credit disagrees with original acceptance-time margin");
                    credits.merge(product,1,Integer::sum);
                }}
            }
        }
        for(long product:items.keySet()) RecoveryRecords.require(reservations.getOrDefault(product,0)==1
                && credits.getOrDefault(product,0)==(status.equals("CONFIRMED")?1:0),
                "Original outcome lacks verified complete catalog effects; leave incident unresolved");
    }

    static void validateReservationFence(boolean guardExists,boolean closed,boolean released,boolean confirmed) throws IOException {
        RecoveryRecords.require(guardExists && closed!=confirmed && released!=confirmed,
                "Catalog reservation fence disagrees with permanent outcome");
    }

    private static List<Incident> incidents(Connection connection,RecoveryRecords.Original original,Ledger ledger) throws Exception {
        List<Incident> result=new ArrayList<>();
        for(String table:List.of("orphan_results","conflicting_results")) try(PreparedStatement query=connection.prepareStatement("SELECT message_id,payload_fingerprint,reason,envelope::text,command_id,saga_id,buyer_id,request_fingerprint,outcome FROM ordering."+table+" WHERE order_id=? AND resolved_at IS NULL FOR UPDATE")) {
            query.setLong(1,original.orderId());try(ResultSet rows=query.executeQuery()) {while(rows.next()) {
                String reason=rows.getString(3);JsonNode envelope=RecoveryRecords.JSON.readTree(rows.getString(4)); boolean matches=false;
                for(JsonNode binding:ledger.bindings()) if(binding.path("envelope").equals(envelope)) matches=true;
                if(matches) RecoveryRecords.require(rows.getLong(1)==Long.parseLong(envelope.path("messageId").asText())
                        && rows.getString(2).equals(RecoveryRecords.digest(RecoveryRecords.canonical(envelope)))
                        && rows.getLong(5)==RecoveryRecords.positive(original.saga(),"command_id")
                        && rows.getLong(6)==RecoveryRecords.positive(original.saga(),"saga_id")
                        && rows.getLong(7)==RecoveryRecords.positive(original.order(),"buyer_id")
                        && rows.getString(8).equals(original.order().path("request_fingerprint").asText())
                        && rows.getString(9).equals(ledger.decision().path("outcome").asText()),"Stored incident identity or fingerprint disagrees with authentic envelope");
                if(table.equals("orphan_results")) RecoveryRecords.require(matches && (reason.startsWith("AUDIT_") || reason.equals("ORDER_ABSENT")),
                        "Unresolved orphan has conflicting or unsupported evidence");
                if(matches && (reason.startsWith("AUDIT_") || table.equals("orphan_results") && reason.equals("ORDER_ABSENT")))
                    result.add(new Incident(table,rows.getLong(1),rows.getString(2),reason));
            }}
        }
        return result;
    }

    private static void applyAuthenticResult(Connection connection,RecoveryRecords.Original original,ObjectNode envelope,String status) throws Exception {
        long saga=RecoveryRecords.positive(original.saga(),"saga_id"),message=Long.parseLong(envelope.path("messageId").asText());
        try(PreparedStatement update=connection.prepareStatement("UPDATE ordering.order_sagas SET status=?,cancellation_reason=?,ledger_result=?::jsonb,"
                +"updated_at=GREATEST(clock_timestamp(),created_at),stock_lease_until=NULL,stock_lease_version=stock_lease_version+1,"
                +"next_stock_attempt_at=clock_timestamp(),reconciliation_attempts=0,next_reconciliation_at=GREATEST(clock_timestamp(),created_at),"
                +"stock_released_at=CASE WHEN ?='CANCELLED' THEN GREATEST(clock_timestamp(),created_at) ELSE stock_released_at END "
                +"WHERE saga_id=? AND order_id=? AND status='PENDING_LEDGER' AND ledger_result IS NULL")) {
            update.setString(1,status);update.setString(2,envelope.hasNonNull("rejectionReason")?envelope.path("rejectionReason").asText():null);
            update.setString(3,envelope.toString());update.setString(4,status);update.setLong(5,saga);update.setLong(6,original.orderId());
            RecoveryRecords.require(update.executeUpdate()==1,"Original pending saga changed before authentic result application");
        }
        try(PreparedStatement update=connection.prepareStatement("UPDATE ordering.orders SET status=?,updated_at=GREATEST(clock_timestamp(),created_at) WHERE order_id=? AND status='PENDING_LEDGER'")) {
            update.setString(1,status);update.setLong(2,original.orderId());RecoveryRecords.require(update.executeUpdate()==1,"Original pending order changed");
        }
        if(status.equals("CONFIRMED")) for(JsonNode item:original.rows("order_items")) try(PreparedStatement insert=connection.prepareStatement(
                "INSERT INTO ordering.profit_intents(order_id,item_position,seller_id,product_id,currency,amount,created_at,applied_at) "
                        +"VALUES(?,?,?,?,?,?,clock_timestamp(),clock_timestamp())")) {
            insert.setLong(1,original.orderId());insert.setInt(2,item.path("item_position").asInt());insert.setLong(3,RecoveryRecords.positive(item,"seller_id"));
            insert.setLong(4,RecoveryRecords.positive(item,"product_id"));insert.setString(5,item.path("currency").asText());
            insert.setBigDecimal(6,decimal(item,"unit_price").subtract(decimal(item,"unit_cost")).multiply(BigDecimal.valueOf(item.path("quantity").asInt())));
            RecoveryRecords.require(insert.executeUpdate()==1,"Authentic result profit intent insertion failed");
        }
        try(PreparedStatement update=connection.prepareStatement("UPDATE ordering.order_result_ids SET received_envelope=?::jsonb WHERE result_message_id=? AND saga_id=? AND received_envelope IS NULL")) {
            update.setString(1,envelope.toString());update.setLong(2,message);update.setLong(3,saga);RecoveryRecords.require(update.executeUpdate()==1,"Authentic result identity is not reserved");
        }
        try(PreparedStatement insert=connection.prepareStatement("INSERT INTO ordering.order_inbox(message_id,saga_id,envelope) VALUES(?,?,?::jsonb) ON CONFLICT(message_id) DO NOTHING")) {
            insert.setLong(1,message);insert.setLong(2,saga);insert.setString(3,envelope.toString());insert.executeUpdate();
        }
    }
    static BigDecimal decimal(JsonNode value,String field) throws IOException {
        try {return new BigDecimal(value.path(field).asText());} catch(NumberFormatException invalid) {throw new IOException("Recovery monetary evidence is incomplete");}
    }
}
