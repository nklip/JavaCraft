package dev.nklip.javacraft.kafka.consumer.service;

import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.kafka.consumer.config.KafkaConsumerConfiguration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@SuppressWarnings("unused")
@Service
@Slf4j
public class KafkaMessageConsumer {

    @KafkaListener(topics = "javacraft-kafka-topic", groupId = KafkaConsumerConfiguration.GROUP_ID)
    public void listenGroupFoo(String message) {
        log.info("Received Message in group foo: " + message);
    }
}
