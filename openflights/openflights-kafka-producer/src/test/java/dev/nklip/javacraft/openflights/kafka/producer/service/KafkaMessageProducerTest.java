package dev.nklip.javacraft.openflights.kafka.producer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.kafka.OpenFlightsTopics;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import java.util.concurrent.CompletableFuture;
import java.util.List;
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
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        String message = "hello kafka";

        when(kafkaTemplate.send(eq(OpenFlightsTopics.DEFAULT), eq(message), eq(message)))
                .thenReturn(completedSendResult(OpenFlightsTopics.DEFAULT, message, message));

        Assertions.assertDoesNotThrow(() -> service.sendMessage2DefaultTopic(message));

        verify(kafkaTemplate).send(OpenFlightsTopics.DEFAULT, message, message);
    }

    @Test
    void sendCountrySendsJsonPayloadToCountryTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        Country country = new Country("Russia", "RU", "RS");

        when(kafkaTemplate.send(eq(OpenFlightsTopics.COUNTRY), eq("RU"), eq(country)))
                .thenReturn(completedSendResult(OpenFlightsTopics.COUNTRY, "RU", country));

        Assertions.assertDoesNotThrow(() -> service.sendCountry(country));

        verify(kafkaTemplate).send(OpenFlightsTopics.COUNTRY, "RU", country);
    }

    @Test
    void sendAirlineSendsJsonPayloadToAirlineTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Russia", true);

        when(kafkaTemplate.send(eq(OpenFlightsTopics.AIRLINE), eq("410"), eq(airline)))
                .thenReturn(completedSendResult(OpenFlightsTopics.AIRLINE, "410", airline));

        Assertions.assertDoesNotThrow(() -> service.sendAirline(airline));

        verify(kafkaTemplate).send(OpenFlightsTopics.AIRLINE, "410", airline);
    }

    @Test
    void sendAirportSendsJsonPayloadToAirportTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        Airport airport = new Airport(2965, "Sochi Airport", "Sochi", "Russia", "AER", "URSS",
                43.449902, 39.9566, 89, 3.0, "E", "Europe/Moscow", "airport", "OurAirports");

        when(kafkaTemplate.send(eq(OpenFlightsTopics.AIRPORT), eq("2965"), eq(airport)))
                .thenReturn(completedSendResult(OpenFlightsTopics.AIRPORT, "2965", airport));

        Assertions.assertDoesNotThrow(() -> service.sendAirport(airport));

        verify(kafkaTemplate).send(OpenFlightsTopics.AIRPORT, "2965", airport);
    }

    @Test
    void sendPlaneSendsJsonPayloadToPlaneTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        Plane plane = new Plane("Bombardier CRJ200", "CR2", "CRJ2");

        when(kafkaTemplate.send(eq(OpenFlightsTopics.PLANE), eq("CRJ2"), eq(plane)))
                .thenReturn(completedSendResult(OpenFlightsTopics.PLANE, "CRJ2", plane));

        Assertions.assertDoesNotThrow(() -> service.sendPlane(plane));

        verify(kafkaTemplate).send(OpenFlightsTopics.PLANE, "CRJ2", plane);
    }

    @Test
    void sendRouteSendsJsonPayloadToRouteTopic() {
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        Route route = new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ"));
        String routeKey = "410|2965|2990|0";

        when(kafkaTemplate.send(eq(OpenFlightsTopics.ROUTE), eq(routeKey), eq(route)))
                .thenReturn(completedSendResult(OpenFlightsTopics.ROUTE, routeKey, route));

        Assertions.assertDoesNotThrow(() -> service.sendRoute(route));

        verify(kafkaTemplate).send(OpenFlightsTopics.ROUTE, routeKey, route);
    }

    @Test
    void sendMessage2DefaultTopicHandlesFailedCallback() {
        KafkaTemplate<String, Object> kafkaTemplate = mockKafkaTemplate();
        KafkaMessageProducer service = new KafkaMessageProducer(kafkaTemplate);
        String message = "hello kafka";
        RuntimeException failure = new RuntimeException("broker unavailable");

        when(kafkaTemplate.send(eq(OpenFlightsTopics.DEFAULT), eq(message), eq(message)))
                .thenReturn(CompletableFuture.failedFuture(failure));

        Assertions.assertDoesNotThrow(() -> service.sendMessage2DefaultTopic(message));

        verify(kafkaTemplate).send(OpenFlightsTopics.DEFAULT, message, message);
    }

    private CompletableFuture<SendResult<String, Object>> completedSendResult(String topic, String key, Object value) {
        RecordMetadata recordMetadata = new RecordMetadata(
                new TopicPartition(topic, 0),
                10L,
                2,
                0L,
                0,
                0
        );
        SendResult<String, Object> sendResult = new SendResult<>(
                new ProducerRecord<>(topic, key, value),
                recordMetadata
        );
        return CompletableFuture.completedFuture(sendResult);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> mockKafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
