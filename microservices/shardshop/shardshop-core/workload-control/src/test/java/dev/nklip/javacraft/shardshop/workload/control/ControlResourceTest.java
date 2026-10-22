package dev.nklip.javacraft.shardshop.workload.control;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlResourceTest {
    private static final WorkloadControl.Settings READER = new WorkloadControl.Settings("product-reader",
            "product read starts/s", new BigDecimal("200"), new BigDecimal("10000"), new BigDecimal("10"), 16,
            List.of("reads"));

    @Test
    void appliesValidUpdatesAndReturnsTheResultingStatus() {
        ControlResource resource = new ControlResource(new WorkloadControl(READER, WorkloadControl.Phase.ACTIVE));
        assertEquals("12.25", resource.rate("{\"rate\": 12.25}").targetRate().toPlainString());
        assertEquals("300", resource.rate("{\"rate\":3e2}").targetRate().toPlainString());
        assertEquals("0", resource.rate(" {\"rate\": -0.0} ").targetRate().toPlainString());
        assertEquals("PAUSED", resource.pause().state());
        assertEquals("PAUSED", resource.resume().state(), "A zero target stays paused after Resume");
        assertEquals(5, resource.status().revision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "{}", "{\"rate\": 1, \"rate\": 2}", "{\"rate\": 1, \"other\": 2}",
            "{\"Rate\": 1}", "{\"rate\": 1} {}", "{\"rate\": 1", "{\"rate\": NaN}", "{\"rate\": Infinity}", "rate=1",
            "{\"rate\": 01}"})
    void malformedBodiesAreInvalidRequests(String body) {
        assertInvalid(body, ControlFailure.Reason.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"rate\": \"5\"}", "{\"rate\": null}", "{\"rate\": true}", "{\"rate\": {}}",
            "{\"rate\": -1}", "{\"rate\": 10000.001}", "{\"rate\": 1.0001}", "{\"rate\": 1e400}"})
    void numbersOutsideTheBoundsAndOtherValuesAreInvalidRates(String body) {
        assertInvalid(body, ControlFailure.Reason.INVALID_RATE);
    }

    @Test
    void aMissingBodyIsAnInvalidRequest() {
        assertSame(ControlFailure.Reason.INVALID_REQUEST,
                assertThrows(ControlFailure.class, () -> ControlResource.requestedRate(null)).reason());
    }

    @Test
    void mapsFailuresToStableJsonErrors() {
        ControlFailureMapper mapper = new ControlFailureMapper();
        assertError(mapper.toResponse(new ControlFailure(ControlFailure.Reason.INVALID_REQUEST)), 400, "INVALID_REQUEST");
        assertError(mapper.toResponse(new ControlFailure(ControlFailure.Reason.INVALID_RATE)), 400, "INVALID_RATE");
        assertError(mapper.toResponse(new ControlFailure(ControlFailure.Reason.RUN_ACTIVE)), 409, "RUN_ACTIVE");
        ControlWebExceptionMapper web = new ControlWebExceptionMapper();
        assertError(web.toResponse(new WebApplicationException(400)), 400, "INVALID_REQUEST");
        assertEquals(415, web.toResponse(new WebApplicationException(415)).getStatus(), "Other statuses pass through");
    }

    private static void assertInvalid(String body, ControlFailure.Reason reason) {
        WorkloadControl control = new WorkloadControl(READER, WorkloadControl.Phase.ACTIVE);
        ControlResource resource = new ControlResource(control);
        ControlFailure failure = assertThrows(ControlFailure.class, () -> resource.rate(body));
        assertSame(reason, failure.reason());
        assertEquals("200", control.status().targetRate().toPlainString(), "The previous setting is unchanged");
        assertEquals(0, control.status().revision());
    }

    private static void assertError(Response response, int status, String code) {
        assertEquals(status, response.getStatus());
        assertEquals("application/json", response.getMediaType().toString());
        assertEquals(code, ((ControlFailureMapper.Error) response.getEntity()).code());
    }
}
