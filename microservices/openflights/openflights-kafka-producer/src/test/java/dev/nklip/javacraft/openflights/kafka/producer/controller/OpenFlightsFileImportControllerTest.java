package dev.nklip.javacraft.openflights.kafka.producer.controller;

import dev.nklip.javacraft.openflights.kafka.producer.model.OpenFlightsImportResult;
import dev.nklip.javacraft.openflights.kafka.producer.service.OpenFlightsFileImportService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenFlightsFileImportControllerTest {

    @Test
    void importCountriesReturnsCompletedResult() {
        OpenFlightsFileImportService importService = mock(OpenFlightsFileImportService.class);
        OpenFlightsFileImportController controller = new OpenFlightsFileImportController(importService);
        OpenFlightsImportResult expected = new OpenFlightsImportResult("countries", 3);
        when(importService.importCountries()).thenReturn(expected);

        ResponseEntity<OpenFlightsImportResult> response = controller.importCountries();

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }

    @Test
    void importAirlinesReturnsCompletedResult() {
        OpenFlightsFileImportService importService = mock(OpenFlightsFileImportService.class);
        OpenFlightsFileImportController controller = new OpenFlightsFileImportController(importService);
        OpenFlightsImportResult expected = new OpenFlightsImportResult("airlines", 3);
        when(importService.importAirlines()).thenReturn(expected);

        ResponseEntity<OpenFlightsImportResult> response = controller.importAirlines();

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }

    @Test
    void importAirportsReturnsCompletedResult() {
        OpenFlightsFileImportService importService = mock(OpenFlightsFileImportService.class);
        OpenFlightsFileImportController controller = new OpenFlightsFileImportController(importService);
        OpenFlightsImportResult expected = new OpenFlightsImportResult("airports", 3);
        when(importService.importAirports()).thenReturn(expected);

        ResponseEntity<OpenFlightsImportResult> response = controller.importAirports();

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }

    @Test
    void importPlanesReturnsCompletedResult() {
        OpenFlightsFileImportService importService = mock(OpenFlightsFileImportService.class);
        OpenFlightsFileImportController controller = new OpenFlightsFileImportController(importService);
        OpenFlightsImportResult expected = new OpenFlightsImportResult("planes", 3);
        when(importService.importPlanes()).thenReturn(expected);

        ResponseEntity<OpenFlightsImportResult> response = controller.importPlanes();

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }

    @Test
    void importRoutesReturnsCompletedResult() {
        OpenFlightsFileImportService importService = mock(OpenFlightsFileImportService.class);
        OpenFlightsFileImportController controller = new OpenFlightsFileImportController(importService);
        OpenFlightsImportResult expected = new OpenFlightsImportResult("routes", 3);
        when(importService.importRoutes()).thenReturn(expected);

        ResponseEntity<OpenFlightsImportResult> response = controller.importRoutes();

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }
}
