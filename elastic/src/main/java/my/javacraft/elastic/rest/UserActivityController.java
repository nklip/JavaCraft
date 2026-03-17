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
import my.javacraft.elastic.model.UserPostEvent;
import my.javacraft.elastic.model.UserPostEventResponse;
import my.javacraft.elastic.service.DateService;
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
    public ResponseEntity<UserPostEventResponse> captureUserPostEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "UserPostEvent event",
                    useParameterTypeSchema = true,
                    content = @Content(schema = @Schema(
                            implementation = UserPostEvent.class
                    ))
            )
            @RequestBody @Valid UserPostEvent userPostEvent) throws IOException {

        log.info("ingesting (UserPostEvent = {})...", userPostEvent);

        UserPostEventResponse userPostEventResponse = userActivityService.ingestUserEvent(
                userPostEvent, dateService.getCurrentDate()
        );

        return ResponseEntity.ok().body(userPostEventResponse);
    }

}
