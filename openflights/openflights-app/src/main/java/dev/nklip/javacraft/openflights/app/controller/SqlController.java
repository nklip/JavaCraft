package dev.nklip.javacraft.openflights.app.controller;

import dev.nklip.javacraft.openflights.app.model.SqlQueryRequest;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResultType;
import dev.nklip.javacraft.openflights.app.service.SqlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for the admin SQL console.
 *
 * <p>This controller intentionally stays thin:
 * <ul>
 *     <li>accept the raw JSON request from the browser</li>
 *     <li>delegate execution/validation to {@code SqlService}</li>
 *     <li>translate validation and database failures into structured
 *     {@code ERROR} responses</li>
 * </ul>
 *
 * <p>The browser receives a structured {@code SqlQueryResult} and renders the
 * table or message locally. That keeps presentation concerns out of the server
 * layers while still giving the UI enough metadata to rebuild paging controls
 * and result summaries.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/openflights/admin/sql")
@Tag(name = "OpenFlights SQL management")
public class SqlController {

    private final SqlService sqlService;

    @PostMapping
    @Operation(summary = "Execute a raw SQL statement against the OpenFlights PostgreSQL database")
    public ResponseEntity<SqlQueryResult> execute(@RequestBody SqlQueryRequest request) {
        try {
            SqlQueryResult result = sqlService.executeSql(request.sql(), request.page(), request.pageSize());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity
                    .badRequest()
                    .body(new SqlQueryResult(
                            SqlQueryResultType.ERROR,
                            List.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            null,
                            exception.getMessage()
                    ));
        } catch (DataAccessException exception) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new SqlQueryResult(
                            SqlQueryResultType.ERROR,
                            List.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            null,
                            exception.getMostSpecificCause().getMessage()
                    ));
        }
    }
}
