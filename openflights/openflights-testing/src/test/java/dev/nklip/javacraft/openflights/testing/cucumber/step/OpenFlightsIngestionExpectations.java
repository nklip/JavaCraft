package dev.nklip.javacraft.openflights.testing.cucumber.step;

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
