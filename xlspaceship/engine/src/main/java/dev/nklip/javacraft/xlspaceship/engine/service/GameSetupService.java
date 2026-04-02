package dev.nklip.javacraft.xlspaceship.engine.service;

import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameSetupService {

    private static final AtomicLong sequenceId = new AtomicLong();

    private final RandomProvider randomProvider;

    public String nextUniqueGameId() {
        return String.format("match-%s-%s-%s",
                sequenceId.incrementAndGet(),
                randomProvider.generateUp10(),
                randomProvider.generateUp10()
        );
    }

    /**
     * 1 - challenger
     * 2 - defender
     */
    public String chooseStartingPlayer(String challenger, String defender) {
        int p1 = 1;
        int randNumber = randomProvider.generatePlayer();
        return (p1 == randNumber) ? challenger : defender;
    }

}
