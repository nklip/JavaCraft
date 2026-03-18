package my.javacraft.elastic.rest;

import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.javacraft.elastic.service.AdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-time setup controller: creates Elasticsearch indexes and their field mappings.
 * <p>
 * Call these endpoints once before using {@link SearchController} or
 * {@link VoteController}. No user input is accepted — each endpoint
 * creates a specific, pre-defined index with a fixed schema.
 * <p>
 * Re-running an endpoint when the index already exists returns 201 and does
 * not modify the index.
 */
@Slf4j
@RestController
@Tag(name = "0. Admin", description = "One-time setup: create Elasticsearch indexes and field mappings")
@RequestMapping(path = "/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(
            summary = "Create posts index",
            description = "Creates the 'posts' index with typed field mappings: "
                    + "postId(keyword), createdAt(date). "
                    + "Required by NewRankingService for chronological 'New' feed ordering."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Index created successfully"),
            @ApiResponse(responseCode = "201", description = "Index already exists; no changes were made"),
            @ApiResponse(responseCode = "500", description = "Elasticsearch error")
    })
    @PutMapping(value = "/indexes/posts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateIndexResponse> createPostsIndex() throws IOException {
        log.info("request to create posts index");
        AdminService.IndexCreationResult result = adminService.createPostsIndex();
        return buildResponse(result);
    }

    @Operation(
            summary = "Create user-votes index",
            description = "Creates the 'user-votes' index with typed field mappings: "
                    + "timestamp(date), userId/postId/action(keyword). "
                    + "Required by VoteController for ingestion, retrieval, and trending queries."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Index created successfully"),
            @ApiResponse(responseCode = "201", description = "Index already exists; no changes were made"),
            @ApiResponse(responseCode = "500", description = "Elasticsearch error")
    })
    @PutMapping(value = "/indexes/user-votes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateIndexResponse> createUserVoteIndex() throws IOException {
        log.info("request to create user-votes index");
        AdminService.IndexCreationResult result = adminService.createUserVoteIndex();
        return buildResponse(result);
    }

    @Operation(
            summary = "Create books index",
            description = "Creates the 'books' index with text fields: name, author, synopsis. "
                    + "Required by SearchController for full-text search queries."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Index created successfully"),
            @ApiResponse(responseCode = "201", description = "Index already exists; no changes were made"),
            @ApiResponse(responseCode = "500", description = "Elasticsearch error")
    })
    @PutMapping(value = "/indexes/books", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateIndexResponse> createBooksIndex() throws IOException {
        log.info("request to create books index");
        AdminService.IndexCreationResult result = adminService.createBooksIndex();
        return buildResponse(result);
    }

    @Operation(
            summary = "Create movies index",
            description = "Creates the 'movies' index with text fields: name, director, synopsis. "
                    + "Required by SearchController for full-text search queries."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Index created successfully"),
            @ApiResponse(responseCode = "201", description = "Index already exists; no changes were made"),
            @ApiResponse(responseCode = "500", description = "Elasticsearch error")
    })
    @PutMapping(value = "/indexes/movies", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateIndexResponse> createMoviesIndex() throws IOException {
        log.info("request to create movies index");
        AdminService.IndexCreationResult result = adminService.createMoviesIndex();
        return buildResponse(result);
    }

    @Operation(
            summary = "Create music index",
            description = "Creates the 'music' index with text fields: band, album, name, lyrics. "
                    + "Required by SearchController for full-text search queries."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Index created successfully"),
            @ApiResponse(responseCode = "201", description = "Index already exists; no changes were made"),
            @ApiResponse(responseCode = "500", description = "Elasticsearch error")
    })
    @PutMapping(value = "/indexes/music", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateIndexResponse> createMusicIndex() throws IOException {
        log.info("request to create music index");
        AdminService.IndexCreationResult result = adminService.createMusicIndex();
        return buildResponse(result);
    }

    private ResponseEntity<CreateIndexResponse> buildResponse(AdminService.IndexCreationResult result) {
        HttpStatus status = result.created() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.response());
    }
}
