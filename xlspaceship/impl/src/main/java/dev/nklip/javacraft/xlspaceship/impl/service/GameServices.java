package dev.nklip.javacraft.xlspaceship.impl.service;

import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GameServices {

    private static final AtomicLong sequenceId = new AtomicLong();

    private final RandomServices randomServices;

    public String nextUniqueGameId() {
        return String.format("match-%s-%s-%s",
                sequenceId.incrementAndGet(),
                randomServices.generateUp10(),
                randomServices.generateUp10()
        );
    }

    /**
     * 1 - challenger
     * 2 - defender
     */
    public String chooseStartingPlayer(String challenger, String defender) {
        int p1 = 1;
        int randNumber = randomServices.generatePlayer();
        return (p1 == randNumber) ? challenger : defender;
    }

}
