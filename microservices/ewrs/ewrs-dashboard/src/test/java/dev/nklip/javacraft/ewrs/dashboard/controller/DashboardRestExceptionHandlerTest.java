package dev.nklip.javacraft.ewrs.dashboard.controller;

import dev.nklip.javacraft.ewrs.api.shared.ErrorResponse;
import dev.nklip.javacraft.ewrs.dashboard.exception.DashboardWorkRequestNotFoundException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class DashboardRestExceptionHandlerTest {

    @Test
    void handleWorkRequestNotFoundReturnsSharedErrorPayload() {
        DashboardRestExceptionHandler handler = new DashboardRestExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/work-requests/1000/timeline");

        ResponseEntity<ErrorResponse> response = handler.handleWorkRequestNotFound(
                new DashboardWorkRequestNotFoundException("Work request 1000 does not exist in the event history"),
                new ServletWebRequest(request)
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()),
                () -> Assertions.assertNotNull(response.getBody()),
                () -> {
                    Assertions.assertNotNull(response.getBody());
                    Assertions.assertEquals("/api/v1/dashboard/work-requests/1000/timeline", response.getBody().path());
                },
                () -> {
                    Assertions.assertNotNull(response.getBody());
                    Assertions.assertEquals("Work request 1000 does not exist in the event history",
                            response.getBody().message());
                }
        );
    }
}
