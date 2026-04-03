package dev.nklip.javacraft.openflights.kafka.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Hello world!
 */
@Slf4j
@EnableJpaRepositories("dev.nklip.javacraft.openflights.jpa.repository")
@EntityScan("dev.nklip.javacraft.openflights.jpa.entity")
@SpringBootApplication(scanBasePackages = "dev.nklip.javacraft.openflights")
public class KafkaConsumerApplication {
    public static void main(String[] args) {
        log.info("OpenFlights Kafka consumer application starting...");
        SpringApplication.run(KafkaConsumerApplication.class, args);
    }
}
