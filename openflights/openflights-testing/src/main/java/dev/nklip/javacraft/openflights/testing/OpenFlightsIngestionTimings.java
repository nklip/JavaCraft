package dev.nklip.javacraft.openflights.testing;

public record OpenFlightsIngestionTimings(
        long countriesMillis,
        long airlinesMillis,
        long airportsMillis,
        long planesMillis,
        long routesMillis,
        long routeEquipmentCodesMillis,
        long totalMillis
) {
}
