package dev.nklip.javacraft.ewrs.app.integration;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.RebuildProjectionsResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.app.AbstractPostgresIntegrationTest;
import dev.nklip.javacraft.ewrs.app.service.WorkRequestCommandService;
import dev.nklip.javacraft.ewrs.events.Priority;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AdminProjectionControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WorkRequestCommandService workRequestCommandService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void rebuildEndpointReturnsProjectionCounts() {
        WorkRequestResponse created = workRequestCommandService.create(new CreateWorkRequest(
                "Roll certificates",
                Priority.CRITICAL,
                "PLATFORM-2026",
                20,
                "Nikita",
                "corr-1"
        ));
        workRequestCommandService.approve(created.requestId(), new ApproveWorkRequest("Lead", "corr-2"));

        ResponseEntity<RebuildProjectionsResponse> response =
                restTemplate.postForEntity("/api/v1/admin/projections/rebuild", null, RebuildProjectionsResponse.class);

        Assertions.assertAll(
                () -> Assertions.assertEquals(HttpStatus.OK, response.getStatusCode()),
                () -> Assertions.assertNotNull(response.getBody()),
                () -> {
                    Assertions.assertNotNull(response.getBody());
                    Assertions.assertEquals(2, response.getBody().eventsReplayed());
                },
                () -> {
                    Assertions.assertNotNull(response.getBody());
                    Assertions.assertEquals(1, response.getBody().requestsProjected());
                },
                () -> {
                    Assertions.assertNotNull(response.getBody());
                    Assertions.assertEquals(3, response.getBody().budgetsProjected());
                }
        );
    }
}
