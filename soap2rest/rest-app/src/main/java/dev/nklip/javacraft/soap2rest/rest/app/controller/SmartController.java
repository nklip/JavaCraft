package dev.nklip.javacraft.soap2rest.rest.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.soap2rest.rest.api.AsyncJobResultResponse;
import dev.nklip.javacraft.soap2rest.rest.api.Metrics;
import dev.nklip.javacraft.soap2rest.rest.app.service.MetricsQueryService;
import dev.nklip.javacraft.soap2rest.rest.app.service.async.AsyncJobState;
import dev.nklip.javacraft.soap2rest.rest.app.service.async.AsyncMetricsStorage;
import dev.nklip.javacraft.soap2rest.rest.app.service.SmartService;
import dev.nklip.javacraft.soap2rest.common.aop.ExecutionTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Data
@Slf4j
@RestController
@Tag(name = "Smart", description = "List of APIs for smart metrics")
@RequestMapping(path = "/api/v1/smart")
@RequiredArgsConstructor
public class SmartController {

    private final MetricsQueryService metricsQueryService;
    private final AsyncMetricsStorage asyncMetricsStorage;
    private final SmartService smartService;
    @Value("${soap2rest.rest.smart.message:Hello World!}")
    private String smartMessage;

    @ExecutionTime
    @Operation(
            summary = "Get default message",
            description = "API to get default message"
    )
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getDefault() {
        return ResponseEntity.ok(smartMessage);
    }

    @ExecutionTime
    @Operation(
            summary = "Get metrics by account id",
            description = "API to get metrics by account id"
    )
    @GetMapping(value = "/{id}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Metrics> getMetrics(@PathVariable Long id) {
        return ResponseEntity
                .ok(metricsQueryService.findByAccountId(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Get the LATEST metric by account id",
            description = "API to get the LATEST metric by account id"
    )
    @GetMapping(value = "/{id}/latest",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Metrics> getLatestMetrics(@PathVariable Long id) {
        return ResponseEntity
                .ok(metricsQueryService.findLatestMetrics(id));
    }

    @ExecutionTime
    @Operation(
            summary = "Create or update metrics for an account",
            description = "Stores the submitted gas and electric readings for the account from the path. " +
                    "When 'async=false' the request is processed immediately and returns a boolean result. " +
                    "When 'async=true' the request is accepted for background processing over JMS/Artemis and returns a polling request id."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Metrics were processed synchronously",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Boolean.class),
                            examples = @ExampleObject(value = "true")
                    )
            ),
            @ApiResponse(
                    responseCode = "202",
                    description = "Metrics were accepted for asynchronous processing; poll the async endpoint with the returned request id",
                    content = @Content(
                            mediaType = MediaType.TEXT_PLAIN_VALUE,
                            schema = @Schema(type = "string", example = "smart-req-123"),
                            examples = @ExampleObject(value = "smart-req-123")
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Unexpected processing error",
                    content = @Content
            )
    })
    @PutMapping(value = "/{id}",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<?> updateMetrics(
            @PathVariable("id") Long accountId,
            @RequestBody Metrics metrics,
            @RequestParam(name = "async", defaultValue = "false") boolean async) {
        if (async) {
            return ResponseEntity
                    .accepted()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(asyncMetricsStorage.submit(accountId, metrics));
        } else {
            return ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(smartService.submit(accountId, metrics));
        }
    }

    @ExecutionTime
    @Operation(
            summary = "Get smart async job result",
            description = "API to get async smart job result by request id"
    )
    @GetMapping(value = "/async/{requestId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AsyncJobResultResponse> getAsyncResult(
            @PathVariable("requestId") String requestId) {
        return asyncMetricsStorage.findResult(requestId)
                .map(this::toAsyncJobResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExecutionTime
    @Operation(
            summary = "Delete all metrics",
            description = "API to delete all metrics"
    )
    @DeleteMapping(value = "/{id}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Integer> deleteAllMetrics(@PathVariable("id") Long id) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(smartService.deleteAllByAccountId(id));
    }

    private ResponseEntity<AsyncJobResultResponse> toAsyncJobResponse(AsyncJobState asyncJobState) {
        return switch (asyncJobState.status()) {
            case ACCEPTED -> ResponseEntity.accepted()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(asyncJobState.toResponse());
            case COMPLETED -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(asyncJobState.toResponse());
            case FAILED -> ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(asyncJobState.toResponse());
        };
    }

}
