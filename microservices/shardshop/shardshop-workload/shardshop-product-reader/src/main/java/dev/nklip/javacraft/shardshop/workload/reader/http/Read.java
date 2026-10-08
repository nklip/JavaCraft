package dev.nklip.javacraft.shardshop.workload.reader.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.function.Function;

/** One GET path on the product Service and the decoder that checks its response. */
public record Read<T>(String path, Function<JsonNode, T> decoder) {
    public Read {
        Objects.requireNonNull(path);
        Objects.requireNonNull(decoder);
    }
}
