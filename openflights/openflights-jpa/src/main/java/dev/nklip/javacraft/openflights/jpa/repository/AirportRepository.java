package dev.nklip.javacraft.openflights.jpa.repository;

import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AirportRepository extends JpaRepository<AirportEntity, Integer> {

    List<AirportEntity> findByCountryName(String countryName);

    Optional<AirportEntity> findByIataCode(String iataCode);
}
