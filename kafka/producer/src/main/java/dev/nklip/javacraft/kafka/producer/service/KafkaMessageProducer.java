package dev.nklip.javacraft.kafka.producer.service;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.kafka.producer.config.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaMessageProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public void sendMessage2DefaultTopic(String message) {
        // send is async method
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(Topics.DEFAULT, message);
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Sent message=[{}] with offset=[{}}]", message, result.getRecordMetadata().offset());
            } else {
                log.info("Unable to send message=[{}] due to : {}", message, ex.getMessage());
            }
        });
    }
}
