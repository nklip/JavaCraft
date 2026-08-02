package dev.nklip.javacraft.ewrs.app.controller;

import dev.nklip.javacraft.ewrs.api.command.ApproveWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CompleteWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.CreateWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.RejectWorkRequest;
import dev.nklip.javacraft.ewrs.api.command.StartWorkRequest;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestResponse;
import dev.nklip.javacraft.ewrs.api.query.WorkRequestTimelineEventResponse;
import dev.nklip.javacraft.ewrs.app.service.WorkRequestCommandService;
import dev.nklip.javacraft.ewrs.app.service.WorkRequestQueryService;
import dev.nklip.javacraft.ewrs.events.EventStatus;
import dev.nklip.javacraft.ewrs.events.Priority;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for work-request commands and queries.
 * Architecture mapping: this is the {@code client -> controller} hop from Runtime Topology, delegating to
 * {@link WorkRequestCommandService} for the Write Side and to {@link WorkRequestQueryService} for the Read Side.
 */
@RestController
@RequestMapping("/api/v1/work-requests")
public class WorkRequestController {

    private final WorkRequestCommandService workRequestCommandService;
    private final WorkRequestQueryService workRequestQueryService;

    public WorkRequestController(
            WorkRequestCommandService workRequestCommandService,
            WorkRequestQueryService workRequestQueryService
    ) {
        this.workRequestCommandService = workRequestCommandService;
        this.workRequestQueryService = workRequestQueryService;
    }

    @PostMapping
    public ResponseEntity<WorkRequestResponse> create(@Valid @RequestBody CreateWorkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workRequestCommandService.create(request));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<WorkRequestResponse> approve(
            @PathVariable("requestId") int requestId,
            @Valid @RequestBody ApproveWorkRequest request
    ) {
        return ResponseEntity.ok(workRequestCommandService.approve(requestId, request));
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<WorkRequestResponse> reject(
            @PathVariable("requestId") int requestId,
            @Valid @RequestBody RejectWorkRequest request
    ) {
        return ResponseEntity.ok(workRequestCommandService.reject(requestId, request));
    }

    @PostMapping("/{requestId}/start")
    public ResponseEntity<WorkRequestResponse> start(
            @PathVariable("requestId") int requestId,
            @Valid @RequestBody StartWorkRequest request
    ) {
        return ResponseEntity.ok(workRequestCommandService.start(requestId, request));
    }

    @PostMapping("/{requestId}/complete")
    public ResponseEntity<WorkRequestResponse> complete(
            @PathVariable("requestId") int requestId,
            @Valid @RequestBody CompleteWorkRequest request
    ) {
        return ResponseEntity.ok(workRequestCommandService.complete(requestId, request));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<WorkRequestResponse> getRequest(@PathVariable("requestId") int requestId) {
        return ResponseEntity.ok(workRequestQueryService.getRequest(requestId));
    }

    @GetMapping
    public ResponseEntity<List<WorkRequestResponse>> listRequests(
            @RequestParam(name = "status", required = false) EventStatus status,
            @RequestParam(name = "priority", required = false) Priority priority,
            @RequestParam(name = "budgetCode", required = false) String budgetCode
    ) {
        return ResponseEntity.ok(workRequestQueryService.listRequests(status, priority, budgetCode));
    }

    @GetMapping("/{requestId}/timeline")
    public ResponseEntity<List<WorkRequestTimelineEventResponse>> getTimeline(
            @PathVariable("requestId") int requestId
    ) {
        return ResponseEntity.ok(workRequestQueryService.getTimeline(requestId));
    }
}
