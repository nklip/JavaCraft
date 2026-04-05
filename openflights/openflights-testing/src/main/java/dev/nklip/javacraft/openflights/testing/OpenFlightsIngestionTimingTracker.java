package dev.nklip.javacraft.openflights.testing;

public final class OpenFlightsIngestionTimingTracker {

    private final OpenFlightsDatabaseStatus expectedStatus;
    private final long startedAtMillis;

    private long countriesReachedAtMillis = -1L;
    private long airlinesReachedAtMillis = -1L;
    private long airportsReachedAtMillis = -1L;
    private long planesReachedAtMillis = -1L;
    private long routesReachedAtMillis = -1L;
    private long routeEquipmentCodesReachedAtMillis = -1L;

    public OpenFlightsIngestionTimingTracker(OpenFlightsDatabaseStatus expectedStatus, long startedAtMillis) {
        this.expectedStatus = expectedStatus;
        this.startedAtMillis = startedAtMillis;
    }

    public void recordProgress(OpenFlightsDatabaseStatus actualStatus, long observedAtMillis) {
        if (countriesReachedAtMillis < 0L && actualStatus.countries() >= expectedStatus.countries()) {
            countriesReachedAtMillis = observedAtMillis;
        }
        if (airlinesReachedAtMillis < 0L && actualStatus.airlines() >= expectedStatus.airlines()) {
            airlinesReachedAtMillis = observedAtMillis;
        }
        if (airportsReachedAtMillis < 0L && actualStatus.airports() >= expectedStatus.airports()) {
            airportsReachedAtMillis = observedAtMillis;
        }
        if (planesReachedAtMillis < 0L && actualStatus.planes() >= expectedStatus.planes()) {
            planesReachedAtMillis = observedAtMillis;
        }
        if (routesReachedAtMillis < 0L && actualStatus.routes() >= expectedStatus.routes()) {
            routesReachedAtMillis = observedAtMillis;
        }
        if (routeEquipmentCodesReachedAtMillis < 0L
                && actualStatus.routeEquipmentCodes() >= expectedStatus.routeEquipmentCodes()) {
            routeEquipmentCodesReachedAtMillis = observedAtMillis;
        }
    }

    public OpenFlightsIngestionTimings finish(long finishedAtMillis) {
        return new OpenFlightsIngestionTimings(
                elapsedSinceStart(countriesReachedAtMillis),
                elapsedSinceStart(airlinesReachedAtMillis),
                elapsedSinceStart(airportsReachedAtMillis),
                elapsedSinceStart(planesReachedAtMillis),
                elapsedSinceStart(routesReachedAtMillis),
                elapsedSinceStart(routeEquipmentCodesReachedAtMillis),
                elapsedSinceStart(finishedAtMillis)
        );
    }

    private long elapsedSinceStart(long observedAtMillis) {
        if (observedAtMillis < 0L) {
            return -1L;
        }
        return observedAtMillis - startedAtMillis;
    }
}
