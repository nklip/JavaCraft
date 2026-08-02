package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.api.query.ProjectionUpdateResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages SSE subscribers and pushes projection updates to them.
 * Architecture mapping: implements the final {@code projector -> sse -> stream} hop from the Runtime Topology and
 * Projection Flow.
 */
@Service
public class ProjectionSsePublisher implements SmartLifecycle {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ignored -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    public void publish(ProjectionUpdateResponse update) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("projection-update")
                        .data(update));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Use Spring's highest lifecycle phase so SSE subscriptions stop before Spring Boot starts graceful web-server
     * shutdown. Otherwise the infinite {@link SseEmitter} requests remain active and keep Tomcat waiting until the
     * shutdown timeout expires.
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }
}
