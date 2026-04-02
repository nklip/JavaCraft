package dev.nklip.javacraft.xlspaceship.engine.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Data
@Service
@RequiredArgsConstructor
public class LocalPlayerService {

    private final RandomProvider randomProvider;

    private String userId;
    private String fullName;
    private boolean isAI = false;

    // is called from Application
    public void setUpAI() {
        isAI = true;
        userId = "AI";
        fullName = String.format("AI-%s", randomProvider.generateAI());
    }

}
