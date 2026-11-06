package dev.nklip.javacraft.shardshop.testing.cluster.drill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpWriterTest {
    @Test
    void classifiesApplicationFailuresWithoutTheirPrivateMessageOrAdditionalFields() {
        for (String code : List.of("ORDER_STORE_UNAVAILABLE", "ID_GENERATION_UNAVAILABLE", "CATALOG_UNAVAILABLE")) {
            assertEquals("contentType=application/json; origin=application; code=" + code,
                    HttpWriter.failureDetails(" Application/JSON; charset=UTF-8 ",
                            "{\"code\":\"" + code + "\",\"message\":\"secret-token\",\"buyer\":\"private-buyer\"}"));
        }
    }

    @Test
    void distinguishesKubernetesProxyStatusWithoutPrintingItsUpstreamUrl() {
        assertEquals("contentType=application/json; origin=kubernetes; kind=Status; reason=ServiceUnavailable",
                HttpWriter.failureDetails("application/json", """
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
                    HttpWriter.failureDetails("application/json", body));
        }
        assertEquals("contentType=other; origin=unknown; detail=redacted",
                HttpWriter.failureDetails("private-secret/header", "{\"code\":\"ORDER_STORE_UNAVAILABLE\"}"));
        assertEquals("contentType=text/html; origin=unknown; detail=redacted",
                HttpWriter.failureDetails("text/html; charset=UTF-8", "<html>private-secret</html>"));
    }
}
