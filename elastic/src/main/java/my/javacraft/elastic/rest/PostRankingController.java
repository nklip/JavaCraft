package my.javacraft.elastic.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.model.PostPreview;
import my.javacraft.elastic.service.activity.HotService;
import my.javacraft.elastic.service.activity.TopService;
import my.javacraft.elastic.service.activity.UserActivityService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@Validated
@Tag(name = "3. Post ranking", description = "API(s) for ranked post feeds (hot, top by window)")
@RequestMapping(path = "/api/services/posts/ranking")
@RequiredArgsConstructor
public class PostRankingController {

    private final TopService topService;
    private final HotService hotService;

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

        return ResponseEntity.ok().body(hotService.retrieveHotPosts(size));
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

        return ResponseEntity.ok().body(topService.retrieveTopPosts(size));
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

        return ResponseEntity.ok().body(topService.retrieveTopPosts(size, tw));
    }
}
