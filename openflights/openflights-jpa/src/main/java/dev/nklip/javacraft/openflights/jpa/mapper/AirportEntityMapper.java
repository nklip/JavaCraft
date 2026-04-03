package dev.nklip.javacraft.openflights.jpa.mapper;

import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class AirportEntityMapper {

    public AirportEntity toEntity(Airport airport) {
        return toEntity(airport, airport.country());
    }

    public AirportEntity toEntity(Airport airport, String countryName) {
        Objects.requireNonNull(airport, "airport must not be null");

        AirportEntity airportEntity = new AirportEntity();
        airportEntity.setAirportId(airport.airportId());
        airportEntity.setName(airport.name());
        airportEntity.setCity(airport.city());
        airportEntity.setCountryName(countryName);
        airportEntity.setIataCode(airport.iataCode());
        airportEntity.setIcaoCode(airport.icaoCode());
        airportEntity.setLatitude(airport.latitude());
        airportEntity.setLongitude(airport.longitude());
        airportEntity.setAltitudeFeet(airport.altitudeFeet());
        airportEntity.setTimezoneHours(airport.timezoneHours());
        airportEntity.setDaylightSavingTime(airport.daylightSavingTime());
        airportEntity.setTimezoneName(airport.timezoneName());
        airportEntity.setType(airport.type());
        airportEntity.setSource(airport.source());
        return airportEntity;
    }
}
