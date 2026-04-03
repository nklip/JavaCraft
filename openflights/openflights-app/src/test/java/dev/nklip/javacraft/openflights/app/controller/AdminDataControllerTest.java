package dev.nklip.javacraft.openflights.app.controller;

import dev.nklip.javacraft.openflights.app.model.OpenFlightsDataCleanupResult;
import dev.nklip.javacraft.openflights.app.service.AdminDataService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDataControllerTest {

    @Test
    void cleanDataReturnsOkResult() {
        AdminDataService adminDataService = mock(AdminDataService.class);
        AdminDataController controller = new AdminDataController(adminDataService);
        OpenFlightsDataCleanupResult expected = new OpenFlightsDataCleanupResult(1, 2, 3, 4, 5, 6);
        when(adminDataService.cleanPersistedData()).thenReturn(expected);

        ResponseEntity<OpenFlightsDataCleanupResult> response = controller.cleanData();

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }
}
