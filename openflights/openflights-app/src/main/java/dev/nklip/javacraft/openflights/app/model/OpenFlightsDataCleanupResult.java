package dev.nklip.javacraft.openflights.app.model;

/**
 * Summary returned after deleting persisted OpenFlights data.
 */
public record OpenFlightsDataCleanupResult(
        long countriesDeleted,
        long airlinesDeleted,
        long airportsDeleted,
        long planesDeleted,
        long routesDeleted,
        long routeEquipmentCodesDeleted
) {

    public long totalDeleted() {
        return countriesDeleted + airlinesDeleted + airportsDeleted
                + planesDeleted + routesDeleted + routeEquipmentCodesDeleted;
    }
}
