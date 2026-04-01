package dev.nklip.javacraft.tictactoe.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * User: Lipatov Nikita
 */
public class PlayerTest {

    @Test
    public void testPlayer_defaultConstructorAndSetters() {
        Player player = new Player();

        player.setName("nikita");
        player.setComputer(true);

        Assertions.assertEquals("nikita", player.getName());
        Assertions.assertTrue(player.isComputer());
    }

    @Test
    public void testPlayer_fullConstructorAndUpdates() {
        Player player = new Player("computer", true);

        Assertions.assertEquals("computer", player.getName());
        Assertions.assertTrue(player.isComputer());

        player.setName("human");
        player.setComputer(false);

        Assertions.assertEquals("human", player.getName());
        Assertions.assertFalse(player.isComputer());
    }
}

