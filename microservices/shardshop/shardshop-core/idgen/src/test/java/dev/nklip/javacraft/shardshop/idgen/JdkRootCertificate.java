package dev.nklip.javacraft.shardshop.idgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;

/** One root certificate of the JDK trust store as PEM: a CA file that the client can load, not a trusted test server. */
public final class JdkRootCertificate {

    private JdkRootCertificate() {
    }

    public static String pem() throws GeneralSecurityException, IOException {
        KeyStore cacerts = KeyStore.getInstance(
                Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toFile(), (char[]) null);
        byte[] certificate = cacerts.getCertificate(cacerts.aliases().nextElement()).getEncoded();
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate)
                + "\n-----END CERTIFICATE-----\n";
    }
}
