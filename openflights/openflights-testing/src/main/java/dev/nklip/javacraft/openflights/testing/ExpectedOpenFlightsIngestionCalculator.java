package dev.nklip.javacraft.openflights.testing;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import dev.nklip.javacraft.openflights.jpa.mapper.RouteEntityMapper;
import dev.nklip.javacraft.openflights.jpa.normalize.CountryNameNormalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExpectedOpenFlightsIngestionCalculator {

    private final OpenFlightsDataReader dataReader;
    private final CountryNameNormalizer countryNameNormalizer;
    private final RouteEntityMapper routeEntityMapper;

    public OpenFlightsIngestionExpectations calculate() {
        List<Country> countries = dataReader.readCountries();
        List<Airline> airlines = dataReader.readAirlines();
        List<Airport> airports = dataReader.readAirports();
        List<Plane> planes = dataReader.readPlanes();
        List<Route> routes = dataReader.readRoutes();

        OpenFlightsDatabaseStatus expectedAfterCountries = new OpenFlightsDatabaseStatus(
                calculateExpectedCountryCount(countries, List.of(), List.of()),
                0L,
                0L,
                0L,
                0L,
                0L
        );

        OpenFlightsDatabaseStatus expectedAfterAirlines = new OpenFlightsDatabaseStatus(
                calculateExpectedCountryCount(countries, airlines, List.of()),
                calculateExpectedAirlineCount(airlines, List.of()),
                0L,
                0L,
                0L,
                0L
        );

        OpenFlightsDatabaseStatus expectedAfterAirports = new OpenFlightsDatabaseStatus(
                calculateExpectedCountryCount(countries, airlines, airports),
                calculateExpectedAirlineCount(airlines, List.of()),
                calculateExpectedAirportCount(airports, List.of()),
                0L,
                0L,
                0L
        );

        OpenFlightsDatabaseStatus expectedAfterPlanes = new OpenFlightsDatabaseStatus(
                calculateExpectedCountryCount(countries, airlines, airports),
                calculateExpectedAirlineCount(airlines, List.of()),
                calculateExpectedAirportCount(airports, List.of()),
                calculateExpectedPlaneCount(planes),
                0L,
                0L
        );

        OpenFlightsDatabaseStatus expectedDatabaseStatus = new OpenFlightsDatabaseStatus(
                calculateExpectedCountryCount(countries, airlines, airports),
                calculateExpectedAirlineCount(airlines, routes),
                calculateExpectedAirportCount(airports, routes),
                calculateExpectedPlaneCount(planes),
                calculateExpectedRouteCount(routes),
                calculateExpectedRouteEquipmentCodeCount(routes)
        );

        return new OpenFlightsIngestionExpectations(
                countries.size(),
                airlines.size(),
                airports.size(),
                planes.size(),
                routes.size(),
                expectedAfterCountries,
                expectedAfterAirlines,
                expectedAfterAirports,
                expectedAfterPlanes,
                expectedDatabaseStatus
        );
    }

    public void confirmExpectedDatabaseStatus(OpenFlightsDatabaseStatus expectedStatusFromTable) {
        OpenFlightsDatabaseStatus calculatedStatus = calculate().expectedDatabaseStatus();
        if (!calculatedStatus.equals(expectedStatusFromTable)) {
            throw new IllegalStateException(
                    "Expected OpenFlights dataset table is out of sync with the current source datasets. "
                            + "Expected from feature table=%s, calculated from source files=%s"
                            .formatted(expectedStatusFromTable, calculatedStatus)
            );
        }
    }

    private long calculateExpectedCountryCount(
            List<Country> countries,
            List<Airline> airlines,
            List<Airport> airports
    ) {
        Set<String> persistedCountryNames = calculatePersistedCountryNames(countries);
        airlines.stream()
                .map(Airline::country)
                .map(countryNameNormalizer::normalize)
                .filter(Objects::nonNull)
                .forEach(persistedCountryNames::add);
        airports.stream()
                .map(Airport::country)
                .map(countryNameNormalizer::normalize)
                .filter(Objects::nonNull)
                .forEach(persistedCountryNames::add);
        return persistedCountryNames.size();
    }

    private Set<String> calculatePersistedCountryNames(List<Country> countries) {
        List<MutableCountryState> persistedCountries = new ArrayList<>();
        Map<String, MutableCountryState> countriesByIsoCode = new HashMap<>();
        Map<String, MutableCountryState> countriesByName = new HashMap<>();

        for (Country country : countries) {
            MutableCountryState existingCountry = findExistingCountry(country, countriesByIsoCode, countriesByName);
            if (existingCountry == null) {
                existingCountry = new MutableCountryState();
                persistedCountries.add(existingCountry);
            } else {
                removeCountryIndexes(existingCountry, countriesByIsoCode, countriesByName);
            }

            existingCountry.name = trimToNull(country.name());
            existingCountry.isoCode = trimToNull(country.isoCode());
            indexCountry(existingCountry, countriesByIsoCode, countriesByName);
        }

        Set<String> persistedCountryNames = new HashSet<>();
        for (MutableCountryState persistedCountry : persistedCountries) {
            if (persistedCountry.name != null) {
                persistedCountryNames.add(persistedCountry.name);
            }
        }
        return persistedCountryNames;
    }

    private MutableCountryState findExistingCountry(
            Country country,
            Map<String, MutableCountryState> countriesByIsoCode,
            Map<String, MutableCountryState> countriesByName
    ) {
        String isoCode = trimToNull(country.isoCode());
        if (isoCode != null) {
            MutableCountryState byIsoCode = countriesByIsoCode.get(isoCode);
            if (byIsoCode != null) {
                return byIsoCode;
            }
        }

        String countryName = trimToNull(country.name());
        if (countryName != null) {
            return countriesByName.get(countryName);
        }
        return null;
    }

    private void removeCountryIndexes(
            MutableCountryState country,
            Map<String, MutableCountryState> countriesByIsoCode,
            Map<String, MutableCountryState> countriesByName
    ) {
        if (country.isoCode != null) {
            countriesByIsoCode.remove(country.isoCode);
        }
        if (country.name != null) {
            countriesByName.remove(country.name);
        }
    }

    private void indexCountry(
            MutableCountryState country,
            Map<String, MutableCountryState> countriesByIsoCode,
            Map<String, MutableCountryState> countriesByName
    ) {
        if (country.isoCode != null) {
            countriesByIsoCode.put(country.isoCode, country);
        }
        if (country.name != null) {
            countriesByName.put(country.name, country);
        }
    }

    private long calculateExpectedAirlineCount(List<Airline> airlines, List<Route> routes) {
        Set<Integer> airlineIds = new HashSet<>();
        airlines.stream()
                .map(Airline::airlineId)
                .filter(Objects::nonNull)
                .forEach(airlineIds::add);
        routes.stream()
                .map(Route::airlineId)
                .filter(Objects::nonNull)
                .forEach(airlineIds::add);
        return airlineIds.size();
    }

    private long calculateExpectedAirportCount(List<Airport> airports, List<Route> routes) {
        Set<Integer> airportIds = new HashSet<>();
        airports.stream()
                .map(Airport::airportId)
                .filter(Objects::nonNull)
                .forEach(airportIds::add);
        routes.stream()
                .map(Route::sourceAirportId)
                .filter(Objects::nonNull)
                .forEach(airportIds::add);
        routes.stream()
                .map(Route::destinationAirportId)
                .filter(Objects::nonNull)
                .forEach(airportIds::add);
        return airportIds.size();
    }

    private long calculateExpectedPlaneCount(List<Plane> planes) {
        return planes.stream()
                .map(plane -> new PlaneNaturalKey(plane.name(), plane.iataCode(), plane.icaoCode()))
                .distinct()
                .count();
    }

    private long calculateExpectedRouteCount(List<Route> routes) {
        return calculateLatestEquipmentSizesByRouteKey(routes).size();
    }

    private long calculateExpectedRouteEquipmentCodeCount(List<Route> routes) {
        return calculateLatestEquipmentSizesByRouteKey(routes).values().stream()
                .mapToLong(Integer::longValue)
                .sum();
    }

    private Map<String, Integer> calculateLatestEquipmentSizesByRouteKey(List<Route> routes) {
        Map<String, Integer> latestEquipmentSizesByRouteKey = new LinkedHashMap<>();
        for (Route route : routes) {
            latestEquipmentSizesByRouteKey.put(routeEntityMapper.toRouteKey(route), route.equipmentCodes().size());
        }
        return latestEquipmentSizesByRouteKey;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class MutableCountryState {
        private String name;
        private String isoCode;
    }

    private record PlaneNaturalKey(String name, String iataCode, String icaoCode) {
    }
}
