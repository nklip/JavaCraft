package dev.nklip.javacraft.shardshop.common.amqp;

import com.rabbitmq.client.ConnectionFactory;

import java.time.Duration;

/** The RabbitMQ connection of a publisher or a consumer. The application owns the values and the credential. */
public record BrokerSettings(String host, int port, String virtualHost, String username,
                             String password, Duration timeout) {
    public BrokerSettings {
        if (host == null || host.isBlank() || virtualHost == null || virtualHost.isBlank()
                || username == null || username.isBlank() || password == null) {
            throw new IllegalArgumentException("RabbitMQ connection settings are incomplete");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("RabbitMQ port is out of range");
        }
        if (timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("RabbitMQ timeout is out of range");
        }
    }

    public int timeoutMillis() {
        return Math.toIntExact(timeout.toMillis());
    }

    /**
     * Applies these settings and the shared client policy: the timeout for each broker operation, a heartbeat, no
     * automatic recovery (the owner replaces a failed connection) and daemon threads with the given name prefix.
     */
    void configure(ConnectionFactory factory, String threadPrefix) {
        int timeoutMillis = timeoutMillis();
        factory.setHost(host);
        factory.setPort(port);
        factory.setVirtualHost(virtualHost);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setConnectionTimeout(timeoutMillis);
        factory.setHandshakeTimeout(timeoutMillis);
        factory.setChannelRpcTimeout(timeoutMillis);
        factory.setShutdownTimeout(timeoutMillis);
        factory.setRequestedHeartbeat(10);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        factory.setThreadFactory(Thread.ofPlatform().daemon().name(threadPrefix, 0).factory());
    }

    @Override
    public String toString() {
        return "BrokerSettings[host=" + host + ", port=" + port + ", virtualHost=" + virtualHost
                + ", username=" + username + ", password=<redacted>, timeout=" + timeout + "]";
    }
}
