package dev.nklip.javacraft.openflights.app.repository;

import dev.nklip.javacraft.openflights.app.model.SqlQueryResult;
import dev.nklip.javacraft.openflights.app.model.SqlQueryResultType;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.stereotype.Repository;

/**
 * Repository responsible only for SQL execution and result extraction for the
 * admin SQL console.
 *
 * <p>This class exists because the SQL console needs direct PostgreSQL access,
 * but we still want to preserve the repo boundary from the rest of the
 * codebase: repositories do IO only. The repository therefore owns:
 * <ul>
 *     <li>SQL normalization such as trimming trailing semicolons</li>
 *     <li>deciding whether a statement should be paged at the database level</li>
 *     <li>executing count/page statements for row-returning queries</li>
 *     <li>extracting columns, rows, and update counts into a structured result</li>
 * </ul>
 *
 * <p>It intentionally does <strong>not</strong> know anything about HTML, CSS
 * classes, button labels, or page layout. That presentation work now lives in
 * the browser so the repository can stay a pure data-access component.</p>
 *
 * <p>For {@code select}/{@code with}/{@code values} statements we do not
 * execute the user query as-is and then slice in memory. Instead, we wrap it
 * twice:
 * <ul>
 *     <li>a {@code count(*)} wrapper to learn the total number of rows</li>
 *     <li>a {@code limit/offset} wrapper to fetch only the requested page</li>
 * </ul>
 * This keeps paging database-backed and avoids loading the whole result set
 * into the application just to show a small page in the UI.</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SqlRepository {

    private final JdbcTemplate jdbcTemplate;

    public SqlQueryResult executeSql(String sql, int page, int pageSize) {
        String normalizedSql = normalizeSql(sql);
        log.info("Executing SQL statement '{}'...", normalizedSql);
        if (isPagedResultQuery(normalizedSql)) {
            return executePagedResultQuery(normalizedSql, page, pageSize);
        }

        return jdbcTemplate.execute((StatementCallback<SqlQueryResult>) statement -> {
            boolean hasResultSet = statement.execute(normalizedSql);
            if (hasResultSet) {
                return buildTableResult(statement.getResultSet(), page, pageSize);
            }
            return buildMessageResult(
                    "Statement executed successfully. Updated rows: " + statement.getUpdateCount());
        });
    }

    private SqlQueryResult buildTableResult(ResultSet resultSet, int page, int pageSize) throws SQLException {
        // When a result set came directly from Statement.execute(...), we still
        // normalize it into the same structured contract as the paged path.
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = collectColumnLabels(metadata, columnCount);
        List<List<String>> rows = collectRows(resultSet, columnCount);
        int totalRows = rows.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalRows / pageSize));
        int effectivePage = Math.min(page, totalPages);
        int fromIndex = Math.min((effectivePage - 1) * pageSize, totalRows);
        int toIndex = Math.min(fromIndex + pageSize, totalRows);
        return buildTableResult(columns, rows.subList(fromIndex, toIndex), effectivePage, pageSize, totalRows, totalPages);
    }

    private SqlQueryResult executePagedResultQuery(String sql, int page, int pageSize) {
        // The UI needs both the current page slice and the global totals for the
        // toolbar. We therefore run a count query first, then fetch only the
        // requested window from PostgreSQL.
        int totalRows = countRows(sql);
        int totalPages = Math.max(1, (int) Math.ceil((double) totalRows / pageSize));
        int effectivePage = Math.min(page, totalPages);
        return jdbcTemplate.query(
                buildPagedSql(sql, effectivePage, pageSize),
                resultSet -> {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    int columnCount = metadata.getColumnCount();
                    List<String> columns = collectColumnLabels(metadata, columnCount);
                    List<List<String>> rows = collectRows(resultSet, columnCount);
                    return buildTableResult(columns, rows, effectivePage, pageSize, totalRows, totalPages);
                }
        );
    }

    private SqlQueryResult buildTableResult(
            List<String> columns,
            List<List<String>> pageRows,
            int page,
            int pageSize,
            int totalRows,
            int totalPages
    ) {
        return new SqlQueryResult(
                SqlQueryResultType.TABLE,
                columns,
                pageRows,
                page,
                pageSize,
                totalRows,
                totalPages,
                null
        );
    }

    private int countRows(String sql) {
        Integer rowCount = jdbcTemplate.queryForObject(buildCountSql(sql), Integer.class);
        return rowCount == null ? 0 : rowCount;
    }

    private List<String> collectColumnLabels(ResultSetMetaData metadata, int columnCount) throws SQLException {
        List<String> columns = new ArrayList<>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            columns.add(metadata.getColumnLabel(index));
        }
        return columns;
    }

    private List<List<String>> collectRows(ResultSet resultSet, int columnCount) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<String> row = new ArrayList<>(columnCount);
            for (int index = 1; index <= columnCount; index++) {
                Object value = resultSet.getObject(index);
                row.add(value == null ? "null" : String.valueOf(value));
            }
            rows.add(row);
        }
        return rows;
    }

    private SqlQueryResult buildMessageResult(String message) {
        return new SqlQueryResult(
                SqlQueryResultType.MESSAGE,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                message
        );
    }

    private boolean isPagedResultQuery(String sql) {
        String lowercaseSql = sql.toLowerCase(Locale.ROOT);
        return lowercaseSql.startsWith("select")
                || lowercaseSql.startsWith("with")
                || lowercaseSql.startsWith("values");
    }

    private String buildCountSql(String sql) {
        return "select count(*) from (" + sql + ") sql_result_count";
    }

    private String buildPagedSql(String sql, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return "select * from (" + sql + ") sql_result_page limit " + pageSize + " offset " + offset;
    }

    private String normalizeSql(String sql) {
        // Users often paste statements with a trailing semicolon. That is fine
        // for direct execution, but the wrapper queries in this repository need
        // a clean subquery body, so we strip trailing semicolons first.
        String normalizedSql = sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }
}
