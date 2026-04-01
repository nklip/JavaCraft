package dev.nklip.javacraft.soap2rest.rest.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.soap2rest.rest.api.Metric;
import dev.nklip.javacraft.soap2rest.rest.app.service.GasService;
import dev.nklip.javacraft.soap2rest.common.aop.ExecutionTime;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@Tag(name = "Gas", description = "List of APIs for gas metrics")
@RequestMapping(path = "/api/v1/smart/{id}/gas")
@RequiredArgsConstructor
public class GasController {

    private final GasService gasService;

    @ExecutionTime
    @Operation(
            summary = "Get gas metrics",
            description = "API to get gas metrics"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Metric>> getGasMetrics(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gasService.getMetricsByAccountId(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Get the LATEST gas metric",
            description = "API to get the LATEST gas metric"
    )
    @GetMapping(value = "/latest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Metric> getLatestGasMetric(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gasService.findLatestMetric(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Add a new gas metric",
            description = "API to add a new gas metric"
    )
    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Metric> putNewGasMetric(
            @PathVariable("id") Long id,
            @RequestBody Metric gasMetric) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gasService.submit(id, gasMetric));
    }

    @ExecutionTime
    @Operation(
            summary = "Delete all gas metrics",
            description = "API to delete all gas metrics"
    )
    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Integer> deleteAllGasMetrics(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gasService.deleteAllByAccountId(id));
    }
}
