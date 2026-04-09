package dev.nklip.javacraft.openflights.app.model;

/**
 * Request contract for the admin SQL console.
 *
 * <p>The browser sends the raw SQL statement together with the paging state that
 * is currently selected in the UI. The backend intentionally treats paging as a
 * first-class concern instead of letting the browser fetch the whole result set,
 * because the repository rewrites row-returning queries into count + page
 * statements so PostgreSQL returns only the requested slice.</p>
 *
 * <p>{@code page} and {@code pageSize} are nullable on purpose. The controller
 * accepts lightweight requests from the UI, and {@code SqlService} owns the
 * validation/defaulting rules.</p>
 */
public record SqlQueryRequest(String sql, Integer page, Integer pageSize) {
}
