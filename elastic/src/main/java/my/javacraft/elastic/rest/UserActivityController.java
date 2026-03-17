package my.javacraft.elastic.rest;

import co.elastic.clients.elasticsearch.core.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.model.UserClick;
import my.javacraft.elastic.model.UserClickResponse;
import my.javacraft.elastic.model.UserActivity;
import my.javacraft.elastic.service.DateService;
import my.javacraft.elastic.service.activity.UserActivityIngestionService;
import my.javacraft.elastic.service.activity.TopService;
import my.javacraft.elastic.service.activity.UserActivityService;
import my.javacraft.elastic.service.activity.HotService;
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
    private final TopService topService;
    private final HotService hotService;
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

    @Operation(
            summary = "Top posts — all time",
            description = "Returns globally top posts ranked by net score (upvotes − downvotes) across all time."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/top", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PostPreview>> retrieveTopPosts(
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(UserActivityService.MAX_VALUES) int size) throws IOException {

        log.info("retrieving top posts all-time (limit = '{}')...", size);

        List<PostPreview> posts = topService.retrieveTopPosts(size);

        return ResponseEntity.ok().body(posts);
    }

    @Operation(
            summary = "Top posts — by window",
            description = "Returns top posts ranked by net score (upvotes − downvotes) within a time window. "
                    + "Valid windows: day (24 h), week (7 d), month (30 d), year (365 d)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "400", description = "Invalid window value"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/top/{window}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PostPreview>> retrieveTopPostsByWindow(
            @PathVariable("window") String window,
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(UserActivityService.MAX_VALUES) int size) throws IOException {

        TopService.TopWindow tw;
        try {
            tw = TopService.TopWindow.valueOf(window.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("invalid top window '{}', must be one of: day, week, month, year", window);
            return ResponseEntity.badRequest().build();
        }

        log.info("retrieving top posts (window = '{}', limit = '{}')...", tw, size);

        List<PostPreview> posts = topService.retrieveTopPosts(size, tw);

        return ResponseEntity.ok().body(posts);
    }

    @Operation(
            summary = "Hot posts",
            description = "Returns globally hot posts ranked by exponential time-decay weighted net score."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/hot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PostPreview>> retrieveHotPosts(
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(UserActivityService.MAX_VALUES) int size) throws IOException {

        log.info("retrieving hot posts (limit = '{}')...", size);

        List<PostPreview> posts = hotService.retrieveHotPosts(size);

        return ResponseEntity.ok().body(posts);
    }

}
