package dev.nklip.javacraft.shardshop.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

class Sha256Test {

    // Expected complete digests were calculated independently with Python hashlib.
    @ParameterizedTest
    @CsvSource({
            "'', e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "1, 6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b",
            "2, d4735e3a265e16eee03f59718b9b5d03019c07d8b6c51f90da3a666eec13ab35",
            "01, 938db8c9f82c8cb58d3f3ef4fd250036a48d26a712753d2fde5abd03a85cabf4",
            "' 1 ', c0527d0a6cc99395deabfa3f67d9926fc6fa0b06e3148b74e230244e8e3ee183",
            "9007199254740993, a1c367c29158357e62a3ff5d3e800fb7698a22396439dbc0a9d4929322afd35d",
            "9223372036854775807, b34a1c30a715f6bf8b7243afa7fab883ce3612b7231716bdcbbdc1982e1aed29",
            "café, 850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e",
            "💡, 8aa6471e16db04d6e830407969d34374b8d62f1280c3ccae3277f6c79d97dac3"
    })
    void hashesExactUtf8TextToTheFullUnsignedDigest(String text, String expectedHex) {
        assertEquals(new BigInteger(expectedHex, 16), Sha256.unsignedDigest(text));
    }

    @Test
    void rejectsMissingText() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> Sha256.unsignedDigest(null));

        assertEquals("Hash input must not be null", exception.getMessage());
    }

    @Test
    void failsWhenSha256IsUnavailable() {
        NoSuchAlgorithmException missing = new NoSuchAlgorithmException("SHA-256");
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256")).thenThrow(missing);

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> Sha256.unsignedDigest("1"));

            assertEquals("SHA-256 is not available", exception.getMessage());
            assertSame(missing, exception.getCause());
        }
    }
}
