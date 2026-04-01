package dev.nklip.javacraft.vfs.server.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * @author Lipatov Nikita
 */
public class Timer {

    private final Clock clock;
    private volatile Instant lastMessageTime;

    public Timer() {
        this(Clock.systemUTC());
    }

    Timer(Clock clock) {
        this.clock = clock;
        this.lastMessageTime = Instant.now(clock);
    }

    public void updateTime() {
        lastMessageTime = Instant.now(clock);
    }

    /**
     *
     * @return number of minutes
     */
    public int difference() {
        return (int) Duration.between(lastMessageTime, Instant.now(clock)).toMinutes();
    }
}
