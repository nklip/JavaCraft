package dev.nklip.javacraft.xlspaceship.impl.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Data
@Service
@RequiredArgsConstructor
public class UserServices {

    private final RandomServices randomServices;

    private String userId;
    private String fullName;
    private boolean isAI = false;

    // is called from Application
    public void setUpAI() {
        isAI = true;
        userId = "AI";
        fullName = String.format("AI-%s", randomServices.generateAI());
    }

}
