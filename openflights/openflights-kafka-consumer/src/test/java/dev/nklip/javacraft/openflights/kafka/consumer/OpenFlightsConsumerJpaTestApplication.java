package dev.nklip.javacraft.openflights.kafka.consumer;

import dev.nklip.javacraft.openflights.jpa.mapper.AirlineEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.AirportEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.CountryEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.PlaneEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.RouteEntityMapper;
import dev.nklip.javacraft.openflights.jpa.normalize.CountryNameNormalizer;
import dev.nklip.javacraft.openflights.kafka.consumer.service.OpenFlightsPlaceholderWriteService;
import dev.nklip.javacraft.openflights.kafka.consumer.service.OpenFlightsPersistenceService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableJpaRepositories("dev.nklip.javacraft.openflights.jpa.repository")
@EntityScan("dev.nklip.javacraft.openflights.jpa.entity")
@Import({
        OpenFlightsPersistenceService.class,
        OpenFlightsPlaceholderWriteService.class,
        CountryNameNormalizer.class,
        CountryEntityMapper.class,
        AirlineEntityMapper.class,
        AirportEntityMapper.class,
        PlaneEntityMapper.class,
        RouteEntityMapper.class
})
@SpringBootConfiguration
@EnableAutoConfiguration
public class OpenFlightsConsumerJpaTestApplication {
}
