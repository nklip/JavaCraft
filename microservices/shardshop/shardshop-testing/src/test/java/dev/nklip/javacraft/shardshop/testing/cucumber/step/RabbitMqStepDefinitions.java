package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ShutdownSignalException;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the chart's broker contract over AMQP; HTTP is only used to inspect effective state. */
public final class RabbitMqStepDefinitions {
    private static final Duration DELIVERY = Duration.ofSeconds(10);
    private final RabbitMqBroker broker;

    public RabbitMqStepDefinitions(RabbitMqBroker broker) {
        this.broker = broker;
    }

    @Given("RabbitMQ runs with the chart configuration on retained storage")
    public void start() throws IOException, InterruptedException {
        broker.start();
    }

    @Then("RabbitMQ exposes the pinned runtime, effective topology and readiness contract")
    public void topology() throws IOException, InterruptedException {
        JsonNode overview = broker.get("/api/overview");
        assertEquals("4.3.7", overview.path("rabbitmq_version").asText());
        assertEquals("27.3.4.19", overview.path("erlang_version").asText());
        boolean streams = false;
        for (JsonNode flag : broker.get("/api/feature-flags")) {
            if (flag.path("name").asText().equals("stream_queue")) {
                assertEquals("enabled", flag.path("state").asText());
                streams = true;
            }
        }
        assertTrue(streams, "At-least-once quorum dead-lettering requires stream_queue");
        JsonNode definitions = Json.parse(Files.readAllBytes(ShardShopFiles.resolve(
                "infra/helm/shardshop/files/rabbitmq/definitions.json")));
        assertEquals(4, broker.get("/api/queues/shardshop").size());
        for (JsonNode expected : definitions.path("queues")) {
            String name = expected.path("name").asText();
            JsonNode actual = broker.queue(name);
            assertEquals("quorum", actual.path("type").asText(), name);
            assertTrue(actual.path("durable").asBoolean(), name);
            assertFalse(actual.path("auto_delete").asBoolean(), name);
            assertEquals(expected.path("arguments"), actual.path("arguments"), name);
            assertEquals(1, actual.path("members").size(), "The lab intentionally has one quorum member");
            JsonNode policy = actual.path("effective_policy_definition");
            assertEquals("reject-publish", policy.path("overflow").asText(), name);
            assertEquals(1000, policy.path("max-length").asInt(), name);
            assertEquals(16 * 1024 * 1024, policy.path("max-length-bytes").asInt(), name);
            if (name.endsWith(".dlq")) {
                assertEquals(-1, actual.path("arguments").path("x-delivery-limit").asInt(), name);
                assertFalse(policy.has("dead-letter-exchange"), "Parking queues must have no onward DLX");
                assertFalse(policy.has("message-ttl"), "Parking queues must not expire messages");
                assertEquals(0, actual.path("consumers").asInt(), "No parking queue consumer");
            } else {
                assertEquals(5, policy.path("delivery-limit").asInt(), name);
                assertEquals("failed", policy.path("delayed-retry-type").asText(), name);
                assertEquals(1000, policy.path("delayed-retry-min").asInt(), name);
                assertEquals(5000, policy.path("delayed-retry-max").asInt(), name);
                assertEquals("at-least-once", policy.path("dead-letter-strategy").asText(), name);
                assertEquals("shardshop.dlx", policy.path("dead-letter-exchange").asText(), name);
                assertEquals(name + ".dlq", policy.path("dead-letter-routing-key").asText(), name);
            }
        }
        for (JsonNode expected : definitions.path("exchanges")) {
            JsonNode actual = broker.get("/api/exchanges/shardshop/" + expected.path("name").asText());
            assertEquals("direct", actual.path("type").asText());
            assertTrue(actual.path("durable").asBoolean());
        }
        List<String> bindings = new ArrayList<>();
        for (JsonNode binding : broker.get("/api/bindings/shardshop")) {
            bindings.add(binding.path("source").asText() + "/" + binding.path("destination").asText()
                    + "/" + binding.path("routing_key").asText());
        }
        for (JsonNode binding : definitions.path("bindings")) {
            assertTrue(bindings.contains(binding.path("source").asText() + "/" + binding.path("destination").asText()
                    + "/" + binding.path("routing_key").asText()), "A declared route is missing");
        }
        broker.assertReadiness();
    }

    @Then("RabbitMQ rejects guest and restricts order and ledger to their own messaging operations")
    public void authorization() throws Exception {
        assertEquals(401, broker.guestStatus());
        assertEquals(401, broker.managementStatus("/api/overview", "order"));
        assertEquals(401, broker.managementStatus("/api/overview", "ledger"));
        List<String> users = new ArrayList<>();
        for (JsonNode user : broker.get("/api/users")) {
            users.add(user.path("name").asText());
        }
        assertEquals(List.of("shardshop-admin", "shardshop-ledger", "shardshop-order"), users.stream().sorted().toList());
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            assertTrue(publish(channel, "ledger.commands", "ledger.record-order", payload("consume-ledger")));
            assertTrue(publish(channel, "order.saga-results", "order.ledger-results", payload("consume-order")));
        }
        for (String role : List.of("order", "ledger")) {
            String exchange = role.equals("order") ? "ledger.commands" : "order.saga-results";
            String destination = role.equals("order") ? "ledger.record-order" : "order.ledger-results";
            String consumed = role.equals("order") ? "order.ledger-results" : "ledger.record-order";
            try (Connection connection = broker.connect(role); Channel channel = connection.createChannel()) {
                assertTrue(publish(channel, exchange, destination, payload("authorized-" + role)));
                GetResponse authorized = delivery(channel, consumed, DELIVERY);
                assertArrayEquals(payload("consume-" + role), authorized.getBody());
                channel.basicAck(authorized.getEnvelope().getDeliveryTag(), false);
            }
            denied(role, channel -> channel.queueDeclare("unapproved-queue", true, false, false, Map.of()));
            denied(role, channel -> channel.exchangeDeclare(exchange, "direct", true));
            denied(role, channel -> channel.basicGet(destination, false));
            denied(role, channel -> {
                channel.confirmSelect();
                channel.basicPublish(role.equals("order") ? "order.saga-results" : "ledger.commands",
                        consumed, true, properties(), payload("forbidden"));
                channel.waitForConfirmsOrDie(5_000);
            });
        }
    }

    @Then("native reject retries {string} at the configured delays and delivery limit while nack does not count as failure")
    public void retries(String queue) throws Exception {
        byte[] body = payload("retry-boundary");
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            assertTrue(publish(channel, exchange(queue), queue, body));
            GetResponse delivery = delivery(channel, queue, DELIVERY);
            assertEquals(0, failureCount(delivery));
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            delivery = delivery(channel, queue, DELIVERY);
            assertEquals(0, failureCount(delivery), "basic.nack must not spend the failure retry budget");
            List<Long> milliseconds = new ArrayList<>();
            List<Long> redeliveredAt = new ArrayList<>();
            long started = System.nanoTime();
            for (int failed = 1; failed <= 5; failed++) {
                long rejected = System.nanoTime();
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
                delivery = delivery(channel, queue, DELIVERY);
                long elapsed = Duration.ofNanos(System.nanoTime() - rejected).toMillis();
                milliseconds.add(elapsed);
                redeliveredAt.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
                assertEquals(failed, failureCount(delivery), "basic.reject failure number " + failed);
                assertArrayEquals(body, delivery.getBody());
                assertTrue(delivery.getEnvelope().isRedeliver());
                assertTrue(elapsed >= failed * 1000L - 200, "Retry arrived before its delay: " + milliseconds);
                assertTrue(elapsed < failed * 1000L + 5000, "Retry exceeded its bounded delay: " + milliseconds);
            }
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
            GetResponse parked = delivery(channel, queue + ".dlq", DELIVERY);
            long parkedAfter = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertArrayEquals(body, parked.getBody());
            assertDeath(parked, queue, "delivery_limit");
            assertTrue(parkedAfter >= 14_000 && parkedAfter < 50_000,
                    "Six rejects must park after five delays totaling 15 seconds, plus bounded IO: " + parkedAfter);
            channel.basicAck(parked.getEnvelope().getDeliveryTag(), false);
            assertEquals(0, channel.queueDeclarePassive(queue).getMessageCount());
            assertEquals(0, channel.queueDeclarePassive(queue + ".dlq").getMessageCount());
            System.out.println("RabbitMQ " + queue + " failure counts=[0,1,2,3,4,5], rejects=6, "
                    + "redelivery delays (ms)=" + milliseconds + ", redelivery elapsed (ms)=" + redeliveredAt
                    + ", DLQ parking elapsed (ms)=" + parkedAfter);
        }
    }

    @Then("persistent unacknowledged and parked messages in {string} survive broker container replacement")
    public void persistence(String queue) throws Exception {
        byte[] pending = payload("unacknowledged");
        byte[] parked = payload("parked");
        Connection oldConnection = broker.connect("admin");
        try {
            Channel channel = oldConnection.createChannel();
            assertTrue(publish(channel, exchange(queue), queue, parked));
            GetResponse malformed = delivery(channel, queue, DELIVERY);
            channel.basicReject(malformed.getEnvelope().getDeliveryTag(), false);
            RabbitMqBroker.await("the rejected message is parked", DELIVERY,
                    () -> channel.queueDeclarePassive(queue + ".dlq").getMessageCount() == 1);
            assertTrue(publish(channel, exchange(queue), queue, pending));
            assertArrayEquals(pending, delivery(channel, queue, DELIVERY).getBody());
            broker.replaceContainer();
        } finally {
            oldConnection.abort();
        }
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            GetResponse recovered = delivery(channel, queue, DELIVERY);
            assertArrayEquals(pending, recovered.getBody());
            assertTrue(recovered.getEnvelope().isRedeliver(), "Unacknowledged delivery must be recovered");
            assertEquals(2, recovered.getProps().getDeliveryMode());
            channel.basicAck(recovered.getEnvelope().getDeliveryTag(), false);
            GetResponse recoveredParked = delivery(channel, queue + ".dlq", DELIVERY);
            assertArrayEquals(parked, recoveredParked.getBody());
            assertDeath(recoveredParked, queue, "rejected");
            channel.basicAck(recoveredParked.getEnvelope().getDeliveryTag(), false);
        }
        topology();
    }

    @Then("queue capacity rejects publishes and a full parking queue retains its dead letter until capacity returns")
    public void capacity() throws Exception {
        String queue = "ledger.record-order";
        byte[] filler = payload("capacity");
        byte[] retained = payload("retained-dead-letter");
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            int sourceMessages = fillToRejection(channel, "ledger.commands", queue, filler);
            channel.queuePurge(queue);
            int parkingMessages = fillToRejection(channel, "shardshop.dlx", queue + ".dlq", filler);
            System.out.println("RabbitMQ accepted messages before negative confirm: source=" + sourceMessages
                    + ", parking=" + parkingMessages);
            assertTrue(publish(channel, "ledger.commands", queue, retained));
            GetResponse delivery = delivery(channel, queue, DELIVERY);
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
            RabbitMqBroker.await("the source retains the unconfirmed dead letter", Duration.ofSeconds(15),
                    () -> broker.queue(queue).path("messages").asInt() == 1
                            && broker.queue(queue + ".dlq").path("messages").asInt() == parkingMessages);
            broker.assertReadiness();
            for (int index = 0; index < parkingMessages; index++) {
                GetResponse parked = delivery(channel, queue + ".dlq", DELIVERY);
                assertArrayEquals(filler, parked.getBody());
                channel.basicAck(parked.getEnvelope().getDeliveryTag(), false);
            }
            // RabbitMQ 4.3.7 defaults dead_letter_worker_publisher_confirm_timeout to 180000 ms.
            // https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.7/deps/rabbit/Makefile
            System.out.println("RabbitMQ waiting for the default 180-second dead-letter retry after capacity returns");
            GetResponse recovered = delivery(channel, queue + ".dlq", Duration.ofSeconds(210));
            assertArrayEquals(retained, recovered.getBody());
            assertDeath(recovered, queue, "rejected");
            channel.basicAck(recovered.getEnvelope().getDeliveryTag(), false);
            RabbitMqBroker.await("the source released its confirmed dead letter", DELIVERY,
                    () -> broker.queue(queue).path("messages").asInt() == 0);
            assertEquals(0, channel.queueDeclarePassive(queue + ".dlq").getMessageCount());
        }
    }

    private static int fillToRejection(Channel channel, String exchange, String queue, byte[] filler) throws Exception {
        // Quorum admission can overshoot its limit; prove bounded backpressure and retain every accepted message.
        for (int accepted = 0; accepted < 1100; accepted++) {
            if (!publish(channel, exchange, queue, filler)) {
                assertTrue(accepted >= 1000, "Small messages must fit the configured message budget");
                assertEquals(accepted, channel.queueDeclarePassive(queue).getMessageCount(),
                        "reject-publish must preserve all accepted messages instead of dropping old messages");
                return accepted;
            }
        }
        throw new AssertionError("Queue did not negatively confirm within the bounded capacity headroom: " + queue);
    }

    private void denied(String role, ChannelOperation operation) throws Exception {
        try (Connection connection = broker.connect(role)) {
            Channel channel = connection.createChannel();
            Exception denied = assertThrows(Exception.class, () -> operation.run(channel));
            Throwable cause = denied;
            while (cause != null && !(cause instanceof ShutdownSignalException)) {
                cause = cause.getCause();
            }
            assertNotNull(cause, "AMQP permission denial must close the channel");
            ShutdownSignalException shutdown = (ShutdownSignalException) cause;
            assertEquals(403, ((AMQP.Channel.Close) shutdown.getReason()).getReplyCode());
        }
    }

    @FunctionalInterface
    private interface ChannelOperation {
        void run(Channel channel) throws Exception;
    }

    private static boolean publish(Channel channel, String exchange, String routingKey, byte[] body) throws Exception {
        channel.confirmSelect();
        AtomicBoolean returned = new AtomicBoolean();
        var listener = channel.addReturnListener(message -> returned.set(true));
        try {
            channel.basicPublish(exchange, routingKey, true, properties(), body);
            boolean confirmed = channel.waitForConfirms(5_000);
            assertFalse(returned.get(), "Mandatory publish was unroutable");
            return confirmed;
        } finally {
            channel.removeReturnListener(listener);
        }
    }

    private static AMQP.BasicProperties properties() {
        return new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/octet-stream")
                .messageId(UUID.randomUUID().toString()).build();
    }

    private static byte[] payload(String label) {
        return ("broker-acceptance:" + label).getBytes(StandardCharsets.UTF_8);
    }

    private static GetResponse delivery(Channel channel, String queue, Duration timeout)
            throws IOException, InterruptedException {
        GetResponse[] result = new GetResponse[1];
        RabbitMqBroker.await("a delivery on " + queue, timeout, () -> {
            result[0] = channel.basicGet(queue, false);
            return result[0] != null;
        });
        return result[0];
    }

    private static long failureCount(GetResponse response) {
        Map<String, Object> headers = response.getProps().getHeaders();
        return headers == null || !headers.containsKey("x-delivery-count")
                ? 0 : ((Number) headers.get("x-delivery-count")).longValue();
    }

    private static void assertDeath(GetResponse response, String queue, String reason) {
        Object value = response.getProps().getHeaders().get("x-death");
        assertTrue(value instanceof List<?>, "Dead-letter delivery must contain x-death");
        Map<?, ?> death = (Map<?, ?>) ((List<?>) value).getFirst();
        assertEquals(queue, death.get("queue").toString());
        assertEquals(reason, death.get("reason").toString());
        assertEquals(1L, ((Number) death.get("count")).longValue());
    }

    private static String exchange(String queue) {
        return queue.equals("ledger.record-order") ? "ledger.commands" : "order.saga-results";
    }
}
