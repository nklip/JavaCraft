package dev.nklip.javacraft.openflights.app.model;

import java.util.List;

/**
 * Structured response returned by the admin SQL endpoint.
 *
 * <p>The important design choice here is that this record carries data, not
 * presentation. That keeps HTML generation out of the repository layer and
 * makes the boundary much cleaner:
 * <ul>
 *     <li>repository: execute SQL and extract tabular/message data</li>
 *     <li>service: validate paging inputs and orchestrate execution</li>
 *     <li>controller: expose the JSON contract over HTTP</li>
 *     <li>frontend: render the table, toolbar, paging controls, and messages</li>
 * </ul>
 *
 * <p>Only the fields relevant to the current {@code type} are populated.
 * Table results include columns, rows, and paging metadata. Message/error
 * results use {@code message} and leave the tabular fields empty or null.</p>
 */
public record SqlQueryResult(
        SqlQueryResultType type,
        List<String> columns,
        List<List<String>> rows,
        Integer page,
        Integer pageSize,
        Integer totalRows,
        Integer totalPages,
        String message
) {
}
