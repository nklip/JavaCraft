package dev.nklip.javacraft.openflights.jpa.mapper;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class AirlineEntityMapper {

    public AirlineEntity toEntity(Airline airline) {
        return toEntity(airline, airline.country());
    }

    public AirlineEntity toEntity(Airline airline, String countryName) {
        Objects.requireNonNull(airline, "airline must not be null");

        AirlineEntity airlineEntity = new AirlineEntity();
        airlineEntity.setAirlineId(airline.airlineId());
        airlineEntity.setName(airline.name());
        airlineEntity.setAlias(airline.alias());
        airlineEntity.setIataCode(airline.iataCode());
        airlineEntity.setIcaoCode(airline.icaoCode());
        airlineEntity.setCallsign(airline.callsign());
        airlineEntity.setCountryName(countryName);
        airlineEntity.setActive(airline.active());
        return airlineEntity;
    }
}
