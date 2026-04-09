package dev.nklip.javacraft.openflights.app.controller;

import dev.nklip.javacraft.openflights.app.model.SqlQueryRequest;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResultType;
import dev.nklip.javacraft.openflights.app.service.SqlService;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlControllerTest {

    @Test
    void executeReturnsOkResult() {
        SqlService sqlService = mock(SqlService.class);
        SqlController controller = new SqlController(sqlService);
        SqlQueryRequest request = new SqlQueryRequest("select 1", 2, 20);
        SqlQueryResult expected = new SqlQueryResult(
                SqlQueryResultType.TABLE,
                List.of("id"),
                List.of(List.of("1")),
                2,
                20,
                21,
                2,
                null
        );
        when(sqlService.executeSql(request.sql(), request.page(), request.pageSize())).thenReturn(expected);

        ResponseEntity<SqlQueryResult> response = controller.execute(request);

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals(expected, response.getBody());
    }

    @Test
    void executeReturnsBadRequestForBlankSql() {
        SqlService sqlService = mock(SqlService.class);
        SqlController controller = new SqlController(sqlService);
        SqlQueryRequest request = new SqlQueryRequest(" ", 1, 50);
        when(sqlService.executeSql(request.sql(), request.page(), request.pageSize()))
                .thenThrow(new IllegalArgumentException("SQL query must not be blank."));

        ResponseEntity<SqlQueryResult> response = controller.execute(request);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(
                new SqlQueryResult(
                        SqlQueryResultType.ERROR,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        "SQL query must not be blank."
                ),
                response.getBody()
        );
    }

    @Test
    void executeReturnsBadRequestForDatabaseErrors() {
        SqlService sqlService = mock(SqlService.class);
        SqlController controller = new SqlController(sqlService);
        SqlQueryRequest request = new SqlQueryRequest("select nope", 1, 50);
        when(sqlService.executeSql(request.sql(), request.page(), request.pageSize()))
                .thenThrow(new BadSqlGrammarException("execute", request.sql(), new java.sql.SQLException("bad sql")));

        ResponseEntity<SqlQueryResult> response = controller.execute(request);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(
                new SqlQueryResult(
                        SqlQueryResultType.ERROR,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        "bad sql"
                ),
                response.getBody()
        );
    }
}
