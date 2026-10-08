package dev.nklip.javacraft.shardshop.workload.reader.http;

/** The final outcome of one read, and its decoded value for {@link Outcome#OK}. */
public record ReadResult<T>(Outcome outcome, T value) {

    public static <T> ReadResult<T> ok(T value) {
        return new ReadResult<>(Outcome.OK, value);
    }

    public static <T> ReadResult<T> failed(Outcome outcome) {
        return new ReadResult<>(outcome, null);
    }

    public enum Outcome {
        /** A 200 response that matches the dataset definition and the issued IDs. */
        OK,
        /** 404: the product is missing, for example on a standby that has not replayed it yet. */
        NOT_FOUND,
        /** 503 READ_REPLICA_UNAVAILABLE: the replica profile has no available standby. */
        REPLICA_UNAVAILABLE,
        /** Another 503, for example CATALOG_UNAVAILABLE. */
        UNAVAILABLE,
        /** Another HTTP status. */
        HTTP_ERROR,
        /** A response body that is too large, is not valid JSON, or does not match the definition. */
        INVALID_RESPONSE,
        /** No result before the overall deadline, including the wait for a free connection. */
        DEADLINE,
        /** No HTTP response, for example a refused or reset connection, after the permitted attempts. */
        TRANSPORT
    }
}
