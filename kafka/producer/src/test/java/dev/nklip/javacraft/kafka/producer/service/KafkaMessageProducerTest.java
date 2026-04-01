package dev.nklip.javacraft.kafka.producer.service;

import java.util.concurrent.CompletableFuture;
import dev.nklip.javacraft.kafka.producer.config.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaMessageProducerTest {

    @Test
    void sendMessage2DefaultTopicSendsMessageAndHandlesSuccessfulCallback() {
        KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        String message = "hello kafka";

        RecordMetadata recordMetadata = new RecordMetadata(
                new TopicPartition(Topics.DEFAULT, 0),
                10L,
                2,
                0L,
                0,
                0
        );
        SendResult<String, String> sendResult = new SendResult<>(
                new ProducerRecord<>(Topics.DEFAULT, message),
                recordMetadata
        );
        when(kafkaTemplate.send(eq(Topics.DEFAULT), eq(message)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        Assertions.assertDoesNotThrow(() -> service.sendMessage2DefaultTopic(message));

        verify(kafkaTemplate).send(Topics.DEFAULT, message);
    }

    @Test
    void sendMessage2DefaultTopicSendsMessageAndHandlesFailedCallback() {
        KafkaTemplate<String, String> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        String message = "hello kafka";
        RuntimeException failure = new RuntimeException("broker unavailable");

        when(kafkaTemplate.send(eq(Topics.DEFAULT), eq(message)))
                .thenReturn(CompletableFuture.failedFuture(failure));

        Assertions.assertDoesNotThrow(() -> service.sendMessage2DefaultTopic(message));

        verify(kafkaTemplate).send(Topics.DEFAULT, message);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockKafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
