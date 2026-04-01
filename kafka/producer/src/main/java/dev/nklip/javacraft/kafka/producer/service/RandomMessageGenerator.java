package dev.nklip.javacraft.kafka.producer.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RandomMessageGenerator {

    @Autowired
    KafkaMessageProducer kafkaMessageProducer;

    @PostConstruct
    public void sendMessages() throws InterruptedException {
        Thread.sleep(3000); // initial waiting

        for(int i = 0; i < 20; i++) {
            kafkaMessageProducer.sendMessage2DefaultTopic("Hello world - " + i);
            Thread.sleep(500);
        }

    }
}
