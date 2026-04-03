package dev.nklip.javacraft.openflights.kafka.producer.config;

import dev.nklip.javacraft.openflights.data.reader.impl.ClasspathOpenFlightsDataReader;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import dev.nklip.javacraft.openflights.data.parser.AirlineDatParser;
import dev.nklip.javacraft.openflights.data.parser.AirportDatParser;
import dev.nklip.javacraft.openflights.data.parser.CountryDatParser;
import dev.nklip.javacraft.openflights.data.parser.PlaneDatParser;
import dev.nklip.javacraft.openflights.data.parser.RouteDatParser;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring wiring for OpenFlights source-data parsing and import-side execution.
 *
 * <p>Why this configuration exists:
 *
 * <p>The OpenFlights producer imports data from static source files located in the {@code openflights-data} module.
 * That data module intentionally stays framework-light: it provides plain Java parsers and readers for the
 * OpenFlights {@code .dat} files, but it does not expose Spring configuration of its own. This keeps the data module
 * reusable in tests and other modules without forcing a Spring dependency boundary on it.
 *
 * <p>Because of that design, the producer module is responsible for turning those plain Java components into Spring
 * beans. This configuration is the place where that happens. It bridges the gap between:
 * - the framework-neutral parsing layer from {@code openflights-data}
 * - the Spring Boot producer application that needs those components injected into import services
 *
 * <p>Main responsibilities of this class:
 *
 * <p>1. expose one parser bean per OpenFlights dataset
 * <p>2. assemble those parsers into an {@link OpenFlightsDataReader}
 * <p>3. provide a dedicated executor for parallel import work
 *
 * <p>Why the parser beans are declared individually:
 *
 * <p>Each OpenFlights dataset has its own parser because the file formats are related but not identical. Countries,
 * airlines, airports, planes, and routes each map to different shared API types and have slightly different field
 * rules. Registering them as separate beans keeps those responsibilities explicit and makes injection into higher-level
 * services straightforward.
 *
 * <p>Why the {@link OpenFlightsDataReader} is assembled here:
 *
 * <p>The reader is the higher-level entry point used by the producer import flow. It knows how to locate the classpath
 * resources and delegate each source file to the correct parser. That is application wiring rather than low-level data
 * parsing, so this configuration is the right place to assemble it.
 *
 * <p>Why this lives in the producer module instead of the data module:
 *
 * <p>The data module should answer "how do we parse OpenFlights source files?" The producer module should answer "how
 * does the running application obtain and use those parsers?" Keeping Spring bean creation here preserves that module
 * boundary:
 * - {@code openflights-data} stays focused on source-file reading and parsing
 * - {@code openflights-kafka-producer} owns application wiring and import orchestration
 *
 * <p>Why there is a dedicated import executor:
 *
 * <p>OpenFlights imports can publish large datasets, especially for routes. The producer does not want to perform that
 * entire import flow on a request thread or with the generic task executor used by unrelated parts of the application.
 * A dedicated executor gives the import process its own bounded thread pool and makes the concurrency policy explicit.
 *
 * <p>Why the executor is sized from available processors:
 *
 * <p>The import pipeline is mostly local parsing, object mapping, and Kafka send orchestration. That work benefits from
 * moderate parallelism, but it should not create an arbitrary number of threads. Sizing from the available processor
 * count provides a practical default that scales with the machine while remaining bounded. The small queue gives the
 * producer some buffering room without letting import tasks grow unbounded in memory.
 *
 * <p>In short, this class is the producer-side bridge between raw OpenFlights source-file utilities and the Spring
 * application runtime: it turns plain parsing components into injectable beans and provides the executor used to import
 * and publish the datasets efficiently.
 */
@Configuration
public class OpenFlightsDataConfiguration {

    @Bean
    public CountryDatParser countryDatParser() {
        return new CountryDatParser();
    }

    @Bean
    public AirlineDatParser airlineDatParser() {
        return new AirlineDatParser();
    }

    @Bean
    public AirportDatParser airportDatParser() {
        return new AirportDatParser();
    }

    @Bean
    public PlaneDatParser planeDatParser() {
        return new PlaneDatParser();
    }

    @Bean
    public RouteDatParser routeDatParser() {
        return new RouteDatParser();
    }

    @Bean
    public OpenFlightsDataReader openFlightsDataReader(
            CountryDatParser countryDatParser,
            AirlineDatParser airlineDatParser,
            AirportDatParser airportDatParser,
            PlaneDatParser planeDatParser,
            RouteDatParser routeDatParser
    ) {
        return new ClasspathOpenFlightsDataReader(
                countryDatParser,
                airlineDatParser,
                airportDatParser,
                planeDatParser,
                routeDatParser
        );
    }

    @Bean(name = "openFlightsImportExecutor")
    public Executor openFlightsImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Math.max(2, Runtime.getRuntime().availableProcessors());
        executor.setCorePoolSize(processors);
        executor.setMaxPoolSize(processors);
        executor.setQueueCapacity(processors * 4);
        executor.setThreadNamePrefix("openflights-import-");
        executor.initialize();
        return executor;
    }
}
