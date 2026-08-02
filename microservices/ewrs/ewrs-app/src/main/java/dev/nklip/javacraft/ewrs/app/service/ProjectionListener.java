package dev.nklip.javacraft.ewrs.app.service;

import dev.nklip.javacraft.ewrs.app.repository.EventStoreRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Background PostgreSQL listener that wakes the projector when new events are appended.
 * Architecture mapping: implements the {@code event_store -> NOTIFY -> listener -> coordinator} portion of the
 * Projection Flow and starts automatically with the application.
 */
@Component
@ConditionalOnProperty(
        name = "ewrs.projector.listener.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProjectionListener {

    private static final int LISTEN_TIMEOUT_MILLIS = 1000;
    private static final long RECONNECT_DELAY_MILLIS = 1000L;
    private static final Logger log = LoggerFactory.getLogger(ProjectionListener.class);

    private final DataSource dataSource;
    private final ProjectionCoordinator projectionCoordinator;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(new ProjectorThreadFactory());

    public ProjectionListener(DataSource dataSource, ProjectionCoordinator projectionCoordinator) {
        this.dataSource = dataSource;
        this.projectionCoordinator = projectionCoordinator;
    }

    @PostConstruct
    public void start() {
        executorService.submit(this::runLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executorService.shutdownNow();
    }

    private void runLoop() {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(true);
                statement.execute("listen " + EventStoreRepository.NOTIFY_CHANNEL);

                projectionCoordinator.catchUpFromLastApplied();

                PGConnection pgConnection = connection.unwrap(PGConnection.class);
                while (running.get()) {
                    pgConnection.getNotifications(LISTEN_TIMEOUT_MILLIS);
                    projectionCoordinator.catchUpFromLastApplied();
                }
            } catch (Exception e) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.warn("EWRS projection listener lost its PostgreSQL LISTEN/NOTIFY connection. Retrying.", e);
                sleepBeforeReconnect();
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(RECONNECT_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Creates the dedicated background thread used by the PostgreSQL LISTEN loop.
     * Architecture mapping: infrastructure detail for the listener stage of the Projection Flow.
     */
    private static final class ProjectorThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            Thread thread = new Thread(runnable, "ewrs-projector-listener");
            thread.setDaemon(true);
            return thread;
        }
    }
}
