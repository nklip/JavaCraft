package my.javacraft.elastic.rest;

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
import my.javacraft.elastic.model.VoteRequest;
import my.javacraft.elastic.model.VoteResponse;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.activity.VoteService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

@Slf4j
@RestController
@Validated
@Tag(name = "2. User activity", description = "API(s) for hit count services")
@RequestMapping(path = "/api/services/user-vote")
@RequiredArgsConstructor
public class VoteController {

    private final DateService dateService;
    private final VoteService voteService;

    @Operation(
            summary = "Process VoteRequest",
            description = """
            ┌──────────┬──────────┬──────────┐
            │ Status   │ Action   │ Result   │
            ├──────────┼──────────┼──────────┤
            │ NOTHING  │ UPVOTE   │ UPVOTE   │
            │ UPVOTE   │ UPVOTE   │ UPVOTE   │
            │ DOWNVOTE │ UPVOTE   │ UPVOTE   │
            │ UPVOTE   │ DOWNVOTE │ DOWNVOTE │
            │ NOTHING  │ DOWNVOTE │ DOWNVOTE │
            │ DOWNVOTE │ DOWNVOTE │ DOWNVOTE │
            │ DOWNVOTE │ UPVOTE   │ UPVOTE   │
            └──────────┴──────────┴──────────┘
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
