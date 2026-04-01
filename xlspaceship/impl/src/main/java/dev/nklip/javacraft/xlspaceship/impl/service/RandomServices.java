package dev.nklip.javacraft.xlspaceship.impl.service;

import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class RandomServices {

    private static final Random greatRandom = new Random(); // should be thread safe

    public int generateAI() {
        return greatRandom.nextInt(2000) + 1;
    }

    public int generateForm() {
        return greatRandom.nextInt(4) + 1;
    }

    public int generatePlayer() {
        return greatRandom.nextInt(2) + 1;
    }

    public int generateCell(int size) {
        return greatRandom.nextInt(size);
    }

    public int generateUp10() {
        return greatRandom.nextInt(10) + 1;
    }

    public int generateUp16() {
        return greatRandom.nextInt(16);
    }


}
