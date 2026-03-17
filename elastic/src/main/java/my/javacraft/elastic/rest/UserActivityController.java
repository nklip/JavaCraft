package my.javacraft.elastic.rest;

import co.elastic.clients.elasticsearch.core.*;
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
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
import my.javacraft.elastic.service.activity.UserActivityService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

@Slf4j
@RestController
@Validated
@Tag(name = "2. User activity", description = "API(s) for hit count services")
@RequestMapping(path = "/api/services/user-activity")
@RequiredArgsConstructor
public class UserActivityController {

    private final DateService dateService;
    private final UserActivityService userActivityService;
    private final UserActivityIngestionService userActivityIngestionService;

    @Operation(
            summary = "Capture user click",
            description = "Upsert - create a new hit count document or update(increment) the hit count."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @PostMapping(
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserClickResponse> captureUserClick(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "UserClick event",
                    useParameterTypeSchema = true,
                    content = @Content(schema = @Schema(
                            implementation = UserClick.class
                    ))
            )
            @RequestBody @Valid UserClick userClick) throws IOException {

        log.info("ingesting (UserClick = {})...", userClick);

        UserClickResponse userClickResponse = userActivityIngestionService.ingestUserClick(userClick, dateService.getCurrentDate());

        return ResponseEntity.ok().body(userClickResponse);
    }

    @Operation(
            summary = "Search activity by userId",
            description = "Fetch the search activity by userId"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/documents/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GetResponse<UserActivity>> getHitCount(
            @PathVariable("documentId") String documentId) throws IOException {

        log.info("executing getHitCount (documentId = '{}')...", documentId);

        GetResponse<UserActivity> map = userActivityService.getUserActivityByDocumentId(documentId);

        return ResponseEntity.ok().body(map);
    }
}
