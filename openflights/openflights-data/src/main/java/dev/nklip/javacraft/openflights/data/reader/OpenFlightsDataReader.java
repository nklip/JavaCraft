package dev.nklip.javacraft.openflights.data.reader;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import java.util.List;

public interface OpenFlightsDataReader {

    List<Country> readCountries();

    List<Airline> readAirlines();

    List<Airport> readAirports();

    List<Plane> readPlanes();

    List<Route> readRoutes();
}
