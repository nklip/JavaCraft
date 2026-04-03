package dev.nklip.javacraft.openflights.api.kafka;

/**
 * Shared Kafka topic names for OpenFlights records.
 */
public final class OpenFlightsTopics {

    public static final String DEFAULT = "javacraft-kafka-topic";

    public static final String AIRLINE = "openflights.airline";
    public static final String AIRPORT = "openflights.airport";
    public static final String COUNTRY = "openflights.country";
    public static final String PLANE = "openflights.plane";
    public static final String ROUTE = "openflights.route";

    private OpenFlightsTopics() {
    }
}
