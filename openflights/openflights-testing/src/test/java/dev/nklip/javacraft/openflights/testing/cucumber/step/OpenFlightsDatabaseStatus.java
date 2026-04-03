package dev.nklip.javacraft.openflights.testing.cucumber.step;

public record OpenFlightsDatabaseStatus(
        long countries,
        long airlines,
        long airports,
        long planes,
        long routes,
        long routeEquipmentCodes
) {
}
