package dev.nklip.javacraft.shardshop.testing.aws;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The login check must find the copied address in the OAuth2 state of Envoy, also with its query string. */
class EksWebpagesTest {
    private static final String COPIED = "https://d111111abcdef8.cloudfront.net/products/?region=EU&page=3";

    @Test
    void readsTheOriginalAddressFromABase64UrlJsonState() {
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"url\":\"" + COPIED + "\",\"csrf_token\":\"abc.def\"}").getBytes(StandardCharsets.UTF_8));
        assertEquals(COPIED, EksWebpages.originalUrl(state));
    }

    @Test
    void readsTheOriginalAddressFromAFormEncodedState() {
        assertEquals(COPIED, EksWebpages.originalUrl("url=https%3A%2F%2Fd111111abcdef8.cloudfront.net%2Fproducts%2F%3Fregion%3DEU%26page%3D3&nonce=1"));
    }

    @Test
    void anUnknownStateGivesNoAddress() {
        assertEquals("", EksWebpages.originalUrl("not-a-state"));
        assertEquals("", EksWebpages.originalUrl(null));
    }

    @Test
    void decodesTheQueryOfTheLoginAddress() {
        assertEquals("https://h/oauth2/callback", EksWebpages.query("client_id=a&redirect_uri=https%3A%2F%2Fh%2Foauth2%2Fcallback")
                .get("redirect_uri"));
    }
}
