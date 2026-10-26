package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Cross-database qualification of a coordinated, drained baseline. Never repairs business state. */
public final class RestoreVerification {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RestoreVerification() { }

    public record Report(long orders, long ledgerDecisions, long publishedCommands, long resultBindings) { }
    record Order(long id, long buyer, long saga, long command, String fingerprint, String currency,
                 BigDecimal amount, String status, String reason, boolean released, JsonNode result) { }
    record Decision(long order, long buyer, long saga, long command, String fingerprint, String currency,
                    BigDecimal amount, String outcome, String reason, JsonNode snapshot, JsonNode result) { }

    public static Report verify(OwnedRestoreTarget target, List<String> shards) throws Exception {
        require(shards.equals(List.of("shard-a", "shard-b", "shard-c")), "Restore requires the captured three-shard inventory");
        long orders = 0, decisions = 0, published = 0, bindings = 0;
        try (CatalogConnections catalogs = CatalogConnections.open(target, shards);
             Connection ledger = target.connection("ledger-db")) {
            require(count(ledger, "SELECT count(*) FROM ledger.ledger_outbox WHERE published_at IS NULL") == 0,
                    "Coordinated baseline has unpublished ledger results");
            require(count(ledger, "SELECT count(*) FROM ledger.ledger_entries e FULL JOIN ledger.ledger_operations o USING(order_id) "
                    + "WHERE (o.outcome='RECORDED') IS DISTINCT FROM (e.order_id IS NOT NULL) "
                    + "OR e.amount IS DISTINCT FROM o.amount AND e.order_id IS NOT NULL "
                    + "OR e.currency IS DISTINCT FROM o.currency AND e.order_id IS NOT NULL "
                    + "OR e.recorded_at IS DISTINCT FROM o.decided_at AND e.order_id IS NOT NULL") == 0,
                    "Ledger entries disagree with permanent decisions");
            for (String shard : shards) {
                try (Connection source = target.connection(shard)) {
                    verifyLocal(source);
                    source.setAutoCommit(false);
                    try (Statement query = source.createStatement()) {
                        query.setFetchSize(128);
                        query.setQueryTimeout(30);
                        try (ResultSet rows = query.executeQuery("SELECT o.order_id,o.buyer_id,s.saga_id,s.command_id,o.request_fingerprint,"
                                + "o.currency,o.total_amount,o.status,s.cancellation_reason,s.stock_released_at IS NOT NULL,s.ledger_result::text "
                                + "FROM ordering.orders o JOIN ordering.order_sagas s USING(order_id) ORDER BY o.order_id")) {
                            while (rows.next()) {
                                Order order = new Order(rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),
                                        rows.getString(5),rows.getString(6),rows.getBigDecimal(7),rows.getString(8),rows.getString(9),
                                        rows.getBoolean(10),parse(rows.getString(11)));
                                Decision decision = decision(ledger, order.id());
                                validateDecision(order, decision);
                                verifyItems(catalogs.connections(), source, order, decision);
                                verifyBindings(source, ledger, order);
                                orders++;
                                if (decision != null) decisions++;
                            }
                        }
                    }
                    published += verifyPublished(source, ledger);
                    bindings += count(source, "SELECT count(*) FROM ordering.order_result_ids");
                    source.rollback();
                }
            }
            require(decisions == count(ledger, "SELECT count(*) FROM ledger.ledger_operations"),
                    "Permanent ledger decisions have missing or duplicate restored orders");
        }
        return new Report(orders, decisions, published, bindings);
    }

    private static void verifyLocal(Connection connection) throws SQLException, IOException {
        require(count(connection, "SELECT count(*) FROM ordering.orders o FULL JOIN ordering.order_sagas s USING(order_id) "
                + "WHERE o.order_id IS NULL OR s.order_id IS NULL OR o.status<>s.status "
                + "OR o.status NOT IN ('CONFIRMED','CANCELLED') OR (s.status='CANCELLED' AND s.stock_released_at IS NULL)") == 0,
                "Coordinated baseline has missing, inconsistent or unfinished sagas");
        require(count(connection, "SELECT count(*) FROM ordering.orders o LEFT JOIN ordering.order_allocations a "
                + "ON o.order_id=a.order_id AND o.buyer_id=a.buyer_id WHERE a.order_id IS NULL") == 0,
                "Order allocation identity is absent");
        require(count(connection, "SELECT count(*) FROM ordering.profit_intents WHERE applied_at IS NULL OR blocked_reason IS NOT NULL") == 0,
                "Coordinated baseline has unfinished profit work");
        require(count(connection, "SELECT count(*) FROM ordering.order_outbox WHERE published_at IS NULL") == 0,
                "Coordinated baseline has unpublished commands");
        require(count(connection, "SELECT count(*) FROM ordering.profit_intents p JOIN ordering.order_items i USING(order_id,item_position) "
                + "JOIN ordering.orders o USING(order_id) WHERE o.status<>'CONFIRMED' OR p.seller_id<>i.seller_id "
                + "OR p.product_id<>i.product_id OR p.currency<>i.currency OR i.unit_cost IS NULL "
                + "OR p.amount<>(i.unit_price-i.unit_cost)*i.quantity") == 0, "Profit intent differs from immutable order margin");
        require(count(connection, KindSaga.stockViolations()) == 0, "Stock differs from permanent reservation history");
        require(count(connection, KindSaga.profitViolations()) == 0, "Seller balance differs from permanent profit credits");
    }

    static void validateDecision(Order order, Decision decision) throws IOException {
        boolean confirmed = "CONFIRMED".equals(order.status());
        require(confirmed || "CANCELLED".equals(order.status()), "Order is not terminal");
        require(confirmed || order.released(), "Cancelled order has unfinished compensation");
        if ("OUT_OF_STOCK".equals(order.reason())) {
            require(!confirmed && decision == null && order.result() == null, "Stock cancellation has a ledger decision");
            return;
        }
        require(decision != null, "Terminal order has no permanent ledger decision");
        require(order.id()==decision.order() && order.buyer()==decision.buyer() && order.saga()==decision.saga()
                && order.command()==decision.command() && order.fingerprint().equals(decision.fingerprint())
                && order.currency().equals(decision.currency()) && order.amount().compareTo(decision.amount())==0,
                "Permanent ledger identity or immutable order data disagree");
        require(decision.snapshot()!=null && order.currency().equals(decision.snapshot().path("currency").asText())
                && order.amount().compareTo(new BigDecimal(decision.snapshot().path("amount").asText()))==0,
                "Permanent ledger snapshot total differs from order");
        require((confirmed ? "RECORDED" : "REJECTED").equals(decision.outcome()), "Terminal order and ledger outcome disagree");
        require(confirmed || java.util.Objects.equals(order.reason(),decision.reason()), "Cancellation reason differs from permanent rejection");
        require(order.result()!=null && order.result().path("orderId").asText().equals(Long.toString(order.id()))
                && order.result().path("sagaId").asText().equals(Long.toString(order.saga()))
                && order.result().path("commandId").asText().equals(Long.toString(order.command()))
                && order.result().path("requestFingerprint").asText().equals(order.fingerprint()),
                "Accepted permanent result is absent or has mismatched identities");
        JsonNode accepted = order.result().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) accepted).remove(List.of("messageId", "occurredAt"));
        require(accepted.equals(decision.result()), "Accepted permanent result differs from the original ledger outcome");
    }

    private static Decision decision(Connection ledger, long id) throws SQLException, IOException {
        try (PreparedStatement query = ledger.prepareStatement("SELECT order_id,buyer_id,saga_id,command_id,request_fingerprint,"
                + "currency,amount,outcome,rejection_reason,snapshot::text,result_data::text FROM ledger.ledger_operations WHERE order_id=?")) {
            query.setLong(1,id);
            try (ResultSet rows=query.executeQuery()) {
                if (!rows.next()) return null;
                return new Decision(rows.getLong(1),rows.getLong(2),rows.getLong(3),rows.getLong(4),rows.getString(5),rows.getString(6),
                        rows.getBigDecimal(7),rows.getString(8),rows.getString(9),parse(rows.getString(10)),parse(rows.getString(11)));
            }
        }
    }

    private static void verifyItems(List<Connection> catalogs, Connection source, Order order, Decision decision)
            throws Exception {
        int items=0;
        BigDecimal total=BigDecimal.ZERO;
        try (PreparedStatement query=source.prepareStatement("SELECT item_position,seller_id,product_id,quantity,product_name,unit_price,currency,unit_cost "
                + "FROM ordering.order_items WHERE order_id=? ORDER BY item_position")) {
            query.setLong(1,order.id());
            try (ResultSet rows=query.executeQuery()) {
                while (rows.next()) {
                    int position=rows.getInt(1), quantity=rows.getInt(4);
                    long seller=rows.getLong(2), product=rows.getLong(3);
                    BigDecimal price=rows.getBigDecimal(6), cost=rows.getBigDecimal(8);
                    String currency=rows.getString(7);
                    total=total.add(price.multiply(BigDecimal.valueOf(quantity)));
                    if (decision!=null) {
                        JsonNode item=decision.snapshot().path("items").path(items);
                        require(item.path("itemPosition").asInt(-1)==position && item.path("sellerId").asText().equals(Long.toString(seller))
                                && item.path("productId").asText().equals(Long.toString(product)) && item.path("quantity").asInt()==quantity
                                && item.path("productName").asText().equals(rows.getString(5)) && currency.equals(item.path("currency").asText())
                                && price.compareTo(new BigDecimal(item.path("unitPrice").asText()))==0,
                                "Ledger item snapshot differs from accepted order");
                    }
                    long reservations=0, credits=0;
                    for (Connection catalog : catalogs) {
                        try (PreparedStatement reservation=catalog.prepareStatement("SELECT quantity,released_at IS NOT NULL FROM catalog.stock_reservations "
                                + "WHERE order_id=? AND product_id=?")) {
                            reservation.setLong(1,order.id()); reservation.setLong(2,product);
                            try (ResultSet rs=reservation.executeQuery()) { while(rs.next()) {
                                reservations++;
                                require(rs.getInt(1)==quantity && rs.getBoolean(2)!=order.status().equals("CONFIRMED"),
                                        "Reservation differs from terminal order");
                            } }
                        }
                        try (PreparedStatement credit=catalog.prepareStatement("SELECT seller_id,currency,amount FROM catalog.profit_credits WHERE order_id=? AND product_id=?")) {
                            credit.setLong(1,order.id()); credit.setLong(2,product);
                            try (ResultSet rs=credit.executeQuery()) { while(rs.next()) {
                                credits++;
                                require(cost!=null && rs.getLong(1)==seller && currency.equals(rs.getString(2))
                                        && rs.getBigDecimal(3).compareTo(price.subtract(cost).multiply(BigDecimal.valueOf(quantity)))==0,
                                        "Profit credit differs from immutable order margin");
                            } }
                        }
                    }
                    boolean confirmed=order.status().equals("CONFIRMED");
                    require(reservations<=1 && ("OUT_OF_STOCK".equals(order.reason()) || reservations==1)
                            && credits==(confirmed?1:0), "Terminal reservation or profit cardinality differs");
                    items++;
                }
            }
        }
        require(items>0 && total.compareTo(order.amount())==0, "Order item total differs from accepted amount");
        if (decision!=null) require(decision.snapshot().path("items").size()==items, "Ledger item snapshot has different cardinality");
        try (PreparedStatement query=source.prepareStatement("SELECT count(*) FROM ordering.profit_intents WHERE order_id=?")) {
            query.setLong(1,order.id());
            try(ResultSet rows=query.executeQuery()) { rows.next(); require(rows.getLong(1)==(order.status().equals("CONFIRMED")?items:0),
                    "Terminal order has incorrect permanent profit intents"); }
        }
    }

    private static void verifyBindings(Connection source, Connection ledger, Order order) throws Exception {
        try (PreparedStatement query=source.prepareStatement("SELECT message_id,result_message_id,received_envelope::text FROM ordering.order_result_ids WHERE saga_id=?")) {
            query.setLong(1,order.saga());
            try(ResultSet rows=query.executeQuery()) { while(rows.next()) {
                try(PreparedStatement match=ledger.prepareStatement("SELECT order_id,envelope::text FROM ledger.ledger_result_ids WHERE message_id=? AND result_message_id=?")) {
                    match.setLong(1,rows.getLong(1)); match.setLong(2,rows.getLong(2));
                    try(ResultSet found=match.executeQuery()) {
                        JsonNode received=parse(rows.getString(3));
                        if(received!=null) require(found.next() && found.getLong(1)==order.id() && received.equals(parse(found.getString(2))),
                                "Received permanent result binding differs from ledger envelope");
                    }
                }
            } }
        }
    }

    private static long verifyPublished(Connection source, Connection ledger) throws Exception {
        long count=0;
        try(Statement query=source.createStatement()) {
            query.setFetchSize(128);
            try(ResultSet rows=query.executeQuery("SELECT message_id,result_message_id,envelope::text FROM ordering.order_outbox WHERE published_at IS NOT NULL")) {
                while(rows.next()) {
                    try(PreparedStatement match=ledger.prepareStatement("SELECT command_envelope::text FROM ledger.ledger_result_ids WHERE message_id=? AND result_message_id=?")) {
                        match.setLong(1,rows.getLong(1)); match.setLong(2,rows.getLong(2));
                        try(ResultSet found=match.executeQuery()) {
                            validatePublished(parse(rows.getString(3)),found.next()?parse(found.getString(1)):null);
                        }
                    }
                    count++;
                }
            }
        }
        return count;
    }

    static void validatePublished(JsonNode command, JsonNode saved) throws IOException {
        require(command!=null && saved!=null && command.equals(saved),
                "Published retained command lacks identical permanent ledger evidence");
    }

    private record CatalogConnections(List<Connection> connections) implements AutoCloseable {
        static CatalogConnections open(OwnedRestoreTarget target, List<String> shards) throws Exception {
            var connections = new java.util.ArrayList<Connection>();
            try {
                for (String shard : shards) connections.add(target.connection(shard));
                return new CatalogConnections(List.copyOf(connections));
            } catch (Exception failed) {
                for (Connection connection : connections) try { connection.close(); } catch (SQLException close) { failed.addSuppressed(close); }
                throw failed;
            }
        }
        @Override public void close() throws SQLException {
            SQLException failure = null;
            for (Connection connection : connections) try { connection.close(); } catch (SQLException close) {
                if (failure == null) failure = close; else failure.addSuppressed(close);
            }
            if (failure != null) throw failure;
        }
    }

    static long count(Connection connection, String sql) throws SQLException {
        try(Statement query=connection.createStatement()) {
            query.setQueryTimeout(30);
            try(ResultSet rows=query.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
        }
    }
    static JsonNode parse(String value) throws IOException { return value==null?null:JSON.readTree(value); }
    static void require(boolean condition,String reason) throws IOException { if(!condition) throw new IOException(reason); }
}
