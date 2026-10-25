package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KindHttpWriterTest {
    @Test
    void classifiesApplicationFailuresWithoutTheirPrivateMessageOrAdditionalFields() {
        for (String code : List.of("ORDER_STORE_UNAVAILABLE", "ID_GENERATION_UNAVAILABLE", "CATALOG_UNAVAILABLE")) {
            assertEquals("contentType=application/json; origin=application; code=" + code,
                    KindHttpWriter.failureDetails(" Application/JSON; charset=UTF-8 ",
                            "{\"code\":\"" + code + "\",\"message\":\"secret-token\",\"buyer\":\"private-buyer\"}"));
        }
    }

    @Test
    void distinguishesKubernetesProxyStatusWithoutPrintingItsUpstreamUrl() {
        assertEquals("contentType=application/json; origin=kubernetes; kind=Status; reason=ServiceUnavailable",
                KindHttpWriter.failureDetails("application/json", """
                        {"kind":"Status","reason":"ServiceUnavailable","code":503,
                         "message":"error trying to reach http://private-host/private-buyer?token=secret"}
                        """));
    }

    @Test
    void malformedOversizedAndUnrecognizedResponsesRemainRedacted() {
        for (String body : List.of("private-secret", "{\"code\":\"secret-token\"}",
                "{\"kind\":\"Status\",\"reason\":\"private-host\"}",
                "{\"code\":\"ORDER_STORE_UNAVAILABLE\",\"code\":\"private-secret\"}",
                "{\"code\":\"ORDER_STORE_UNAVAILABLE\"} private-secret",
                "{\"code\":\"ORDER_STORE_UNAVAILABLE\",\"message\":\"" + "x".repeat(4096) + "\"}")) {
            assertEquals("contentType=application/json; origin=unknown; detail=redacted",
                    KindHttpWriter.failureDetails("application/json", body));
        }
        assertEquals("contentType=other; origin=unknown; detail=redacted",
                KindHttpWriter.failureDetails("private-secret/header", "{\"code\":\"ORDER_STORE_UNAVAILABLE\"}"));
        assertEquals("contentType=text/html; origin=unknown; detail=redacted",
                KindHttpWriter.failureDetails("text/html; charset=UTF-8", "<html>private-secret</html>"));
    }
}
