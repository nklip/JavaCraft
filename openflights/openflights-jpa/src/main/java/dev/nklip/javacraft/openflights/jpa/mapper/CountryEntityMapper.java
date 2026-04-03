package dev.nklip.javacraft.openflights.jpa.mapper;

import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class CountryEntityMapper {

    public CountryEntity toEntity(Country country) {
        Objects.requireNonNull(country, "country must not be null");

        CountryEntity countryEntity = new CountryEntity();
        countryEntity.setName(country.name());
        countryEntity.setIsoCode(country.isoCode());
        countryEntity.setDafifCode(country.dafifCode());
        return countryEntity;
    }
}
