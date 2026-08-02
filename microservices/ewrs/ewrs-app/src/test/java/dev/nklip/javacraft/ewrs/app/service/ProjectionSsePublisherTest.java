package dev.nklip.javacraft.ewrs.app.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ProjectionSsePublisherTest {

    @Test
    void stopCompletesActiveSubscribersBeforeWebServerGracefulShutdown() {
        ProjectionSsePublisher publisher = new ProjectionSsePublisher();
        publisher.start();
        SseEmitter emitter = publisher.subscribe();

        publisher.stop();

        Assertions.assertAll(
                () -> Assertions.assertFalse(publisher.isRunning()),
                () -> Assertions.assertEquals(SmartLifecycle.DEFAULT_PHASE, publisher.getPhase()),
                () -> Assertions.assertThrows(IllegalStateException.class, () -> emitter.send("after shutdown"))
        );
    }
}
