package dev.nklip.javacraft.shardshop.common.jdbc;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** PostgreSQL JDBC URL defaults that applications apply before they create their own pools. */
public final class JdbcUrls {
    private static final String DRIVER_LIMITS = "connectTimeout=1&loginTimeout=1&socketTimeout=3&cancelSignalTimeout=1";

    private JdbcUrls() {
    }

    /**
     * Adds {@code sslmode=verify-full} when the URL sets no mode, and the root certificate when a verifying mode
     * has none. An explicit mode or certificate stays; pgjdbc uses the last value of a repeated parameter.
     */
    public static String secure(String url, String rootCertificate) {
        String mode = parameter(url, "sslmode");
        if (mode == null || mode.isBlank()) {
            mode = "verify-full";
            url = append(url, "sslmode", mode);
        }
        if (mode.equalsIgnoreCase("verify-full") || mode.equalsIgnoreCase("verify-ca")) {
            String root = parameter(url, "sslrootcert");
            if (root == null || root.isBlank()) {
                if (rootCertificate.isBlank()) {
                    throw new IllegalArgumentException("The root certificate must not be blank");
                }
                url = append(url, "sslrootcert", rootCertificate);
            }
        }
        return url;
    }

    /** Appends finite driver limits last, because URL parameters take precedence over driver properties. */
    public static String withDriverLimits(String url) {
        return url + (url.contains("?") ? "&" : "?") + DRIVER_LIMITS;
    }

    private static String parameter(String url, String name) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        String value = null;
        for (String part : url.substring(query + 1).split("&")) {
            if (part.startsWith(name + "=")) {
                try {
                    value = URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException("Invalid TLS parameter encoding");
                }
            }
        }
        return value;
    }

    private static String append(String url, String name, String value) {
        return url + (url.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
