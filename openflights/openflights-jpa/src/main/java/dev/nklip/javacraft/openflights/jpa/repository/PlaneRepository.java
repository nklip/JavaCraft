package dev.nklip.javacraft.openflights.jpa.repository;

import dev.nklip.javacraft.openflights.jpa.entity.PlaneEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaneRepository extends JpaRepository<PlaneEntity, Long> {

    Optional<PlaneEntity> findByNameAndIataCodeAndIcaoCode(String name, String iataCode, String icaoCode);

    Optional<PlaneEntity> findByIataCode(String iataCode);

    Optional<PlaneEntity> findByIcaoCode(String icaoCode);

    Optional<PlaneEntity> findByName(String name);
}
