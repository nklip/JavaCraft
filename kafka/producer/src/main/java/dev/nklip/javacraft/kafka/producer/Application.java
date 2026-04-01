package dev.nklip.javacraft.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hello world!
 *
 */
@Slf4j
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        log.info("Kafka Producer Application starting...");
        SpringApplication.run(Application.class, args);
    }
}
