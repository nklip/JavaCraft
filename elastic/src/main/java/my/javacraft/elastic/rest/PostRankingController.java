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
import my.javacraft.elastic.config.Constants;
import my.javacraft.elastic.model.Post;
import my.javacraft.elastic.service.activity.BestRankingService;
import my.javacraft.elastic.service.activity.HotRankingService;
import my.javacraft.elastic.service.activity.NewRankingService;
import my.javacraft.elastic.service.activity.TopRankingService;
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

    private final TopRankingService topRankingService;
    private final HotRankingService hotRankingService;
    private final NewRankingService newRankingService;
    private final BestRankingService bestRankingService;

    @Operation(
            summary = "Best posts",
            description = "Returns posts ranked by Wilson score lower bound (95 % confidence interval "
                    + "on upvote ratio). Rewards posts that are reliably liked with a statistically "
                    + "significant number of votes. Equivalent to Reddit's 'Best' comment sort."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/best", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Post>> retrieveBestPosts(
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(Constants.MAX_VALUES) int size) throws IOException {

        log.info("retrieving best posts (limit = '{}')...", size);

        return ResponseEntity.ok().body(bestRankingService.retrieveBestPosts(size));
    }

    @Operation(
            summary = "New posts",
            description = "Returns the most recently submitted posts in reverse-chronological order."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/new", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Post>> retrieveNewPosts(
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(Constants.MAX_VALUES) int size) throws IOException {

        log.info("retrieving new posts (limit = '{}')...", size);

        return ResponseEntity.ok().body(newRankingService.retrieveNewPosts(size));
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
    public ResponseEntity<List<Post>> retrieveHotPosts(
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(Constants.MAX_VALUES) int size) throws IOException {

        log.info("retrieving hot posts (limit = '{}')...", size);

        return ResponseEntity.ok().body(hotRankingService.retrieveHotPosts(size));
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
    public ResponseEntity<List<Post>> retrieveTopPosts(
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(Constants.MAX_VALUES) int size) throws IOException {

        log.info("retrieving top posts all-time (limit = '{}')...", size);

        return ResponseEntity.ok().body(topRankingService.retrieveTopPosts(size));
    }

    @Operation(
            summary = "Top posts — by window",
            description = "Returns top posts ranked by net score within a time window. "
                    + "Valid windows: day (24 h), week (7 d), month (30 d), year (365 d)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful"),
            @ApiResponse(responseCode = "400", description = "Invalid window value"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @GetMapping(value = "/top/{window}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Post>> retrieveTopPostsByWindow(
            @PathVariable("window") String window,
            @RequestParam(required = false, name = "size", defaultValue = "10")
            @Min(1) @Max(Constants.MAX_VALUES) int size) throws IOException {

        TopRankingService.TopWindow tw;
        try {
            tw = TopRankingService.TopWindow.valueOf(window.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("invalid top window '{}', must be one of: day, week, month, year", window);
            return ResponseEntity.badRequest().build();
        }

        log.info("retrieving top posts (window = '{}', limit = '{}')...", tw, size);

        return ResponseEntity.ok().body(topRankingService.retrieveTopPosts(size, tw));
    }
}
