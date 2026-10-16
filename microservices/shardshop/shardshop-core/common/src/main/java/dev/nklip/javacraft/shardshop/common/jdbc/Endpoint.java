package dev.nklip.javacraft.shardshop.common.jdbc;

import java.util.Objects;

/** One PostgreSQL endpoint of an application-owned pool; the text form hides the URL and the credentials. */
public record Endpoint(String jdbcUrl, String username, String password) {
    public Endpoint {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (!jdbcUrl.startsWith("jdbc:postgresql:") || username.isBlank()) {
            throw new IllegalArgumentException("A PostgreSQL endpoint and username are required");
        }
    }

    @Override
    public String toString() {
        return "Endpoint[configured]";
    }
}
