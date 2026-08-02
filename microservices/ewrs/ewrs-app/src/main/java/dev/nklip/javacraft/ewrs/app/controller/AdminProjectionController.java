package dev.nklip.javacraft.ewrs.app.controller;

import dev.nklip.javacraft.ewrs.api.query.RebuildProjectionsResponse;
import dev.nklip.javacraft.ewrs.app.service.ProjectionCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative HTTP entry point for rebuilding disposable read models from event history.
 * Architecture mapping: triggers the synchronous replay path described in the Projection Flow and Read Side sections.
 */
@RestController
@RequestMapping("/api/v1/admin/projections")
public class AdminProjectionController {

    private final ProjectionCoordinator projectionCoordinator;

    public AdminProjectionController(ProjectionCoordinator projectionCoordinator) {
        this.projectionCoordinator = projectionCoordinator;
    }

    @PostMapping("/rebuild")
    public ResponseEntity<RebuildProjectionsResponse> rebuildProjections() {
        return ResponseEntity.ok(projectionCoordinator.rebuildProjections());
    }
}
