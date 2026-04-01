package dev.nklip.javacraft.linker.datamanager.service;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class SymbolGeneratorServices {

    private static final String ALL_AVAILABLE_SYMBOLS = "ABCDEFGHIJKLMNOPQRSTYVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static char generateSymbol() {
        return generateSymbol(ThreadLocalRandom.current());
    }

    static char generateSymbol(Random random) {
        return ALL_AVAILABLE_SYMBOLS.charAt(random.nextInt(ALL_AVAILABLE_SYMBOLS.length()));
    }

    public static String generateShortText(int length) {
        return generateShortText(length, ThreadLocalRandom.current());
    }

    static String generateShortText(int length, Random random) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder shortBuilder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            shortBuilder.append(generateSymbol(random));
        }
        return shortBuilder.toString();
    }

}
