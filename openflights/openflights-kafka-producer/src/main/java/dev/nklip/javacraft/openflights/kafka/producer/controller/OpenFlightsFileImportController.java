package dev.nklip.javacraft.openflights.kafka.producer.controller;

import dev.nklip.javacraft.openflights.kafka.producer.model.OpenFlightsImportResult;
import dev.nklip.javacraft.openflights.kafka.producer.service.OpenFlightsFileImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/openflights/import")
@Tag(name = "OpenFlights File Import")
public class OpenFlightsFileImportController {

    private final OpenFlightsFileImportService importService;

    @PostMapping("/airlines")
    @Operation(summary = "Read airlines.dat and publish airlines to Kafka")
    public ResponseEntity<OpenFlightsImportResult> importAirlines() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importService.importAirlines());
    }

    @PostMapping("/airports")
    @Operation(summary = "Read airports.dat and publish airports to Kafka")
    public ResponseEntity<OpenFlightsImportResult> importAirports() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importService.importAirports());
    }

    @PostMapping("/countries")
    @Operation(summary = "Read countries.dat and publish countries to Kafka")
    public ResponseEntity<OpenFlightsImportResult> importCountries() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importService.importCountries());
    }

    @PostMapping("/planes")
    @Operation(summary = "Read planes.dat and publish planes to Kafka")
    public ResponseEntity<OpenFlightsImportResult> importPlanes() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importService.importPlanes());
    }

    @PostMapping("/routes")
    @Operation(summary = "Read routes.dat and publish routes to Kafka")
    public ResponseEntity<OpenFlightsImportResult> importRoutes() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(importService.importRoutes());
    }
}
