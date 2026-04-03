package dev.nklip.javacraft.openflights.jpa.repository;

import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountryRepository extends JpaRepository<CountryEntity, Long> {

    Optional<CountryEntity> findByName(String name);

    Optional<CountryEntity> findByIsoCode(String isoCode);
}
