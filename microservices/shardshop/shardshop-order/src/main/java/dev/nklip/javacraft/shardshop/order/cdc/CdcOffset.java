package dev.nklip.javacraft.shardshop.order.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/** Shared strict validation of a Debezium partition and its durable PostgreSQL position. */
public final class CdcOffset {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CdcOffset() { }

    public static long position(String key, String value, String identity) {
        try {
            if (key == null || value == null || identity == null) {
                throw new IllegalArgumentException("offset data is malformed");
            }
            JsonNode partition = JSON.readTree(key);
            JsonNode offset = JSON.readTree(value);
            if (partition == null || !partition.isArray() || partition.size() != 2
                    || !identity.equals(partition.path(0).textValue())
                    || !partition.path(1).isObject() || partition.path(1).size() != 1
                    || !identity.equals(partition.path(1).path("server").textValue())) {
                throw new IllegalArgumentException("offset belongs to another connector");
            }
            if (offset == null || !usable(offset.path("lsn"))) {
                throw new IllegalArgumentException("offset has no usable source position");
            }
            JsonNode commit = offset.path("lsn_commit");
            if (!commit.isMissingNode() && !commit.isNull() && !usable(commit)) {
                throw new IllegalArgumentException("offset has no usable source position");
            }
            return usable(commit) ? commit.longValue() : offset.path("lsn").longValue();
        } catch (IOException malformed) {
            throw new IllegalArgumentException("offset data is malformed", malformed);
        }
    }

    private static boolean usable(JsonNode lsn) {
        return lsn.isIntegralNumber() && lsn.canConvertToLong() && lsn.longValue() > 0;
    }
}
