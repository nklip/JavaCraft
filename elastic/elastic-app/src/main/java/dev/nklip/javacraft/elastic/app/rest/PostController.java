package dev.nklip.javacraft.elastic.app.rest;

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
import dev.nklip.javacraft.elastic.api.model.Post;
import dev.nklip.javacraft.elastic.api.model.PostRequest;
import dev.nklip.javacraft.elastic.app.service.PostService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@Validated
@Tag(name = "3. Posts", description = "API(s) for post submission")
@RequestMapping(path = "/api/services/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @Operation(
            summary = "Submit a new post",
            description = "Creates a post document with a server-generated ID and timestamp. " +
                          "Karma starts at 0; hotScore is initialised from the submission time."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Post created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request — authorUserId is blank"),
            @ApiResponse(responseCode = "406", description = "Resource unavailable")
    })
    @PostMapping(
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Post> createPost(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "PostRequest — author of the new post",
                    useParameterTypeSchema = true,
                    content = @Content(schema = @Schema(implementation = PostRequest.class))
            )
            @RequestBody @Valid PostRequest postRequest) throws IOException {

        log.info("processing (PostRequest = {})...", postRequest);

        Post post = postService.submitPost(postRequest.getAuthorUserId());

        return ResponseEntity.ok().body(post);
    }
}
