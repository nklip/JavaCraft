package dev.nklip.javacraft.elastic.app.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.elastic.api.model.VoteRequest;
import dev.nklip.javacraft.elastic.api.model.VoteResponse;
import dev.nklip.javacraft.elastic.app.service.DateService;
import dev.nklip.javacraft.elastic.app.service.VoteService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

@Slf4j
@RestController
@Validated
@Tag(name = "2. User activity", description = "API(s) for user votes")
@RequestMapping(path = "/api/services/user-votes")
@RequiredArgsConstructor
public class VoteController {

    private final DateService dateService;
    private final VoteService voteService;

    @Operation(
            summary = "Process VoteRequest",
            description = """
            Karma change text table:
            ┌───┬──────────────┬────────────┬───────────┬───────┬─────────────────────┐
            │ N │ Prior state  │ New action │ ES Result │ Delta │ Math                │
            ├───┼──────────────┼────────────┼───────────┼───────┼─────────────────────┤
            │ 1 │ No document  │ UPVOTE     │ Created   │ +1    │ 0 -> +1             │
            │ 2 │ No document  │ DOWNVOTE   │ Created   │ -1    │ 0 -> -1             │
            │ 3 │ UPVOTE       │ UPVOTE     │ NoOp      │ 0     │ no write            │
            │ 4 │ DOWNVOTE     │ DOWNVOTE   │ NoOp      │ 0     │ no write            │
            │ 5 │ DOWNVOTE     │ UPVOTE     │ Updated   │ +2    │ -1 -> +1 = net +2   │
            │ 6 │ UPVOTE       │ DOWNVOTE   │ Updated   │ -2    │ +1 -> -1 = net -2   │
            │ 7 │ was UPVOTE   │ NOVOTE     │ Deleted   │ -1    │ +1 -> 0 = net -1    │
            │ 8 │ was DOWNVOTE │ NOVOTE     │ Deleted   │ +1    │ -1 -> 0 = net +1    │
            │ 9 │ No document  │ NOVOTE     │ NotFound  │ 0     │ nothing to undo     │
            └───┴──────────────┴────────────┴───────────┴───────┴─────────────────────┘
            """
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @PostMapping(
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VoteResponse> captureVoteRequest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "VoteRequest event",
                    useParameterTypeSchema = true,
                    content = @Content(schema = @Schema(
                            implementation = VoteRequest.class
                    ))
            )
            @RequestBody @Valid VoteRequest voteRequest) throws IOException {

        log.info("processing (VoteRequest = {})...", voteRequest);

        VoteResponse voteResponse = voteService.processVoteRequest(voteRequest, dateService.getCurrentDate());

        return ResponseEntity.ok().body(voteResponse);
    }

}
