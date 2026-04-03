package dev.nklip.javacraft.openflights.jpa.repository;

import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AirlineRepository extends JpaRepository<AirlineEntity, Integer> {

    List<AirlineEntity> findByCountryName(String countryName);
}
