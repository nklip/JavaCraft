package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.repository.AirlineRepository;
import dev.nklip.javacraft.openflights.jpa.repository.AirportRepository;
import dev.nklip.javacraft.openflights.jpa.repository.CountryRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenFlightsPlaceholderWriteServiceTest {

    @Test
    void testSaveCountryPlaceholderDelegatesToCountryRepository() {
        CountryRepository countryRepository = mock(CountryRepository.class);
        OpenFlightsPlaceholderWriteService placeholderWriteService = new OpenFlightsPlaceholderWriteService(
                countryRepository,
                mock(AirlineRepository.class),
                mock(AirportRepository.class)
        );
        CountryEntity countryEntity = new CountryEntity();
        countryEntity.setName("Peru");

        Assertions.assertDoesNotThrow(() -> placeholderWriteService.saveCountryPlaceholder(countryEntity));

        verify(countryRepository).saveAndFlush(countryEntity);
    }

    @Test
    void testSaveAirlinePlaceholderDelegatesToAirlineRepository() {
        AirlineRepository airlineRepository = mock(AirlineRepository.class);
        OpenFlightsPlaceholderWriteService placeholderWriteService = new OpenFlightsPlaceholderWriteService(
                mock(CountryRepository.class),
                airlineRepository,
                mock(AirportRepository.class)
        );
        AirlineEntity airlineEntity = new AirlineEntity();
        airlineEntity.setAirlineId(410);

        Assertions.assertDoesNotThrow(() -> placeholderWriteService.saveAirlinePlaceholder(airlineEntity));

        verify(airlineRepository).saveAndFlush(airlineEntity);
    }

    @Test
    void testSaveAirportPlaceholderDelegatesToAirportRepository() {
        AirportRepository airportRepository = mock(AirportRepository.class);
        OpenFlightsPlaceholderWriteService placeholderWriteService = new OpenFlightsPlaceholderWriteService(
                mock(CountryRepository.class),
                mock(AirlineRepository.class),
                airportRepository
        );
        AirportEntity airportEntity = new AirportEntity();
        airportEntity.setAirportId(2965);

        Assertions.assertDoesNotThrow(() -> placeholderWriteService.saveAirportPlaceholder(airportEntity));

        verify(airportRepository).saveAndFlush(airportEntity);
    }
}
