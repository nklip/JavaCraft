package dev.nklip.javacraft.openflights.testing;

public record OpenFlightsIngestionExpectations(
        int submittedCountries,
        int submittedAirlines,
        int submittedAirports,
        int submittedPlanes,
        int submittedRoutes,
        OpenFlightsDatabaseStatus expectedAfterCountries,
        OpenFlightsDatabaseStatus expectedAfterAirlines,
        OpenFlightsDatabaseStatus expectedAfterAirports,
        OpenFlightsDatabaseStatus expectedAfterPlanes,
        OpenFlightsDatabaseStatus expectedDatabaseStatus
) {
}
