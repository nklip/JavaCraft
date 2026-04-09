package dev.nklip.javacraft.openflights.app.model;

/**
 * High-level shape of a SQL execution result.
 *
 * <p>The admin SQL endpoint does not return pre-rendered HTML anymore. Instead
 * it returns structured JSON plus a coarse result type so the frontend can
 * decide which renderer to use:
 * <ul>
 *     <li>{@code TABLE}: query returned rows and column metadata</li>
 *     <li>{@code MESSAGE}: statement succeeded but produced no row set</li>
 *     <li>{@code ERROR}: validation or database execution failed</li>
 * </ul>
 * Keeping this as a dedicated enum makes the response contract explicit and
 * avoids stringly typed checks spread across the UI code.</p>
 */
public enum SqlQueryResultType {
    TABLE,
    MESSAGE,
    ERROR
}
