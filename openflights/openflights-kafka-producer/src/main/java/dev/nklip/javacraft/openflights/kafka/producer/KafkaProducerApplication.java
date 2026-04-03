package dev.nklip.javacraft.openflights.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hello world!
 */
@Slf4j
@SpringBootApplication
public class KafkaProducerApplication {
    public static void main(String[] args) {
        log.info("OpenFlights Kafka producer application starting...");
        SpringApplication.run(KafkaProducerApplication.class, args);
    }
}
