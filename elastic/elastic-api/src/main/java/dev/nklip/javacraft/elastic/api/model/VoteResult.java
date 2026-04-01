package dev.nklip.javacraft.elastic.api.model;

/**
 * API-level result of vote processing.
 * Detached from Elasticsearch transport enums.
 */
public enum VoteResult {
    Created,
    Updated,
    NoOp,
    Deleted,
    NotFound
}

