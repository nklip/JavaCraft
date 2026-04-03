package dev.nklip.javacraft.openflights.app.controller;

import dev.nklip.javacraft.openflights.app.model.OpenFlightsDataCleanupResult;
import dev.nklip.javacraft.openflights.app.service.AdminDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/openflights/admin/data")
@Tag(name = "OpenFlights Admin Data")
public class AdminDataController {

    private final AdminDataService adminDataService;

    @DeleteMapping
    @Operation(summary = "Delete all persisted OpenFlights data from PostgreSQL")
    public ResponseEntity<OpenFlightsDataCleanupResult> cleanData() {
        return ResponseEntity.ok(adminDataService.cleanPersistedData());
    }
}
