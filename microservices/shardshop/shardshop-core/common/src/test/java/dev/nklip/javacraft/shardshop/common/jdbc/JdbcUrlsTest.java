package dev.nklip.javacraft.shardshop.common.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcUrlsTest {
    private static final String ROOT = "/custom CA/root.crt";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jdbc:postgresql://localhost/shardshop|jdbc:postgresql://localhost/shardshop?sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?applicationName=order|jdbc:postgresql://localhost/shardshop?applicationName=order&sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=verify-full|jdbc:postgresql://localhost/shardshop?sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=verify-ca|jdbc:postgresql://localhost/shardshop?sslmode=verify-ca&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=VERIFY-FULL|jdbc:postgresql://localhost/shardshop?sslmode=VERIFY-FULL&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=&sslrootcert=|jdbc:postgresql://localhost/shardshop?sslmode=&sslrootcert=&sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=verify%2Dfull|jdbc:postgresql://localhost/shardshop?sslmode=verify%2Dfull&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=verify-full&sslrootcert=%2Fexplicit.crt|jdbc:postgresql://localhost/shardshop?sslmode=verify-full&sslrootcert=%2Fexplicit.crt",
            "jdbc:postgresql://localhost/shardshop?sslmode=disable|jdbc:postgresql://localhost/shardshop?sslmode=disable",
            "jdbc:postgresql://localhost/shardshop?sslmode=verify-full&sslmode=require|jdbc:postgresql://localhost/shardshop?sslmode=verify-full&sslmode=require"})
    void securesUrlsByDefaultAndKeepsExplicitTlsChoices(String url, String expected) {
        assertEquals(expected, JdbcUrls.secure(url, ROOT));
    }

    @Test
    void rejectsBlankTrustMaterialAndMalformedEncodingWithoutDisclosingTheUrl() {
        assertEquals("The root certificate must not be blank", assertThrows(IllegalArgumentException.class,
                () -> JdbcUrls.secure("jdbc:postgresql://localhost/shardshop", " ")).getMessage());
        var malformed = assertThrows(IllegalArgumentException.class,
                () -> JdbcUrls.secure("jdbc:postgresql://localhost/shardshop?sslmode=%private", ROOT));
        assertEquals("Invalid TLS parameter encoding", malformed.getMessage());
        assertFalse(malformed.getMessage().contains("private"));
    }

    @Test
    void appendsFiniteDriverLimitsAfterEveryUrlParameter() {
        assertEquals("jdbc:postgresql://a/b?connectTimeout=1&loginTimeout=1&socketTimeout=3&cancelSignalTimeout=1",
                JdbcUrls.withDriverLimits("jdbc:postgresql://a/b"));
        assertEquals("jdbc:postgresql://a/b?socketTimeout=0&connectTimeout=1&loginTimeout=1&socketTimeout=3"
                        + "&cancelSignalTimeout=1", JdbcUrls.withDriverLimits("jdbc:postgresql://a/b?socketTimeout=0"));
    }

    @Test
    void endpointsNeedAPostgresqlUrlAndAUserAndHideTheirValues() {
        assertThrows(NullPointerException.class, () -> new Endpoint(null, "user", "secret"));
        assertThrows(NullPointerException.class, () -> new Endpoint("jdbc:postgresql://localhost/a", null, "secret"));
        assertThrows(NullPointerException.class, () -> new Endpoint("jdbc:postgresql://localhost/a", "user", null));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("jdbc:mysql://localhost/a", "user", ""));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint("jdbc:postgresql://localhost/a", " ", ""));
        assertEquals("Endpoint[configured]", new Endpoint("jdbc:postgresql://localhost/a", "user", "secret").toString());
    }
}
