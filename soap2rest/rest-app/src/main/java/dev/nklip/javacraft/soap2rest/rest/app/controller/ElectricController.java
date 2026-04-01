package dev.nklip.javacraft.soap2rest.rest.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.service.ElectricService;
import dev.nklip.javacraft.soap2rest.common.aop.ExecutionTime;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@Tag(name = "Electric", description = "List of APIs for electric metrics")
@RequestMapping(path = "/api/v1/smart/{id}/electric")
@RequiredArgsConstructor
public class ElectricController {

    private final ElectricService electricService;

    @ExecutionTime
    @Operation(
            summary = "Get electric metrics",
            description = "API to get electric metrics"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Metric>> getElectricMetrics(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(electricService.getMetricsByAccountId(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Get the LATEST electric metric",
            description = "API to get the LATEST electric metric"
    )
    @GetMapping(value = "/latest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Metric> getLatestElectricMetric(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(electricService.findLatestMetric(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Add a new electric metric",
            description = "API to add a new electric metric"
    )
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Metric> putNewElectricMetric(
            @PathVariable("id") Long id,
            @RequestBody Metric metric) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(electricService.submit(id, metric));
    }

    @ExecutionTime
    @Operation(
            summary = "Delete all electric metrics",
            description = "API to delete all electric metrics"
    )
    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Integer> deleteAllElectricMetrics(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(electricService.deleteAllByAccountId(id));
    }

}
