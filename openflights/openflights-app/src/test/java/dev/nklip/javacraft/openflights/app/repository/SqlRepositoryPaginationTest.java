package dev.nklip.javacraft.openflights.app.repository;

import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResultType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.StatementCallback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SqlRepositoryPaginationTest {

    @Test
    void executeSqlUsesWrappedCountAndPagedQueriesForSelectStatements() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlRepository sqlRepository = new SqlRepository(jdbcTemplate);
        String expectedCountSql = "select count(*) from (select id from country) sql_result_count";
        String expectedPagedSql = "select * from (select id from country) sql_result_page limit 10 offset 90";
        when(jdbcTemplate.queryForObject(expectedCountSql, Integer.class)).thenReturn(120);
        stubPagedQuery(jdbcTemplate, expectedPagedSql, singleRowResultSet("id", 91));

        SqlQueryResult result = sqlRepository.executeSql("select id from country", 10, 10);

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("id"), result.columns());
        Assertions.assertEquals(List.of(List.of("91")), result.rows());
        Assertions.assertEquals(10, result.page());
        Assertions.assertEquals(10, result.pageSize());
        Assertions.assertEquals(120, result.totalRows());
        Assertions.assertEquals(12, result.totalPages());
        Assertions.assertNull(result.message());
        verify(jdbcTemplate).queryForObject(expectedCountSql, Integer.class);
        verify(jdbcTemplate).query(eq(expectedPagedSql), ArgumentMatchers.<ResultSetExtractor<SqlQueryResult>>any());
        verify(jdbcTemplate, never()).execute(any(StatementCallback.class));
    }

    @Test
    void executeSqlRemovesTrailingSemicolonBeforeWrappingSelectStatements() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlRepository sqlRepository = new SqlRepository(jdbcTemplate);
        String expectedCountSql = "select count(*) from (select id from country) sql_result_count";
        String expectedPagedSql = "select * from (select id from country) sql_result_page limit 50 offset 0";
        when(jdbcTemplate.queryForObject(expectedCountSql, Integer.class)).thenReturn(0);
        stubPagedQuery(jdbcTemplate, expectedPagedSql, emptyResultSet("id"));

        SqlQueryResult result = sqlRepository.executeSql("select id from country;   ", 1, 50);

        Assertions.assertEquals(SqlQueryResultType.TABLE, result.type());
        Assertions.assertEquals(List.of("id"), result.columns());
        Assertions.assertEquals(List.of(), result.rows());
        Assertions.assertEquals(1, result.page());
        Assertions.assertEquals(50, result.pageSize());
        Assertions.assertEquals(0, result.totalRows());
        Assertions.assertEquals(1, result.totalPages());
        Assertions.assertNull(result.message());
        verify(jdbcTemplate).queryForObject(expectedCountSql, Integer.class);
        verify(jdbcTemplate).query(eq(expectedPagedSql), ArgumentMatchers.<ResultSetExtractor<SqlQueryResult>>any());
        verify(jdbcTemplate, never()).execute(any(StatementCallback.class));
    }

    private ResultSet singleRowResultSet(String columnLabel, Object value) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = resultSetMetaData(columnLabel);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(value);
        return resultSet;
    }

    private ResultSet emptyResultSet(String columnLabel) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = resultSetMetaData(columnLabel);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(resultSet.next()).thenReturn(false);
        return resultSet;
    }

    private ResultSetMetaData resultSetMetaData(String columnLabel) throws SQLException {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(columnLabel);
        return metadata;
    }

    private void stubPagedQuery(JdbcTemplate jdbcTemplate, String sql, ResultSet resultSet) {
        doAnswer(invocation -> {
            ResultSetExtractor<SqlQueryResult> extractor = invocation.getArgument(1);
            return extractor.extractData(resultSet);
        }).when(jdbcTemplate).query(eq(sql), anyResultSetExtractor());
    }

    private ResultSetExtractor<SqlQueryResult> anyResultSetExtractor() {
        return ArgumentMatchers.any(ResultSetExtractor.class);
    }
}
