package dev.nklip.javacraft.tictactoe.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/**
 * User: Lipatov Nikita
 */
public class GameSettingsTest {

    @BeforeEach
    public void setUp() throws Exception {
        resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        GameSettings settings = GameSettings.getInstance();
        settings.setOddGame(false);
        settings.setComputer(true);
        settings.setFirstGamerMove(true);
    }

    @Test
    public void testGameSettings_singletonAndDefaultValues() {
        GameSettings first = GameSettings.getInstance();
        GameSettings second = GameSettings.getInstance();

        Assertions.assertSame(first, second);
        Assertions.assertFalse(first.isOddGame());
        Assertions.assertTrue(first.isComputer());
        Assertions.assertTrue(first.isFirstGamerMove());
    }

    @Test
    public void testGameSettings_settersAndChangeMove() {
        GameSettings settings = GameSettings.getInstance();

        settings.setOddGame(true);
        settings.setComputer(false);
        settings.setFirstGamerMove(false);

        Assertions.assertTrue(settings.isOddGame());
        Assertions.assertFalse(settings.isComputer());
        Assertions.assertFalse(settings.isFirstGamerMove());

        settings.changeGamerMove();
        Assertions.assertTrue(settings.isFirstGamerMove());

        settings.changeGamerMove();
        Assertions.assertFalse(settings.isFirstGamerMove());
    }

    private void resetSingleton() throws Exception {
        Field instanceField = GameSettings.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }
}

