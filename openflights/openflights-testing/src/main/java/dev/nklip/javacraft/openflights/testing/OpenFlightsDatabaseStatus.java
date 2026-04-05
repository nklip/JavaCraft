package dev.nklip.javacraft.openflights.testing;

public record OpenFlightsDatabaseStatus(
        long countries,
        long airlines,
        long airports,
        long planes,
        long routes,
        long routeEquipmentCodes
) {
}
