package dev.nklip.javacraft.kafka.consumer.service;

import java.lang.reflect.Method;
import dev.nklip.javacraft.kafka.consumer.config.KafkaConsumerConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

class KafkaMessageConsumerTest {

    @Test
    void listenGroupFooHasKafkaListenerConfiguration() throws NoSuchMethodException {
        Method listenMethod = KafkaMessageConsumer.class.getDeclaredMethod("listenGroupFoo", String.class);

        KafkaListener kafkaListener = listenMethod.getAnnotation(KafkaListener.class);

        Assertions.assertNotNull(kafkaListener);
        Assertions.assertArrayEquals(new String[]{"javacraft-kafka-topic"}, kafkaListener.topics());
        Assertions.assertEquals(KafkaConsumerConfiguration.GROUP_ID, kafkaListener.groupId());
    }

    @Test
    void listenGroupFooAcceptsIncomingMessage() {
        KafkaMessageConsumer consumer = new KafkaMessageConsumer();

        Assertions.assertDoesNotThrow(() -> consumer.listenGroupFoo("message from topic"));
    }
}
